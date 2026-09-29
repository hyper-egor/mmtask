package ru.egor;

public final class PriceMagnetStrategy implements MarketMakingStrategy {
    // Главный переключатель эксперимента: false оставляет только механику инерции.
    private static final boolean USE_MAGNET = true;

    private static final double TICK_SIZE = 0.1;
    private static final long INERTIA_WINDOW_NANOS = 3_000_000_000L;
    private static final double MINIMUM_MOVE_TICKS = 2.0;
    private static final double MINIMUM_WINDOW_COVERAGE_SHARE = 0.75; // если окно не полностью прогрето - когда сигналить
    private static final int FIXED_EXIT_TICKS = 3;
    private static final long ENTRY_TIMEOUT_NANOS = 3_000_000_000L;
    private static final long MAXIMUM_HOLDING_NANOS = 60_000_000_000L;
    private static final int STOP_LOSS_TICKS = 6;
    private static final long RISK_EXIT_REFRESH_NANOS = 250_000_000L;

    private static final double WALL_RATIO = 3.0;
    private static final double MINIMUM_WALL_VOLUME = 3.0;
    private static final long MINIMUM_WALL_AGE_NANOS = 3_000_000_000L;
    private static final double MINIMUM_PRESENCE_SHARE = 0.80;
    private static final double MINIMUM_RETAINED_VOLUME_SHARE = 0.50;
    private static final long WALL_MISSING_GRACE_NANOS = 500_000_000L;
    private static final int MINIMUM_WALL_DISTANCE_TICKS = 1;
    private static final int MAXIMUM_WALL_DISTANCE_TICKS = 7;
    private static final double MAXIMUM_THIN_VOLUME_RATIO = 0.50;
    private static final double POSITION_EPSILON = 1e-9;

    private final double orderSize;
    private final double hardInventoryLimit;
    private final PriceInertiaWindow inertiaWindow;
    private final PriceWallDetector wallDetector;
    private final CancelBarrier cancelBarrier;

    private StrategyState state;
    private TradePlan tradePlan;
    private PriceInertiaWindow.Signal lastStartedDirection;
    private boolean entryArmed;
    private long firstFillTimeNanos;
    private Double riskExitOrderPrice;
    private long lastRiskExitQuoteTimeNanos;
    private OrderBookEvent lastProcessedOrderBook;
    private long lastProcessedBookTimeNanos;
    private long lastTradeTimeNanos;
    private long lastObservedTimeNanos;

    public PriceMagnetStrategy(
            double orderSize,
            double hardInventoryLimit,
            long orderLatencyNanos
    ) {
        if (!Double.isFinite(orderSize) || orderSize <= 0.0) {
            throw new IllegalArgumentException("Размер ордера должен быть конечным и положительным");
        }
        if (!Double.isFinite(hardInventoryLimit) || hardInventoryLimit < orderSize) {
            throw new IllegalArgumentException("Hard limit должен быть не меньше размера ордера");
        }
        if (orderLatencyNanos < 0L) {
            throw new IllegalArgumentException("Latency не может быть отрицательной");
        }

        this.orderSize = orderSize;
        this.hardInventoryLimit = hardInventoryLimit;
        this.inertiaWindow = new PriceInertiaWindow(
                INERTIA_WINDOW_NANOS,
                TICK_SIZE,
                MINIMUM_MOVE_TICKS,
                MINIMUM_WINDOW_COVERAGE_SHARE
        );
        this.wallDetector = new PriceWallDetector(
                TICK_SIZE,
                WALL_RATIO,
                MINIMUM_WALL_VOLUME,
                MINIMUM_WALL_AGE_NANOS,
                MINIMUM_PRESENCE_SHARE,
                MINIMUM_RETAINED_VOLUME_SHARE,
                WALL_MISSING_GRACE_NANOS,
                MINIMUM_WALL_DISTANCE_TICKS,
                MAXIMUM_WALL_DISTANCE_TICKS,
                MAXIMUM_THIN_VOLUME_RATIO
        );
        this.cancelBarrier = new CancelBarrier(orderLatencyNanos);
        resetState();
    }

    // Запоминает время последнего trade. Оно нужно, чтобы увидеть fill раньше следующего snapshot.
    @Override
    public void onTrade(TradeEvent trade) {
        long tradeTimeNanos = trade.getTimestampNanos();
        if (tradeTimeNanos < lastObservedTimeNanos) {
            resetState();
        }
        lastTradeTimeNanos = tradeTimeNanos;
        lastObservedTimeNanos = Math.max(lastObservedTimeNanos, tradeTimeNanos);
    }

    // Обновляет индикаторы и выполняет один переход простого автомата торгового цикла.
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
        if (orderBook != null
                && lastProcessedBookTimeNanos != Long.MIN_VALUE
                && orderBook.getTimestampNanos() < lastProcessedBookTimeNanos) {
            resetState();
        }

        long decisionTimeNanos = decisionTime(orderBook);
        lastObservedTimeNanos = Math.max(lastObservedTimeNanos, decisionTimeNanos);

        if (!bookFresh || !hasValidTopOfBook(orderBook)) {
            handleStaleBook(inventory, decisionTimeNanos);
            return DesiredOrders.empty();
        }

        updateIndicators(orderBook);
        PriceInertiaWindow.Signal signal = inertiaWindow.getSignal();
        updateEntryArming(signal);

        if (!hasPosition(inventory)
                && (state == StrategyState.POSITION_OPEN || state == StrategyState.RISK_EXIT)) {
            clearTradeCycle();
            return DesiredOrders.empty();
        }

        if (hasPosition(inventory) && state == StrategyState.ENTRY_PENDING) {
            state = StrategyState.POSITION_OPEN;
            firstFillTimeNanos = decisionTimeNanos;
        } else if (hasPosition(inventory) && state == StrategyState.FLAT) {
            firstFillTimeNanos = decisionTimeNanos;
            startRiskExit(
                    decisionTimeNanos,
                    projectedBid != null || projectedAsk != null
            );
        }

        if (state == StrategyState.FLAT) {
            return decideFlat(
                    orderBook,
                    signal,
                    positionRange,
                    projectedBid,
                    projectedAsk,
                    decisionTimeNanos
            );
        }
        if (state == StrategyState.ENTRY_PENDING) {
            return decideEntryPending(
                    signal,
                    positionRange,
                    projectedBid,
                    projectedAsk,
                    decisionTimeNanos
            );
        }
        if (state == StrategyState.POSITION_OPEN) {
            return decidePositionOpen(
                    orderBook,
                    signal,
                    inventory,
                    averageEntryPrice,
                    projectedBid,
                    projectedAsk,
                    decisionTimeNanos
            );
        }
        return decideRiskExit(
                orderBook,
                inventory,
                projectedBid,
                projectedAsk,
                decisionTimeNanos
        );
    }

    // При отсутствии позиции создает не больше одного плана на непрерывный сигнал.
    private DesiredOrders decideFlat(
            OrderBookEvent orderBook,
            PriceInertiaWindow.Signal signal,
            PositionRange positionRange,
            Order projectedBid,
            Order projectedAsk,
            long decisionTimeNanos
    ) {
        if (projectedBid != null || projectedAsk != null) {
            return DesiredOrders.empty();
        }
        if (!entryArmed || signal == PriceInertiaWindow.Signal.FLAT) {
            return DesiredOrders.empty();
        }

        PriceWallDetector.Wall wall = USE_MAGNET ? wallDetector.findNearest(signal) : null;
        if (USE_MAGNET && wall == null) {
            return DesiredOrders.empty();
        }

        OrderSide entrySide = entrySide(signal);
        double entryPrice = entrySide == OrderSide.BUY
                ? orderBook.getBidPrice(0)
                : orderBook.getAskPrice(0);
        Double wallPrice = wall == null ? null : wall.getPrice();
        Double targetPrice = wall == null ? null : wallTarget(signal, wallPrice);
        DesiredOrder entryOrder = new DesiredOrder(entrySide, entryPrice, orderSize);
        if (!canPlaceEntry(entryOrder, positionRange, null)) {
            return DesiredOrders.empty();
        }

        tradePlan = new TradePlan(
                signal,
                decisionTimeNanos,
                entryPrice,
                wallPrice,
                targetPrice
        );
        state = StrategyState.ENTRY_PENDING;
        lastStartedDirection = signal;
        entryArmed = false;
        return quoteOneSide(entrySide, entryOrder);
    }

    // Сохраняет фиксированную цену входа либо начинает безопасную отмену плана.
    private DesiredOrders decideEntryPending(
            PriceInertiaWindow.Signal signal,
            PositionRange positionRange,
            Order projectedBid,
            Order projectedAsk,
            long decisionTimeNanos
    ) {
        if (cancelBarrier.isActive()) {
            if (cancelBarrier.isFinished(decisionTimeNanos)) {
                clearTradeCycle();
            }
            return DesiredOrders.empty();
        }

        // FLAT означает только паузу инерции: сохраняем пассивный вход, чтобы дать
        // цене вернуться к его фиксированному уровню. Отменяемся лишь при реверсе.
        boolean signalReversed = isOppositeSignal(signal, tradePlan.direction);
        boolean timedOut = decisionTimeNanos - tradePlan.signalTimeNanos >= ENTRY_TIMEOUT_NANOS;

        // Стена нужна только для создания плана. После этого не перепроверяем ее:
        // зафиксированный target остается значимым даже после исчезновения объема.
        if (signalReversed || timedOut) {
            if (projectedBid != null || projectedAsk != null) {
                beginCancelWait(decisionTimeNanos);
            } else {
                clearTradeCycle();
            }
            return DesiredOrders.empty();
        }

        OrderSide side = entrySide(tradePlan.direction);
        Order projectedEntry = side == OrderSide.BUY ? projectedBid : projectedAsk;
        Order projectedOther = side == OrderSide.BUY ? projectedAsk : projectedBid;
        if (projectedOther != null) {
            beginCancelWait(decisionTimeNanos);
            return DesiredOrders.empty();
        }

        DesiredOrder desiredEntry = new DesiredOrder(side, tradePlan.entryOrderPrice, orderSize);
        if (projectedEntry != null && desiredEntry.matches(projectedEntry)) {
            return keepOneSide(side);
        }
        if (!canPlaceEntry(desiredEntry, positionRange, projectedEntry)) {
            if (projectedEntry != null) {
                beginCancelWait(decisionTimeNanos);
            } else {
                clearTradeCycle();
            }
            return DesiredOrders.empty();
        }
        return quoteOneSide(side, desiredEntry);
    }

    // После fill сначала дожидается отмены остатка входа, затем ставит фиксированный target.
    private DesiredOrders decidePositionOpen(
            OrderBookEvent orderBook,
            PriceInertiaWindow.Signal signal,
            double inventory,
            double averageEntryPrice,
            Order projectedBid,
            Order projectedAsk,
            long decisionTimeNanos
    ) {
        if (!positionMatchesPlan(inventory)) {
            startRiskExit(
                    decisionTimeNanos,
                    projectedBid != null || projectedAsk != null
            );
            return DesiredOrders.empty();
        }

        boolean reverseSignal = isOppositeSignal(signal, tradePlan.direction);
        boolean stopLoss = reachedStopLoss(orderBook, inventory, averageEntryPrice);
        boolean holdingExpired = decisionTimeNanos - firstFillTimeNanos >= MAXIMUM_HOLDING_NANOS;
        if (reverseSignal || stopLoss || holdingExpired) {
            startRiskExit(
                    decisionTimeNanos,
                    projectedBid != null || projectedAsk != null
            );
            return DesiredOrders.empty();
        }

        OrderSide entrySide = entrySide(tradePlan.direction);
        Order projectedEntry = entrySide == OrderSide.BUY ? projectedBid : projectedAsk;
        if (projectedEntry != null && !cancelBarrier.isActive()) {
            beginCancelWait(decisionTimeNanos);
            return DesiredOrders.empty();
        }
        if (cancelBarrier.isActive()) {
            if (!cancelBarrier.isFinished(decisionTimeNanos)) {
                return DesiredOrders.empty();
            }
        }

        validateAverageEntryPrice(averageEntryPrice);
        if (tradePlan.targetPrice == null) {
            tradePlan.targetPrice = fixedTarget(inventory, averageEntryPrice);
        }

        OrderSide exitSide = inventory > 0.0 ? OrderSide.SELL : OrderSide.BUY;
        Order projectedExit = exitSide == OrderSide.BUY ? projectedBid : projectedAsk;
        double exitSize = Math.abs(inventory);
        if (projectedExit == null) {
            // Если target был отклонен как marketable после latency, заново берем
            // текущую пассивную цену, но не ухудшаем зафиксированный target.
            tradePlan.exitOrderPrice = passiveTargetPrice(
                    orderBook,
                    inventory,
                    tradePlan.targetPrice
            );
            DesiredOrder exitOrder = new DesiredOrder(
                    exitSide,
                    tradePlan.exitOrderPrice,
                    exitSize
            );
            return quoteOneSide(exitSide, exitOrder);
        }

        boolean correctPrice = Double.compare(
                projectedExit.getPrice(),
                tradePlan.exitOrderPrice
        ) == 0;
        boolean cannotReverse = projectedExit.getRemainingSize() <= exitSize + POSITION_EPSILON;
        if (correctPrice && cannotReverse) {
            return keepOneSide(exitSide);
        }

        beginCancelWait(decisionTimeNanos);
        tradePlan.exitOrderPrice = null;
        return DesiredOrders.empty();
    }

    // В risk-off следует за touch с ограниченным refresh и всегда только уменьшает позицию.
    private DesiredOrders decideRiskExit(
            OrderBookEvent orderBook,
            double inventory,
            Order projectedBid,
            Order projectedAsk,
            long decisionTimeNanos
    ) {
        if (cancelBarrier.isActive() && !cancelBarrier.isFinished(decisionTimeNanos)) {
            return DesiredOrders.empty();
        }

        OrderSide exitSide = inventory > 0.0 ? OrderSide.SELL : OrderSide.BUY;
        Order projectedExit = exitSide == OrderSide.BUY ? projectedBid : projectedAsk;
        Order projectedOther = exitSide == OrderSide.BUY ? projectedAsk : projectedBid;
        if (projectedOther != null) {
            beginCancelWait(decisionTimeNanos);
            riskExitOrderPrice = null;
            return DesiredOrders.empty();
        }

        double bestExitPrice = exitSide == OrderSide.BUY
                ? orderBook.getBidPrice(0)
                : orderBook.getAskPrice(0);
        double exitSize = Math.abs(inventory);

        if (projectedExit == null) {
            riskExitOrderPrice = bestExitPrice;
            lastRiskExitQuoteTimeNanos = decisionTimeNanos;
            return quoteOneSide(
                    exitSide,
                    new DesiredOrder(exitSide, riskExitOrderPrice, exitSize)
            );
        }

        boolean knownRiskOrder = riskExitOrderPrice != null
                && Double.compare(projectedExit.getPrice(), riskExitOrderPrice) == 0;
        boolean cannotReverse = projectedExit.getRemainingSize() <= exitSize + POSITION_EPSILON;
        if (!knownRiskOrder || !cannotReverse) {
            beginCancelWait(decisionTimeNanos);
            riskExitOrderPrice = null;
            return DesiredOrders.empty();
        }

        boolean refreshAllowed = decisionTimeNanos - lastRiskExitQuoteTimeNanos
                >= RISK_EXIT_REFRESH_NANOS;
        boolean bestPriceChanged = Double.compare(bestExitPrice, riskExitOrderPrice) != 0;
        if (refreshAllowed && bestPriceChanged) {
            beginCancelWait(decisionTimeNanos);
            riskExitOrderPrice = null;
            return DesiredOrders.empty();
        }
        return keepOneSide(exitSide);
    }

    // Один snapshot входит в каждое окно ровно один раз, как в существующих стратегиях.
    private void updateIndicators(OrderBookEvent orderBook) {
        if (orderBook == lastProcessedOrderBook) {
            return;
        }

        long bookTimeNanos = orderBook.getTimestampNanos();
        inertiaWindow.addSnapshot(
                orderBook.getBidPrice(0),
                orderBook.getAskPrice(0),
                bookTimeNanos
        );
        if (USE_MAGNET) {
            wallDetector.update(orderBook);
        }
        lastProcessedOrderBook = orderBook;
        lastProcessedBookTimeNanos = bookTimeNanos;
    }

    // Stale book отменяет биржевые ордера через runner и сбрасывает краткосрочные сигналы.
    private void handleStaleBook(double inventory, long decisionTimeNanos) {
        inertiaWindow.clear();
        wallDetector.clear();
        lastProcessedOrderBook = null;
        lastProcessedBookTimeNanos = Long.MIN_VALUE;
        cancelBarrier.reset();

        if (hasPosition(inventory)) {
            firstFillTimeNanos = firstFillTimeNanos == Long.MIN_VALUE
                    ? decisionTimeNanos
                    : firstFillTimeNanos;
            state = StrategyState.RISK_EXIT;
            riskExitOrderPrice = null;
        } else {
            clearTradeCycle();
        }
    }

    // Opposite inertia, независимо от стены, переводит открытую позицию в защитный выход.
    private boolean isOppositeSignal(
            PriceInertiaWindow.Signal signal,
            PriceInertiaWindow.Signal positionDirection
    ) {
        return signal != PriceInertiaWindow.Signal.FLAT && signal != positionDirection;
    }

    private boolean reachedStopLoss(
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice
    ) {
        validateAverageEntryPrice(averageEntryPrice);
        if (inventory > 0.0) {
            double stopPrice = averageEntryPrice - STOP_LOSS_TICKS * TICK_SIZE;
            return orderBook.getBidPrice(0) <= stopPrice + POSITION_EPSILON;
        }
        double stopPrice = averageEntryPrice + STOP_LOSS_TICKS * TICK_SIZE;
        return orderBook.getAskPrice(0) >= stopPrice - POSITION_EPSILON;
    }

    private double fixedTarget(double inventory, double averageEntryPrice) {
        double rawTarget = inventory > 0.0
                ? averageEntryPrice + FIXED_EXIT_TICKS * TICK_SIZE
                : averageEntryPrice - FIXED_EXIT_TICKS * TICK_SIZE;
        return roundToTick(rawTarget);
    }

    private double wallTarget(PriceInertiaWindow.Signal direction, double wallPrice) {
        double rawTarget = direction == PriceInertiaWindow.Signal.UP
                ? wallPrice - TICK_SIZE
                : wallPrice + TICK_SIZE;
        return roundToTick(rawTarget);
    }

    private double passiveTargetPrice(
            OrderBookEvent orderBook,
            double inventory,
            double targetPrice
    ) {
        if (inventory > 0.0) {
            return Math.max(targetPrice, orderBook.getAskPrice(0));
        }
        return Math.min(targetPrice, orderBook.getBidPrice(0));
    }

    private boolean positionMatchesPlan(double inventory) {
        if (tradePlan == null) {
            return false;
        }
        if (tradePlan.direction == PriceInertiaWindow.Signal.UP) {
            return inventory > POSITION_EPSILON;
        }
        return inventory < -POSITION_EPSILON;
    }

    private void updateEntryArming(PriceInertiaWindow.Signal signal) {
        if (signal == PriceInertiaWindow.Signal.FLAT) {
            entryArmed = true;
        } else if (lastStartedDirection != null && signal != lastStartedDirection) {
            entryArmed = true;
        }
    }

    private boolean canPlaceEntry(
            DesiredOrder desiredEntry,
            PositionRange positionRange,
            Order projectedEntry
    ) {
        double additionalRisk = desiredEntry.matches(projectedEntry) ? 0.0 : orderSize;
        if (desiredEntry.getSide() == OrderSide.BUY) {
            return positionRange.getMaxPosition() + additionalRisk
                    <= hardInventoryLimit + POSITION_EPSILON;
        }
        return positionRange.getMinPosition() - additionalRisk
                >= -hardInventoryLimit - POSITION_EPSILON;
    }

    private void startRiskExit(long decisionTimeNanos, boolean hasProjectedOrders) {
        state = StrategyState.RISK_EXIT;
        riskExitOrderPrice = null;
        lastRiskExitQuoteTimeNanos = Long.MIN_VALUE;
        if (hasProjectedOrders) {
            beginCancelWait(decisionTimeNanos);
        }
    }

    private void beginCancelWait(long decisionTimeNanos) {
        cancelBarrier.begin(decisionTimeNanos);
    }

    private DesiredOrders quoteOneSide(OrderSide side, DesiredOrder order) {
        if (side == OrderSide.BUY) {
            return new DesiredOrders(
                    OrderInstruction.quote(order),
                    OrderInstruction.cancel()
            );
        }
        return new DesiredOrders(
                OrderInstruction.cancel(),
                OrderInstruction.quote(order)
        );
    }

    private DesiredOrders keepOneSide(OrderSide side) {
        if (side == OrderSide.BUY) {
            return new DesiredOrders(
                    OrderInstruction.keep(),
                    OrderInstruction.cancel()
            );
        }
        return new DesiredOrders(
                OrderInstruction.cancel(),
                OrderInstruction.keep()
        );
    }

    private OrderSide entrySide(PriceInertiaWindow.Signal direction) {
        return direction == PriceInertiaWindow.Signal.UP
                ? OrderSide.BUY
                : OrderSide.SELL;
    }

    private long decisionTime(OrderBookEvent orderBook) {
        long bookTime = orderBook == null ? Long.MIN_VALUE : orderBook.getTimestampNanos();
        return Math.max(bookTime, lastTradeTimeNanos);
    }

    private double roundToTick(double price) {
        return Math.round(price / TICK_SIZE) * TICK_SIZE;
    }

    private boolean hasPosition(double inventory) {
        return Math.abs(inventory) > POSITION_EPSILON;
    }

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

    private void validateAverageEntryPrice(double averageEntryPrice) {
        if (!Double.isFinite(averageEntryPrice) || averageEntryPrice <= 0.0) {
            throw new IllegalArgumentException("Для открытой позиции нужна положительная average entry price");
        }
    }

    private void clearTradeCycle() {
        state = StrategyState.FLAT;
        tradePlan = null;
        firstFillTimeNanos = Long.MIN_VALUE;
        cancelBarrier.reset();
        riskExitOrderPrice = null;
        lastRiskExitQuoteTimeNanos = Long.MIN_VALUE;
    }

    private void resetState() {
        inertiaWindow.clear();
        wallDetector.clear();
        state = StrategyState.FLAT;
        tradePlan = null;
        lastStartedDirection = null;
        entryArmed = true;
        firstFillTimeNanos = Long.MIN_VALUE;
        cancelBarrier.reset();
        riskExitOrderPrice = null;
        lastRiskExitQuoteTimeNanos = Long.MIN_VALUE;
        lastProcessedOrderBook = null;
        lastProcessedBookTimeNanos = Long.MIN_VALUE;
        lastTradeTimeNanos = Long.MIN_VALUE;
        lastObservedTimeNanos = Long.MIN_VALUE;
    }

    private enum StrategyState {
        FLAT,
        ENTRY_PENDING,
        POSITION_OPEN,
        RISK_EXIT
    }

    private static final class TradePlan {
        private final PriceInertiaWindow.Signal direction;
        private final long signalTimeNanos;
        private final double entryOrderPrice;
        private final Double wallPrice;
        private Double targetPrice;
        private Double exitOrderPrice;

        private TradePlan(
                PriceInertiaWindow.Signal direction,
                long signalTimeNanos,
                double entryOrderPrice,
                Double wallPrice,
                Double targetPrice
        ) {
            this.direction = direction;
            this.signalTimeNanos = signalTimeNanos;
            this.entryOrderPrice = entryOrderPrice;
            this.wallPrice = wallPrice;
            this.targetPrice = targetPrice;
            this.exitOrderPrice = null;
        }
    }

    // Отделяет техническое ожидание cancel latency от экономического состояния стратегии.
    private static final class CancelBarrier {
        private final long orderLatencyNanos;
        private long waitUntilNanos;

        private CancelBarrier(long orderLatencyNanos) {
            this.orderLatencyNanos = orderLatencyNanos;
            this.waitUntilNanos = Long.MIN_VALUE;
        }

        private void begin(long decisionTimeNanos) {
            if (!isActive()) {
                waitUntilNanos = Math.addExact(decisionTimeNanos, orderLatencyNanos);
            }
        }

        private boolean isActive() {
            return waitUntilNanos != Long.MIN_VALUE;
        }

        private boolean isFinished(long decisionTimeNanos) {
            if (!isActive()) {
                return true;
            }
            if (decisionTimeNanos <= waitUntilNanos) {
                return false;
            }
            reset();
            return true;
        }

        private void reset() {
            waitUntilNanos = Long.MIN_VALUE;
        }
    }
}
