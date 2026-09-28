package ru.egor;

import java.util.List;

public final class BacktestRunner {
    private final BacktestParameters parameters;

    private OrderManager orderManager;
    private DesiredOrders desiredOrders;
    private OrderBookEvent currentOrderBook;
    private TradeEvent lastTrade;
    private Double currentFundingRate;
    private boolean staleBook;
    private int gapResetCount;

    private double inventory;
    private double cash;
    private double feesPaid;

    public BacktestRunner(BacktestParameters parameters) {
        this.parameters = parameters;
        resetState();
    }

    // Последовательно проигрывает market tape в экономически значимом порядке:
    // команды, fills, состояние рынка, решение стратегии, reconciliation и метрики.
    public void run(List<Event> eventTape) {
        resetState();
        long startedAt = System.nanoTime();
        long processedEvents = 0;

        for (Event event : eventTape) {
            processEvent(event);
            processedEvents++;
        }

        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        System.out.printf(
                "Replay завершен: %,d событий за %.3f с%n",
                processedEvents,
                elapsedSeconds
        );
    }

    // Выполняет один полный шаг replay. Отдельный метод позволяет проверять порядок на малых сценариях.
    void processEvent(Event event) {
        long eventTimeNanos = event.getTimestampNanos();

        // Проверяем - если последние обновление стакана было слишком давно то "забываем" про открытые ордера
        // - это допущение-упрощение бекстеста - обход аномалии..
        checkGapBefore(eventTimeNanos);

        // Ордер может участвовать в fill только после фактического завершения своей latency.
        List<Order> activatedOrders = orderManager.applyCommandsBefore(eventTimeNanos);
        // Только созданным после latency ордерам - фиксируем очередь исполнения которая в стакане на момент активации
        // - эту очередь надо "разъесть" до того как будем счетать свой fill
        initializeQueues(activatedOrders);

        // Текущее событие один раз воздействует на ордера, уже активные до его начала.
        List<Fill> fills = processPossibleFills(event);
        applyFills(fills);

        // После проверки fills делаем текущее событие частью известного состояния рынка.
        updateMarketState(event);

        PositionRange positionRange = orderManager.calculatePositionRange(inventory);

        // Стратегия принимает решение по обновленному рынку и фактическому inventory после fills.
        desiredOrders = decideDesiredOrders(positionRange);

        // Сравнение идет с projected state, поэтому pending-команды не дублируются.
        orderManager.reconcile(desiredOrders, eventTimeNanos);

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

        orderManager.resetOrdersForGap();
        desiredOrders = DesiredOrders.empty();
        lastTrade = null;
        staleBook = true;
        gapResetCount++;
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
                forgetDesiredOrder(order.getSide());
                continue;
            }
            order.initializeQueue(findVisibleQuantity(order));
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

    // Считает видимый объем с лучшим или равным price priority перед нашим ордером.
    // Новый пассивный уровень внутри spread по-прежнему получает нулевую очередь.
    private double findVisibleQuantity(Order order) {
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

            boolean hasPricePriority;
            if (order.getSide() == OrderSide.BUY) {
                hasPricePriority = levelPrice >= order.getPrice();
            } else {
                hasPricePriority = levelPrice <= order.getPrice();
            }
            if (hasPricePriority) {
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

    // Сначала уменьшает остаток ордера, затем отражает fill в cash, inventory и fees.
    private void applyFills(List<Fill> fills) {
        for (Fill fill : fills) {
            orderManager.applyFill(fill);
            applyFillToPortfolio(fill);
        }
    }

    // Ведет минимальный денежный ledger. Разложение realized/unrealized PnL будет добавлено отдельно.
    private void applyFillToPortfolio(Fill fill) {
        double notional = fill.getPrice() * fill.getSize();
        double fee = notional * parameters.getMakerFeeBps() / 10_000.0;

        if (fill.getSide() == OrderSide.BUY) {
            inventory += fill.getSize();
            cash -= notional;
        } else {
            inventory -= fill.getSize();
            cash += notional;
        }

        cash -= fee;
        feesPaid += fee;
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

    // Сохраняет trade для будущих сигналов trade flow.
    private void updateTradeState(TradeEvent trade) {
        lastTrade = trade;
    }

    // Snapshot полностью заменяет предыдущее известное состояние L2 order book.
    private void updateOrderBookState(OrderBookEvent orderBook) {
        currentOrderBook = orderBook;
        staleBook = false;
    }

    // Funding observation обновляет фон, но сам по себе не считается денежным списанием.
    private void updateFundingState(FundingRateEvent fundingRate) {
        currentFundingRate = fundingRate.getFundingRate();
    }

    // Определяет желаемые котировки по текущему рынку, inventory и возможному диапазону позиции.
    // Пока стратегия не зафиксирована, сохраняет прежнее желаемое состояние ордеров.
    private DesiredOrders decideDesiredOrders(PositionRange positionRange) {
        return desiredOrders;
    }

    // Точка для mark-to-market, risk metrics и накопительной статистики после полного шага replay.
    private void updateMetrics(long eventTimeNanos) {
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
        inventory = 0.0;
        cash = 0.0;
        feesPaid = 0.0;
    }

    OrderManager getOrderManager() {
        return orderManager;
    }

    void setDesiredOrders(DesiredOrders desiredOrders) {
        this.desiredOrders = desiredOrders;
    }

    double getInventory() {
        return inventory;
    }

    double getCash() {
        return cash;
    }

    int getGapResetCount() {
        return gapResetCount;
    }

    boolean isStaleBook() {
        return staleBook;
    }
}
