package ru.egor;

import java.util.List;

public final class BacktestRunner {
    private final BacktestParameters parameters;
    private final MarketMakingStrategy strategy;

    private OrderManager orderManager;
    private DesiredOrders desiredOrders;
    private OrderBookEvent currentOrderBook;
    private TradeEvent lastTrade;
    private Double currentFundingRate;
    private boolean staleBook;
    private int gapResetCount;
    private Portfolio portfolio;
    private BacktestMetrics metrics;
    private HourlyStatisticsCollector hourlyStatistics;
    private Long staleStartedAtNanos;
    private long lastEventTimeNanos;

    public BacktestRunner(BacktestParameters parameters) {
        this(
                parameters,
                new SymmetricMarketMakingStrategy(
                        parameters.getOrderSize(),
                        parameters.getHardInventoryLimit()
                )
        );
    }

    BacktestRunner(BacktestParameters parameters, MarketMakingStrategy strategy) {
        this.parameters = parameters;
        this.strategy = strategy;
        resetState();
    }

    // Последовательно проигрывает market tape в экономически значимом порядке:
    // команды, fills, состояние рынка, решение стратегии, reconciliation и метрики.
    public BacktestResult run(List<Event> eventTape) {
        resetState();
        if (eventTape.isEmpty()) {
            throw new IllegalArgumentException("Event tape не может быть пустым");
        }
        long startedAt = System.nanoTime();
        long processedEvents = 0;

        for (Event event : eventTape) {
            processEvent(event);
            processedEvents++;
        }

        closeRemainingOrders(lastEventTimeNanos);
        if (staleStartedAtNanos != null) {
            metrics.onStalePeriod(staleStartedAtNanos, lastEventTimeNanos);
        }
        hourlyStatistics.finish();
        List<BacktestSummaryRow> summaryRows = metrics.finish(lastEventTimeNanos);
        validateDailyPnl(summaryRows);

        BacktestResult result = new BacktestResult(
                strategy.getClass().getSimpleName(),
                parameters,
                summaryRows,
                hourlyStatistics.getRows()
        );

        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        System.out.printf(
                "Replay завершен: %,d событий за %.3f с%n",
                processedEvents,
                elapsedSeconds
        );
        return result;
    }

    // Выполняет один полный шаг replay. Отдельный метод позволяет проверять порядок на малых сценариях.
    void processEvent(Event event) {
        long eventTimeNanos = event.getTimestampNanos();
        lastEventTimeNanos = eventTimeNanos;
        metrics.beforeEvent(eventTimeNanos);
        hourlyStatistics.beforeEvent(eventTimeNanos);

        // Проверяем - если последние обновление стакана было слишком давно то "забываем" про открытые ордера
        // - это допущение-упрощение бекстеста - обход аномалии..
        checkGapBefore(eventTimeNanos);

        // Ордер может участвовать в fill только после фактического завершения своей latency.
        Order oldBid = orderManager.getActiveOrder(OrderSide.BUY);
        Order oldAsk = orderManager.getActiveOrder(OrderSide.SELL);
        List<Order> activatedOrders = orderManager.applyCommandsBefore(eventTimeNanos);
        recordCanceledOrder(oldBid, orderManager.getActiveOrder(OrderSide.BUY), eventTimeNanos);
        recordCanceledOrder(oldAsk, orderManager.getActiveOrder(OrderSide.SELL), eventTimeNanos);
        for (Order order : activatedOrders) {
            metrics.onOrderActivated(order, eventTimeNanos);
        }
        // Только созданным после latency ордерам - фиксируем очередь исполнения которая в стакане на момент активации
        // - эту очередь надо "разъесть" до того как будем счетать свой fill
        initializeQueues(activatedOrders);

        // Текущее событие один раз воздействует на ордера, уже активные до его начала.
        List<Fill> fills = processPossibleFills(event);
        applyFills(fills);

        // После проверки fills делаем текущее событие частью известного состояния рынка.
        updateMarketState(event);

        // получить минимальную и максимальную возможную позицию - ЕСЛИ исполнятся "висящие" ордера
        PositionRange positionRange = orderManager.calculatePositionRange(portfolio.getInventory());

        // Стратегия принимает решение по обновленному рынку и фактическому inventory после fills.
        desiredOrders = decideDesiredOrders(positionRange);

        // Сравнение идет с projected state, поэтому pending-команды не дублируются.
        ReconciliationResult reconciliation = orderManager.reconcile(desiredOrders, eventTimeNanos);
        metrics.onReconciliation(reconciliation);

        updateMetrics(eventTimeNanos);
    }

    // Один раз очищает ордера, когда последний известный стакан становится слишком старым.
    private void checkGapBefore(long eventTimeNanos) {
        if (currentOrderBook == null || staleBook) {
            return;
        }

        long bookTimeNanos = currentOrderBook.getTimestampNanos();
        boolean bookTooOld = eventTimeNanos > bookTimeNanos
                && eventTimeNanos - bookTimeNanos > parameters.getMaxBookAgeNanos();
        if (!bookTooOld) {
            return;
        }

        closeActiveOrders(eventTimeNanos);
        orderManager.resetOrdersForGap();
        desiredOrders = DesiredOrders.empty();
        lastTrade = null;
        staleBook = true;
        staleStartedAtNanos = Math.addExact(bookTimeNanos, parameters.getMaxBookAgeNanos());
        gapResetCount++;
        metrics.onGapReset();
        hourlyStatistics.onGapReset();
    }

    // Новому активному ордеру назначается очередь только по последнему уже известному snapshot.
    private void initializeQueues(List<Order> activatedOrders) {
        for (Order order : activatedOrders) {
            if (currentOrderBook == null || staleBook) {
                throw new IllegalStateException("Нельзя активировать ордер без свежего стакана");
            }
            if (isMarketable(order)) {
                // Если цена ушла в недопустимую для ордера сторону
                //  - отменяем ордер...
                orderManager.rejectActiveOrder(order);
                metrics.onOrderClosed(order, lastEventTimeNanos);
                forgetDesiredOrder(order.getSide());
                continue;
            }
            order.initializeQueue(findVisibleQuantityAtOrderPrice(order));
        }
    }

    // Проверяет, пересекает ли лимитный ордер противоположную сторону последнего известного стакана.
    // В baseline такие случаи отклоняются, потому что taker matching по snapshot отдельно не моделируется.
    private boolean isMarketable(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            double bestAsk = currentOrderBook.getAskPrice(0);
            return bestAsk > 0.0 && bestAsk <= order.getPrice();
        }

        double bestBid = currentOrderBook.getBidPrice(0);
        return bestBid > 0.0 && bestBid >= order.getPrice();
    }

    // После reject убирает старое желание по этой стороне, чтобы каркас стратегии не отправлял тот же
    // marketable ордер заново до следующего осознанного решения стратегии.
    private void forgetDesiredOrder(OrderSide side) {
        DesiredOrder bid = desiredOrders.getBid();
        DesiredOrder ask = desiredOrders.getAsk();
        if (side == OrderSide.BUY) {
            bid = null;
        } else {
            ask = null;
        }
        desiredOrders = new DesiredOrders(bid, ask);
    }

    // Считает только видимый объем на цене нашего ордера: лучшие уровни уже учтены
    // отдельной проверкой достижения цены и не должны второй раз попадать в очередь.
    private double findVisibleQuantityAtOrderPrice(Order order) {
        double visibleQuantity = 0.0;

        for (int levelIndex = 0; levelIndex < OrderBookEvent.LEVEL_COUNT; levelIndex++) {
            double levelPrice;
            double levelQuantity;
            if (order.getSide() == OrderSide.BUY) {
                levelPrice = currentOrderBook.getBidPrice(levelIndex);
                levelQuantity = currentOrderBook.getBidQuantity(levelIndex);
            } else {
                levelPrice = currentOrderBook.getAskPrice(levelIndex);
                levelQuantity = currentOrderBook.getAskQuantity(levelIndex);
            }

            if (levelPrice <= 0.0 || levelQuantity <= 0.0) {
                continue;
            }

            // При price-time priority перед нами стоят только уже видимые заявки
            // на той же цене. Trade на нашей цене или хуже подтверждает, что рынок
            // уже прошел все лучшие уровни.
            if (Double.compare(levelPrice, order.getPrice()) == 0) {
                visibleQuantity += levelQuantity;
            }
        }

        return visibleQuantity;
    }

    // Выбирает event-specific fill model. Funding не может исполнить торговый ордер.
    private List<Fill> processPossibleFills(Event event) {
        if (event.getType() == EventType.TRADE) {
            return processTradeFills((TradeEvent) event);
        }
        if (event.getType() == EventType.ORDER_BOOK) {
            return processOrderBookFills((OrderBookEvent) event);
        }
        return List.of();
    }

    // Подходящий trade сначала расходует очередь, а оставшийся объем исполняет лимитный ордер.
    private List<Fill> processTradeFills(TradeEvent trade) {
        OrderSide orderSide = trade.isMakerAsk() ? OrderSide.SELL : OrderSide.BUY;
        Order order = orderManager.getActiveOrder(orderSide);
        if (order == null) {
            return List.of();
        }

        boolean reachedOrderPrice;
        if (orderSide == OrderSide.BUY) {
            reachedOrderPrice = trade.getPrice() <= order.getPrice();
        } else {
            reachedOrderPrice = trade.getPrice() >= order.getPrice();
        }
        if (!reachedOrderPrice) {
            return List.of();
        }

        double remainingTradeSize = order.consumeQueue(trade.getSize());
        double fillSize = Math.min(order.getRemainingSize(), remainingTradeSize);
        if (fillSize <= 0.0) {
            return List.of();
        }

        return List.of(new Fill(
                trade.getTimestampNanos(),
                order.getId(),
                order.getSide(),
                order.getPrice(),
                fillSize
        ));
    }

    // Snapshot только обновляет известный L2: сам по себе он не подтверждает fill.
    private List<Fill> processOrderBookFills(OrderBookEvent orderBook) {
        return List.of();
    }

    // Сначала уменьшает остаток ордера, затем ровно один раз передает fill в portfolio.
    private void applyFills(List<Fill> fills) {
        for (Fill fill : fills) {
            Order order = orderManager.getActiveOrder(fill.getSide());
            boolean fullFill = Double.compare(fill.getSize(), order.getRemainingSize()) == 0;
            boolean partialFill = fill.getSize() < order.getRemainingSize();

            metrics.onFill(fill, fullFill, partialFill);
            hourlyStatistics.onFill(fill);
            orderManager.applyFill(fill);
            portfolio.applyFill(fill, parameters.getMakerFeeBps());
            if (fullFill) {
                metrics.onOrderClosed(order, fill.getTimestampNanos());
            }
        }
    }

    // Обновляет только наблюдаемое состояние рынка; это событие уже не будет повторно давать fills.
    private void updateMarketState(Event event) {
        if (event.getType() == EventType.TRADE) {
            updateTradeState((TradeEvent) event);
        } else if (event.getType() == EventType.ORDER_BOOK) {
            updateOrderBookState((OrderBookEvent) event);
        } else {
            updateFundingState((FundingRateEvent) event);
        }
    }

    // Сохраняет trade и ровно один раз передает его стратегии после обработки fills.
    private void updateTradeState(TradeEvent trade) {
        lastTrade = trade;
        strategy.onTrade(trade);
    }

    // Snapshot полностью заменяет предыдущее известное состояние L2 order book.
    private void updateOrderBookState(OrderBookEvent orderBook) {
        if (staleBook && staleStartedAtNanos != null) {
            metrics.onStalePeriod(staleStartedAtNanos, orderBook.getTimestampNanos());
            staleStartedAtNanos = null;
        }
        currentOrderBook = orderBook;
        staleBook = false;
    }

    // Funding observation обновляет фон, но сам по себе не считается денежным списанием.
    private void updateFundingState(FundingRateEvent fundingRate) {
        currentFundingRate = fundingRate.getFundingRate();
    }

    // Передает стратегии только известный рынок, фактическую позицию и уже учтенный order risk.
    private DesiredOrders decideDesiredOrders(PositionRange positionRange) {
        return strategy.decide(
                currentOrderBook,
                currentOrderBook != null && !staleBook,
                portfolio.getInventory(),
                portfolio.getAverageEntryPrice(),
                positionRange,
                orderManager.getProjectedOrder(OrderSide.BUY),
                orderManager.getProjectedOrder(OrderSide.SELL)
        );
    }

    // Обновляет точные event-based метрики, часовой timeline и проверяет accounting/risk invariants.
    private void updateMetrics(long eventTimeNanos) {
        Double midPrice = currentMidPrice();
        PositionRange finalRange = orderManager.calculatePositionRange(portfolio.getInventory());
        validateHardLimit(finalRange);

        if (midPrice == null) {
            return;
        }

        StatisticsSnapshot snapshot = new StatisticsSnapshot(portfolio, midPrice);
        validateFiniteSnapshot(snapshot);
        validateAccounting(snapshot);
        metrics.afterEvent(snapshot);
        hourlyStatistics.afterEvent(snapshot);
    }

    // Полностью очищает состояние, чтобы один runner можно было безопасно запустить повторно.
    private void resetState() {
        orderManager = new OrderManager(parameters.getOrderLatencyNanos());
        desiredOrders = DesiredOrders.empty();
        currentOrderBook = null;
        lastTrade = null;
        currentFundingRate = null;
        staleBook = false;
        gapResetCount = 0;
        portfolio = new Portfolio();
        metrics = new BacktestMetrics();
        hourlyStatistics = new HourlyStatisticsCollector();
        staleStartedAtNanos = null;
        lastEventTimeNanos = Long.MIN_VALUE;
    }

    private Double currentMidPrice() {
        if (currentOrderBook == null) {
            return null;
        }
        double bestBid = currentOrderBook.getBidPrice(0);
        double bestAsk = currentOrderBook.getAskPrice(0);
        if (!Double.isFinite(bestBid) || !Double.isFinite(bestAsk) || bestBid <= 0.0 || bestAsk <= bestBid) {
            return null;
        }
        return (bestBid + bestAsk) / 2.0;
    }

    // Отмечает cancel старого active order, если после применения команд его id больше не активен.
    private void recordCanceledOrder(Order oldOrder, Order currentOrder, long eventTimeNanos) {
        if (oldOrder != null && (currentOrder == null || currentOrder.getId() != oldOrder.getId())) {
            metrics.onOrderClosed(oldOrder, eventTimeNanos);
        }
    }

    private void closeActiveOrders(long eventTimeNanos) {
        Order bid = orderManager.getActiveOrder(OrderSide.BUY);
        Order ask = orderManager.getActiveOrder(OrderSide.SELL);
        if (bid != null) {
            metrics.onOrderClosed(bid, eventTimeNanos);
        }
        if (ask != null) {
            metrics.onOrderClosed(ask, eventTimeNanos);
        }
    }

    // В конце replay закрывает только статистическое время жизни, не отменяя ордера и не меняя portfolio.
    private void closeRemainingOrders(long eventTimeNanos) {
        closeActiveOrders(eventTimeNanos);
    }

    private void validateHardLimit(PositionRange range) {
        double limit = parameters.getHardInventoryLimit();
        double tolerance = 1e-9;
        if (portfolio.getInventory() < -limit - tolerance
                || portfolio.getInventory() > limit + tolerance
                || range.getMinPosition() < -limit - tolerance
                || range.getMaxPosition() > limit + tolerance) {
            throw new IllegalStateException("Нарушен hard inventory limit");
        }
    }

    private void validateFiniteSnapshot(StatisticsSnapshot snapshot) {
        double[] values = {
                snapshot.getMidPrice(),
                snapshot.getInventory(),
                snapshot.getRealizedPnl(),
                snapshot.getUnrealizedPnl(),
                snapshot.getGrossPnl(),
                snapshot.getFeesPaid(),
                snapshot.getFundingPnl(),
                snapshot.getNetPnl()
        };
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("В метриках появилось неконечное число");
            }
        }
    }

    private void validateAccounting(StatisticsSnapshot snapshot) {
        double cashEquity = portfolio.calculateNetEquity(snapshot.getMidPrice());
        double tolerance = 1e-8 * Math.max(1.0, Math.abs(snapshot.getNetPnl()));
        if (Math.abs(snapshot.getNetPnl() - cashEquity) > tolerance) {
            throw new IllegalStateException("PnL не сходится с контрольным cash ledger");
        }
    }

    private void validateDailyPnl(List<BacktestSummaryRow> rows) {
        double dailyPnlSum = 0.0;
        for (BacktestSummaryRow row : rows) {
            if (!"TOTAL".equals(row.getPeriod())) {
                dailyPnlSum += row.getDailyPnl();
            }
        }
        double totalPnl = rows.get(rows.size() - 1).getSnapshot().getNetPnl();
        if (Math.abs(dailyPnlSum - totalPnl) > 1e-8 * Math.max(1.0, Math.abs(totalPnl))) {
            throw new IllegalStateException("Сумма daily PnL не совпадает с total PnL");
        }
    }

    OrderManager getOrderManager() {
        return orderManager;
    }

    double getInventory() {
        return portfolio.getInventory();
    }

    double getCash() {
        return portfolio.getTradingCash();
    }

    Portfolio getPortfolio() {
        return portfolio;
    }

    int getGapResetCount() {
        return gapResetCount;
    }

    boolean isStaleBook() {
        return staleBook;
    }
}
