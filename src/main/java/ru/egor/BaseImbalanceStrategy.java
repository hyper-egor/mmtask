package ru.egor;

public final class BaseImbalanceStrategy implements MarketMakingStrategy {
    public static final long DEFAULT_IMBALANCE_WINDOW_NANOS = 60_000_000_000L;
    public static final long DEFAULT_AGGRESSION_WINDOW_NANOS = 20_000_000_000L;
    public static final double DEFAULT_AGGRESSION_RATIO = 1.5;
    public static final boolean USE_INSTANT_CONFIRMATION = false;
    public static final boolean USE_AGGRESSION_CONFIRMATION = false;
    public static final boolean USE_SIGNAL_HYSTERESIS = true; // вкл. сигнала по сильному кооф, и снятие - по слабому
    public static final double WINDOW_IMBALANCE_ENTRY_RATIO = 1.7;
    public static final double WINDOW_IMBALANCE_EXIT_RATIO = 1.4;
    public static final double INSTANT_IMBALANCE_RATIO = 1.7;
    public static final int DEFAULT_ROLLING_BOOK_LEVELS = 5;
    public static final int DEFAULT_INSTANT_BOOK_LEVELS = 20;
    public static final boolean USE_STICKY_ORDERS = true;
    public static final double TICK_SIZE = 0.1;
    public static final int STICKY_MAX_DISTANCE_TICKS = 2; // макс. растояние в цене без перестановок
    public static final long STICKY_MAX_OFF_BEST_NANOS = 1_000_000_000L; // делей - без перестановок если цена ушла
    public static final boolean USE_PROFIT_REDUCE_ORDERS_WITH_ACTIVE_SIGNAL = true;
    public static final boolean USE_PROFIT_REDUCE_ORDERS_WHEN_SIGNAL_BALANCED = true;
    public static final double PROFIT_REDUCE_ORDER_SIZE = .2;
    public static final int PROFIT_REDUCE_MIN_TICKS = 1;
    /*
        ниже: Переставим reduce если он ухудшился относительно best на REDUCE_MAX_DISTANCE_TICKS тиков
              или если REDUCE_MAX_STALE_NANOS best не приближался к ордеру
     */
    public static final int REDUCE_MAX_DISTANCE_TICKS = 2; // допустимое ухудшение от best
    public static final long REDUCE_MAX_STALE_NANOS = 15_000_000_000L; // время без приближения best
    private static final double PRICE_EPSILON = 1e-9;
    private static final double INVENTORY_EPSILON = 1e-9;

    private final SymmetricMarketMakingStrategy baselineStrategy;
    private final MMATema bidTop5Window;
    private final MMATema askTop5Window;
    private final MMATema aggressiveBuyWindow;
    private final MMATema aggressiveSellWindow;
    private final double aggressionRatio;
    private final int rollingBookLevels;
    private final int instantBookLevels;

    private OrderBookEvent lastProcessedOrderBook;
    private long lastProcessedBookTimeNanos;
    private long lastProcessedTradeTimeNanos;
    private ImbalanceSignal activeSignal;
    private final StickyQuoteState bidStickyState;
    private final StickyQuoteState askStickyState;
    private final ReduceQuoteState reduceBidState;
    private final ReduceQuoteState reduceAskState;

    public BaseImbalanceStrategy(double orderSize, double hardInventoryLimit) {
        this(
                orderSize,
                hardInventoryLimit,
                DEFAULT_IMBALANCE_WINDOW_NANOS,
                DEFAULT_AGGRESSION_RATIO,
                DEFAULT_ROLLING_BOOK_LEVELS,
                DEFAULT_INSTANT_BOOK_LEVELS
        );
    }

    public BaseImbalanceStrategy(
            double orderSize,
            double hardInventoryLimit,
            long imbalanceWindowNanos,
            double aggressionRatio,
            int rollingBookLevels,
            int instantBookLevels
    ) {
        if (!Double.isFinite(aggressionRatio) || aggressionRatio <= 1.0) {
            throw new IllegalArgumentException("Коэффициент aggression должен быть больше 1");
        }
        if (rollingBookLevels <= 0 || rollingBookLevels > OrderBookEvent.LEVEL_COUNT) {
            throw new IllegalArgumentException("Некорректное число уровней для плавающего окна");
        }
        if (instantBookLevels <= 0 || instantBookLevels > OrderBookEvent.LEVEL_COUNT) {
            throw new IllegalArgumentException("Некорректное число уровней моментального среза");
        }
        if (WINDOW_IMBALANCE_EXIT_RATIO <= 1.0
                || WINDOW_IMBALANCE_EXIT_RATIO >= WINDOW_IMBALANCE_ENTRY_RATIO) {
            throw new IllegalArgumentException("Порог выхода должен быть между 1 и порогом входа");
        }
        if (TICK_SIZE <= 0.0 || STICKY_MAX_DISTANCE_TICKS <= 0
                || STICKY_MAX_OFF_BEST_NANOS <= 0L) {
            throw new IllegalArgumentException("Параметры sticky order должны быть положительными");
        }
        if (REDUCE_MAX_DISTANCE_TICKS <= 0 || REDUCE_MAX_STALE_NANOS <= 0L) {
            throw new IllegalArgumentException("Параметры repricing reduce-ордера должны быть положительными");
        }
        /*if (!Double.isFinite(PROFIT_REDUCE_ORDER_SIZE) || PROFIT_REDUCE_ORDER_SIZE <= 0.0
                || PROFIT_REDUCE_MIN_TICKS <= 0) {
            throw new IllegalArgumentException("Параметры reducing-only ордера должны быть положительными");
        }*/

        this.baselineStrategy = new SymmetricMarketMakingStrategy(orderSize, hardInventoryLimit);
        this.bidTop5Window = new MMATema(imbalanceWindowNanos);
        this.askTop5Window = new MMATema(imbalanceWindowNanos);
        this.aggressiveBuyWindow = new MMATema(DEFAULT_AGGRESSION_WINDOW_NANOS);
        this.aggressiveSellWindow = new MMATema(DEFAULT_AGGRESSION_WINDOW_NANOS);
        this.aggressionRatio = aggressionRatio;
        this.rollingBookLevels = rollingBookLevels;
        this.instantBookLevels = instantBookLevels;
        this.lastProcessedOrderBook = null;
        this.lastProcessedBookTimeNanos = Long.MIN_VALUE;
        this.lastProcessedTradeTimeNanos = Long.MIN_VALUE;
        this.activeSignal = ImbalanceSignal.BALANCED;
        this.bidStickyState = new StickyQuoteState();
        this.askStickyState = new StickyQuoteState();
        this.reduceBidState = new ReduceQuoteState();
        this.reduceAskState = new ReduceQuoteState();
    }

    // Накапливает отдельно объем агрессивных покупок и продаж за 20 секунд.
    // isMakerAsk=true означает, что инициатор сделки покупал по ask.
    @Override
    public void onTrade(TradeEvent trade) {
        long tradeTimeNanos = trade.getTimestampNanos();

        // Повторный replay начинается с более раннего timestamp и должен получить чистое окно.
        if (tradeTimeNanos < lastProcessedTradeTimeNanos) {
            aggressiveBuyWindow.clear();
            aggressiveSellWindow.clear();
        }

        // Обновляем оба окна на каждом trade, чтобы сделка одной стороны также
        // удаляла слишком старые сделки противоположной стороны.
        double aggressiveBuyVolume = trade.isMakerAsk() ? trade.getSize() : 0.0;
        double aggressiveSellVolume = trade.isMakerAsk() ? 0.0 : trade.getSize();
        aggressiveBuyWindow.addStat(aggressiveBuyVolume, tradeTimeNanos);
        aggressiveSellWindow.addStat(aggressiveSellVolume, tradeTimeNanos);
        lastProcessedTradeTimeNanos = tradeTimeNanos;
    }

    // Использует S0 для цены и risk logic, затем оставляет только сторону сильного
    // сигнала. Если сильного сигнала нет, обе стороны отменяются.
    @Override
    public DesiredOrders decide(
            OrderBookEvent orderBook,
            boolean bookFresh,
            double inventory,
            double averageEntryPrice,
            PositionRange positionRange,
            Order projectedBid,
            Order projectedAsk
    ) {
        updateRollingWindow(orderBook);

        ImbalanceSignal windowSignal = classifyImbalance(
                bidTop5Window.getSum(),
                askTop5Window.getSum(),
                WINDOW_IMBALANCE_ENTRY_RATIO
        );
        ImbalanceSignal instantSignal = calculateInstantSignal(orderBook);
        ImbalanceSignal aggressionSignal = classifyImbalance(
                aggressiveBuyWindow.getSum(),
                aggressiveSellWindow.getSum(),
                aggressionRatio
        );
        ImbalanceSignal confirmedEntrySignal = confirmEntrySignal(
                windowSignal,
                instantSignal,
                aggressionSignal
        );
        ImbalanceSignal effectiveSignal = updateActiveSignal(confirmedEntrySignal);

        DesiredOrders replacementOrders = baselineStrategy.decide(
                orderBook,
                bookFresh,
                inventory,
                averageEntryPrice,
                positionRange,
                projectedBid,
                projectedAsk
        );
        DesiredOrders keepOrders = baselineStrategy.decide(
                orderBook,
                bookFresh,
                inventory,
                averageEntryPrice,
                positionRange,
                moveProjectedOrderToCurrentBest(projectedBid, orderBook),
                moveProjectedOrderToCurrentBest(projectedAsk, orderBook)
        );
        if (!bookFresh || !hasValidTopOfBook(orderBook)) {
            clearAllQuoteStates();
            return DesiredOrders.empty();
        }

        DesiredOrders signalOrders = filterAllowedSides(
                replacementOrders,
                keepOrders,
                projectedBid,
                projectedAsk,
                effectiveSignal,
                orderBook.getTimestampNanos()
        );
        return applyProfitReduceOrders(
                signalOrders,
                effectiveSignal,
                orderBook,
                inventory,
                averageEntryPrice,
                projectedBid,
                projectedAsk
        );
    }

    // Для проверки KEEP подставляет текущую best price без создания нового риска.
    // Реальный projected order и его очередь при этом не меняются.
    private Order moveProjectedOrderToCurrentBest(Order projectedOrder, OrderBookEvent orderBook) {
        if (projectedOrder == null || orderBook == null) {
            return projectedOrder;
        }
        double currentBestPrice = projectedOrder.getSide() == OrderSide.BUY
                ? orderBook.getBidPrice(0)
                : orderBook.getAskPrice(0);
        if (!Double.isFinite(currentBestPrice) || currentBestPrice <= 0.0) {
            return projectedOrder;
        }
        return new Order(
                projectedOrder.getId(),
                projectedOrder.getSide(),
                currentBestPrice,
                projectedOrder.getOriginalSize(),
                projectedOrder.isReduceOnly()
        );
    }

    // Проверяет top of book отдельно, потому что reducing-only ордер может быть
    // разрешен даже тогда, когда обычную сторону заблокировал inventory limit.
    private boolean hasValidTopOfBook(OrderBookEvent orderBook) {
        if (orderBook == null) {
            return false;
        }
        double bestBid = orderBook.getBidPrice(0);
        double bestAsk = orderBook.getAskPrice(0);
        return Double.isFinite(bestBid)
                && Double.isFinite(bestAsk)
                && bestBid > 0.0
                && bestAsk > bestBid;
    }

    // Один snapshot добавляется в окно ровно один раз, даже если стратегия вызывается
    // на последующих trades и funding с тем же последним известным стаканом.
    private void updateRollingWindow(OrderBookEvent orderBook) {
        if (orderBook == null || orderBook == lastProcessedOrderBook) {
            return;
        }

        long bookTimeNanos = orderBook.getTimestampNanos();

        // Повторный replay начинается с более раннего timestamp и должен получить чистое окно.
        if (bookTimeNanos < lastProcessedBookTimeNanos) {
            bidTop5Window.clear();
            askTop5Window.clear();
            aggressiveBuyWindow.clear();
            aggressiveSellWindow.clear();
            lastProcessedTradeTimeNanos = Long.MIN_VALUE;
            activeSignal = ImbalanceSignal.BALANCED;
            clearAllQuoteStates();
        }

        double bidVolume = sumBidQuantities(orderBook, rollingBookLevels);
        double askVolume = sumAskQuantities(orderBook, rollingBookLevels);
        bidTop5Window.addStat(bidVolume, bookTimeNanos);
        askTop5Window.addStat(askVolume, bookTimeNanos);

        lastProcessedOrderBook = orderBook;
        lastProcessedBookTimeNanos = bookTimeNanos;
    }

    // Считает моментальный сигнал по TOP20 только из последнего известного snapshot.
    private ImbalanceSignal calculateInstantSignal(OrderBookEvent orderBook) {
        if (orderBook == null) {
            return ImbalanceSignal.BALANCED;
        }

        double bidVolume = sumBidQuantities(orderBook, instantBookLevels);
        double askVolume = sumAskQuantities(orderBook, instantBookLevels);
        return classifyImbalance(bidVolume, askVolume, INSTANT_IMBALANCE_RATIO);
    }

    // Перевес меньше или ровно переданного коэффициента считается нейтральным.
    private ImbalanceSignal classifyImbalance(double bidVolume, double askVolume, double ratio) {
        if (bidVolume > askVolume * ratio) {
            return ImbalanceSignal.BID_DOMINANT;
        }
        if (askVolume > bidVolume * ratio) {
            return ImbalanceSignal.ASK_DOMINANT;
        }
        return ImbalanceSignal.BALANCED;
    }

    // Применяет включенные подтверждения только к включению нового сильного направления.
    private ImbalanceSignal confirmEntrySignal(
            ImbalanceSignal windowSignal,
            ImbalanceSignal instantSignal,
            ImbalanceSignal aggressionSignal
    ) {
        if (windowSignal == ImbalanceSignal.BALANCED) {
            return ImbalanceSignal.BALANCED;
        }
        if (USE_INSTANT_CONFIRMATION && instantSignal != windowSignal) {
            return ImbalanceSignal.BALANCED;
        }
        if (USE_AGGRESSION_CONFIRMATION && aggressionSignal != windowSignal) {
            return ImbalanceSignal.BALANCED;
        }
        return windowSignal;
    }

    // Сильный порог включает направление, а мягкий удерживает уже включенное.
    // Сильный противоположный сигнал имеет приоритет и сразу меняет сторону.
    private ImbalanceSignal updateActiveSignal(ImbalanceSignal confirmedEntrySignal) {
        if (!USE_SIGNAL_HYSTERESIS) {
            activeSignal = confirmedEntrySignal;
            return activeSignal;
        }
        if (confirmedEntrySignal != ImbalanceSignal.BALANCED
                && confirmedEntrySignal != activeSignal) {
            activeSignal = confirmedEntrySignal;
            return activeSignal;
        }
        if (activeSignal == ImbalanceSignal.BALANCED) {
            activeSignal = confirmedEntrySignal;
            return activeSignal;
        }

        double bidVolume = bidTop5Window.getSum();
        double askVolume = askTop5Window.getSum();
        if (activeSignal == ImbalanceSignal.BID_DOMINANT
                && bidVolume > askVolume * WINDOW_IMBALANCE_EXIT_RATIO) {
            return activeSignal;
        }
        if (activeSignal == ImbalanceSignal.ASK_DOMINANT
                && askVolume > bidVolume * WINDOW_IMBALANCE_EXIT_RATIO) {
            return activeSignal;
        }

        activeSignal = ImbalanceSignal.BALANCED;
        return activeSignal;
    }

    // Активное направление разрешает одну сторону, а sticky order решает,
    // нужно ли сохранять ее цену и очередь или уже пора сделать replacement.
    private DesiredOrders filterAllowedSides(
            DesiredOrders replacementOrders,
            DesiredOrders keepOrders,
            Order projectedBid,
            Order projectedAsk,
            ImbalanceSignal effectiveSignal,
            long bookTimeNanos
    ) {
        if (effectiveSignal == ImbalanceSignal.BID_DOMINANT) {
            askStickyState.clear();
            return new DesiredOrders(
                    stickyQuoteOrCancel(
                            replacementOrders.getBid(),
                            keepOrders.getBid(),
                            projectedBid,
                            bookTimeNanos,
                            bidStickyState
                    ),
                    OrderInstruction.cancel()
            );
        }

        if (effectiveSignal == ImbalanceSignal.ASK_DOMINANT) {
            bidStickyState.clear();
            return new DesiredOrders(
                    OrderInstruction.cancel(),
                    stickyQuoteOrCancel(
                            replacementOrders.getAsk(),
                            keepOrders.getAsk(),
                            projectedAsk,
                            bookTimeNanos,
                            askStickyState
                    )
            );
        }

        clearStickyStates();
        return DesiredOrders.empty();
    }

    // Накладывает reducing-only управление поверх направленного сигнала. На стороне,
    // которая сокращает позицию, обычный ордер заменяется безопасным profit-order.
    private DesiredOrders applyProfitReduceOrders(
            DesiredOrders signalOrders,
            ImbalanceSignal effectiveSignal,
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice,
            Order projectedBid,
            Order projectedAsk
    ) {
        boolean signalBalanced = effectiveSignal == ImbalanceSignal.BALANCED;
        boolean profitReduceEnabled = shouldUseProfitReduceOrders(
                signalBalanced,
                USE_PROFIT_REDUCE_ORDERS_WITH_ACTIVE_SIGNAL,
                USE_PROFIT_REDUCE_ORDERS_WHEN_SIGNAL_BALANCED
        );
        if (!profitReduceEnabled || Math.abs(inventory) <= INVENTORY_EPSILON) {
            clearReduceStates();
            return signalOrders;
        }

        validateAverageEntryPrice(averageEntryPrice);
        if (inventory > 0.0) {
            askStickyState.clear();
            reduceBidState.clear();
            return new DesiredOrders(
                    signalOrders.getInstruction(OrderSide.BUY),
                    profitableReduceAsk(orderBook, inventory, averageEntryPrice, projectedAsk)
            );
        }

        bidStickyState.clear();
        reduceAskState.clear();
        return new DesiredOrders(
                profitableReduceBid(orderBook, inventory, averageEntryPrice, projectedBid),
                signalOrders.getInstruction(OrderSide.SELL)
        );
    }

    // Выбирает независимый флаг reducing-only механики для текущего состояния сигнала.
    // Ни один из двух режимов не является общим master-switch для другого.
    static boolean shouldUseProfitReduceOrders(
            boolean signalBalanced,
            boolean useWithActiveSignal,
            boolean useWhenSignalBalanced
    ) {
        if (signalBalanced) {
            return useWhenSignalBalanced;
        }
        return useWithActiveSignal;
    }

    // Для long сохраняет уже стоящий прибыльный reduce-only ask либо создает новый
    // квант по текущему best ask. Размер никогда не превышает текущую позицию.
    private OrderInstruction profitableReduceAsk(
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice,
            Order projectedAsk
    ) {
        double minimumProfit = PROFIT_REDUCE_MIN_TICKS * TICK_SIZE;
        double reduceSize = Math.min(PROFIT_REDUCE_ORDER_SIZE, inventory);
        double projectedProfit = projectedAsk == null
                ? 0.0
                : projectedAsk.getPrice() - averageEntryPrice;
        if (canKeepReduceOrder(
                projectedAsk,
                OrderSide.SELL,
                reduceSize,
                projectedProfit,
                minimumProfit
        )) {
            boolean keepOrder = shouldKeepReduceOrder(
                    projectedAsk,
                    orderBook.getAskPrice(0),
                    orderBook.getTimestampNanos(),
                    reduceAskState
            );
            if (keepOrder || orderBook.getAskPrice(0) - averageEntryPrice
                    + PRICE_EPSILON < minimumProfit) {
                return OrderInstruction.keep();
            }
            reduceAskState.clear();
            return OrderInstruction.quote(new DesiredOrder(
                    OrderSide.SELL,
                    orderBook.getAskPrice(0),
                    reduceSize,
                    true
            ));
        }

        reduceAskState.clear();
        double bestAsk = orderBook.getAskPrice(0);
        if (bestAsk - averageEntryPrice + PRICE_EPSILON < minimumProfit) {
            return OrderInstruction.cancel();
        }
        return OrderInstruction.quote(new DesiredOrder(
                OrderSide.SELL,
                bestAsk,
                reduceSize,
                true
        ));
    }

    // Для short зеркально сохраняет прибыльный reduce-only bid или создает новый
    // квант по текущему best bid.
    private OrderInstruction profitableReduceBid(
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice,
            Order projectedBid
    ) {
        double minimumProfit = PROFIT_REDUCE_MIN_TICKS * TICK_SIZE;
        double reduceSize = Math.min(PROFIT_REDUCE_ORDER_SIZE, Math.abs(inventory));
        double projectedProfit = projectedBid == null
                ? 0.0
                : averageEntryPrice - projectedBid.getPrice();
        if (canKeepReduceOrder(
                projectedBid,
                OrderSide.BUY,
                reduceSize,
                projectedProfit,
                minimumProfit
        )) {
            boolean keepOrder = shouldKeepReduceOrder(
                    projectedBid,
                    orderBook.getBidPrice(0),
                    orderBook.getTimestampNanos(),
                    reduceBidState
            );
            if (keepOrder || averageEntryPrice - orderBook.getBidPrice(0)
                    + PRICE_EPSILON < minimumProfit) {
                return OrderInstruction.keep();
            }
            reduceBidState.clear();
            return OrderInstruction.quote(new DesiredOrder(
                    OrderSide.BUY,
                    orderBook.getBidPrice(0),
                    reduceSize,
                    true
            ));
        }

        reduceBidState.clear();
        double bestBid = orderBook.getBidPrice(0);
        if (averageEntryPrice - bestBid + PRICE_EPSILON < minimumProfit) {
            return OrderInstruction.cancel();
        }
        return OrderInstruction.quote(new DesiredOrder(
                OrderSide.BUY,
                bestBid,
                reduceSize,
                true
        ));
    }

    // KEEP не пополняет partial fill и допускается только для действительно
    // reduce-only ордера, который все еще прибыльный и не больше нужного кванта.
    private boolean canKeepReduceOrder(
            Order projectedOrder,
            OrderSide expectedSide,
            double maximumSize,
            double currentProfit,
            double minimumProfit
    ) {
        return projectedOrder != null
                && projectedOrder.isReduceOnly()
                && projectedOrder.getSide() == expectedSide
                && projectedOrder.getRemainingSize() <= maximumSize + INVENTORY_EPSILON
                && currentProfit + PRICE_EPSILON >= minimumProfit;
    }

    // Сохраняет очередь reduce-ордера, пока он не стал заметно хуже best или долго
    // не приближался к рынку. Более конкурентная цена никогда не вызывает repricing.
    private boolean shouldKeepReduceOrder(
            Order projectedOrder,
            double currentBestPrice,
            long bookTimeNanos,
            ReduceQuoteState state
    ) {
        state.track(projectedOrder.getId());

        double distanceTicks;
        if (projectedOrder.getSide() == OrderSide.SELL) {
            distanceTicks = (projectedOrder.getPrice() - currentBestPrice) / TICK_SIZE;
        } else {
            distanceTicks = (currentBestPrice - projectedOrder.getPrice()) / TICK_SIZE;
        }
        distanceTicks = Math.max(0.0, distanceTicks);

        if (distanceTicks <= PRICE_EPSILON) {
            state.clearStaleness();
            return true;
        }

        boolean movedTowardOrder = Double.isFinite(state.lastDistanceTicks)
                && distanceTicks + PRICE_EPSILON < state.lastDistanceTicks;
        if (state.staleSinceNanos == null || movedTowardOrder) {
            state.staleSinceNanos = bookTimeNanos;
        }
        state.lastDistanceTicks = distanceTicks;

        // Улучшение best в сторону нашего ордера сохраняет очередь даже при большой
        // текущей дистанции; timeout начинается заново с этого улучшения.
        if (movedTowardOrder) {
            return true;
        }

        boolean tooFar = distanceTicks + PRICE_EPSILON >= REDUCE_MAX_DISTANCE_TICKS;
        boolean tooStale = bookTimeNanos - state.staleSinceNanos >= REDUCE_MAX_STALE_NANOS;
        return !tooFar && !tooStale;
    }

    private void validateAverageEntryPrice(double averageEntryPrice) {
        if (!Double.isFinite(averageEntryPrice) || averageEntryPrice <= 0.0) {
            throw new IllegalArgumentException(
                    "Для reducing-only ордера нужна положительная average entry price"
            );
        }
    }

    // Сохраняет существующий ордер при небольшом или недолгом отклонении от best.
    // Направление движения цены не важно: приближение к ордеру не вызывает отмену.
    private OrderInstruction stickyQuoteOrCancel(
            DesiredOrder replacementOrder,
            DesiredOrder keepOrder,
            Order projectedOrder,
            long bookTimeNanos,
            StickyQuoteState stickyState
    ) {
        if (keepOrder == null) {
            stickyState.clear();
            return OrderInstruction.cancel();
        }
        if (!USE_STICKY_ORDERS || projectedOrder == null) {
            stickyState.clear();
            return quoteOrCancel(replacementOrder);
        }

        stickyState.track(projectedOrder.getId());
        if (keepOrder.matches(projectedOrder)) {
            stickyState.offBestSinceNanos = null;
            return OrderInstruction.keep();
        }

        if (stickyState.offBestSinceNanos == null) {
            stickyState.offBestSinceNanos = bookTimeNanos;
        }

        double priceDistanceTicks = Math.abs(keepOrder.getPrice() - projectedOrder.getPrice())
                / TICK_SIZE;
        boolean tooFar = priceDistanceTicks + PRICE_EPSILON >= STICKY_MAX_DISTANCE_TICKS;
        boolean tooLong = bookTimeNanos - stickyState.offBestSinceNanos
                >= STICKY_MAX_OFF_BEST_NANOS;
        if (tooFar || tooLong) {
            stickyState.clear();
            return quoteOrCancel(replacementOrder);
        }
        return OrderInstruction.keep();
    }

    // Replacement может быть временно запрещен из-за overlap старого и нового риска.
    private OrderInstruction quoteOrCancel(DesiredOrder desiredOrder) {
        if (desiredOrder == null) {
            return OrderInstruction.cancel();
        }
        return OrderInstruction.quote(desiredOrder);
    }

    private void clearStickyStates() {
        bidStickyState.clear();
        askStickyState.clear();
    }

    private void clearAllQuoteStates() {
        clearStickyStates();
        clearReduceStates();
    }

    private void clearReduceStates() {
        reduceBidState.clear();
        reduceAskState.clear();
    }

    // Складывает объем ближайших bid-уровней обычным циклом для прозрачности расчета.
    private double sumBidQuantities(OrderBookEvent orderBook, int levelCount) {
        double total = 0.0;
        for (int level = 0; level < levelCount; level++) {
            total += orderBook.getBidQuantity(level);
        }
        return total;
    }

    // Складывает объем ближайших ask-уровней обычным циклом для прозрачности расчета.
    private double sumAskQuantities(OrderBookEvent orderBook, int levelCount) {
        double total = 0.0;
        for (int level = 0; level < levelCount; level++) {
            total += orderBook.getAskQuantity(level);
        }
        return total;
    }

    private enum ImbalanceSignal {
        BID_DOMINANT,
        ASK_DOMINANT,
        BALANCED
    }

    private static final class StickyQuoteState {
        private long projectedOrderId = Long.MIN_VALUE;
        private Long offBestSinceNanos;

        // Новый order id начинает собственный интервал отклонения от best.
        private void track(long orderId) {
            if (projectedOrderId == orderId) {
                return;
            }
            projectedOrderId = orderId;
            offBestSinceNanos = null;
        }

        private void clear() {
            projectedOrderId = Long.MIN_VALUE;
            offBestSinceNanos = null;
        }
    }

    private static final class ReduceQuoteState {
        private long projectedOrderId = Long.MIN_VALUE;
        private Long staleSinceNanos;
        private double lastDistanceTicks = Double.NaN;

        // Новый order id начинает собственное наблюдение за отклонением от best.
        private void track(long orderId) {
            if (projectedOrderId == orderId) {
                return;
            }
            projectedOrderId = orderId;
            clearStaleness();
        }

        private void clearStaleness() {
            staleSinceNanos = null;
            lastDistanceTicks = Double.NaN;
        }

        private void clear() {
            projectedOrderId = Long.MIN_VALUE;
            clearStaleness();
        }
    }
}
