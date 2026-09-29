package ru.egor;

public final class ImbalanceProfitExitStrategy implements MarketMakingStrategy {
    public static final long DEFAULT_IMBALANCE_WINDOW_NANOS = 60_000_000_000L;
    public static final long DEFAULT_AGGRESSION_WINDOW_NANOS = 60_000_000_000L;
    public static final double DEFAULT_AGGRESSION_RATIO = 1.01;
    public static final double WINDOW_IMBALANCE_RATIO = 1.6;
    public static final double INSTANT_IMBALANCE_RATIO = 1.6;
    public static final int DEFAULT_ROLLING_BOOK_LEVELS = 5;
    public static final int DEFAULT_INSTANT_BOOK_LEVELS = 20;

    private final SymmetricMarketMakingStrategy baselineStrategy;
    private final MMATema bidTop5Window;
    private final MMATema askTop5Window;
    private final MMATema aggressiveBuyWindow;
    private final MMATema aggressiveSellWindow;
    private final double orderSize;
    private final double aggressionRatio;
    private final double minimumExitProfit;
    private final int rollingBookLevels;
    private final int instantBookLevels;

    private OrderBookEvent lastProcessedOrderBook;
    private long lastProcessedBookTimeNanos;
    private long lastProcessedTradeTimeNanos;

    public ImbalanceProfitExitStrategy(
            double orderSize,
            double hardInventoryLimit,
            double minimumExitProfit
    ) {
        this(
                orderSize,
                hardInventoryLimit,
                minimumExitProfit,
                DEFAULT_IMBALANCE_WINDOW_NANOS,
                DEFAULT_AGGRESSION_RATIO,
                DEFAULT_ROLLING_BOOK_LEVELS,
                DEFAULT_INSTANT_BOOK_LEVELS
        );
    }

    public ImbalanceProfitExitStrategy(
            double orderSize,
            double hardInventoryLimit,
            double minimumExitProfit,
            long imbalanceWindowNanos,
            double aggressionRatio,
            int rollingBookLevels,
            int instantBookLevels
    ) {
        if (!Double.isFinite(minimumExitProfit) || minimumExitProfit < 0.0) {
            throw new IllegalArgumentException("Минимальная прибыль выхода должна быть неотрицательной");
        }
        if (!Double.isFinite(aggressionRatio) || aggressionRatio <= 1.0) {
            throw new IllegalArgumentException("Коэффициент aggression должен быть больше 1");
        }
        if (rollingBookLevels <= 0 || rollingBookLevels > OrderBookEvent.LEVEL_COUNT) {
            throw new IllegalArgumentException("Некорректное число уровней для плавающего окна");
        }
        if (instantBookLevels <= 0 || instantBookLevels > OrderBookEvent.LEVEL_COUNT) {
            throw new IllegalArgumentException("Некорректное число уровней моментального среза");
        }

        this.baselineStrategy = new SymmetricMarketMakingStrategy(orderSize, hardInventoryLimit);
        this.bidTop5Window = new MMATema(imbalanceWindowNanos);
        this.askTop5Window = new MMATema(imbalanceWindowNanos);
        this.aggressiveBuyWindow = new MMATema(DEFAULT_AGGRESSION_WINDOW_NANOS);
        this.aggressiveSellWindow = new MMATema(DEFAULT_AGGRESSION_WINDOW_NANOS);
        this.orderSize = orderSize;
        this.aggressionRatio = aggressionRatio;
        this.minimumExitProfit = minimumExitProfit;
        this.rollingBookLevels = rollingBookLevels;
        this.instantBookLevels = instantBookLevels;
        this.lastProcessedOrderBook = null;
        this.lastProcessedBookTimeNanos = Long.MIN_VALUE;
        this.lastProcessedTradeTimeNanos = Long.MIN_VALUE;
    }

    // Копирует накопление aggressive trades из BaseImbalanceStrategy.
    @Override
    public void onTrade(TradeEvent trade) {
        long tradeTimeNanos = trade.getTimestampNanos();

        if (tradeTimeNanos < lastProcessedTradeTimeNanos) {
            aggressiveBuyWindow.clear();
            aggressiveSellWindow.clear();
        }

        double aggressiveBuyVolume = trade.isMakerAsk() ? trade.getSize() : 0.0;
        double aggressiveSellVolume = trade.isMakerAsk() ? 0.0 : trade.getSize();
        aggressiveBuyWindow.addStat(aggressiveBuyVolume, tradeTimeNanos);
        aggressiveSellWindow.addStat(aggressiveSellVolume, tradeTimeNanos);
        lastProcessedTradeTimeNanos = tradeTimeNanos;
    }

    // Сохраняет направленную логику BaseImbalanceStrategy и добавляет только
    // прибыльный ордер, который уменьшает уже открытую позицию.
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

        DesiredOrders baselineOrders = baselineStrategy.decide(
                orderBook,
                bookFresh,
                inventory,
                averageEntryPrice,
                positionRange,
                projectedBid,
                projectedAsk
        );
        if (baselineOrders.getBid() == null && baselineOrders.getAsk() == null) {
            return baselineOrders;
        }

        ImbalanceSignal windowSignal = classifyImbalance(
                bidTop5Window.getSum(),
                askTop5Window.getSum(),
                WINDOW_IMBALANCE_RATIO
        );
        ImbalanceSignal instantSignal = calculateInstantSignal(orderBook);
        ImbalanceSignal aggressionSignal = classifyImbalance(
                aggressiveBuyWindow.getSum(),
                aggressiveSellWindow.getSum(),
                aggressionRatio
        );

        return chooseOrders(
                orderBook,
                inventory,
                averageEntryPrice,
                baselineOrders,
                windowSignal,
                instantSignal,
                aggressionSignal
        );
    }

    // Один snapshot добавляется в окно ровно один раз. Правила полностью повторяют
    // текущее накопление стакана в BaseImbalanceStrategy.
    private void updateRollingWindow(OrderBookEvent orderBook) {
        if (orderBook == null || orderBook == lastProcessedOrderBook) {
            return;
        }

        long bookTimeNanos = orderBook.getTimestampNanos();
        if (bookTimeNanos < lastProcessedBookTimeNanos) {
            bidTop5Window.clear();
            askTop5Window.clear();
            aggressiveBuyWindow.clear();
            aggressiveSellWindow.clear();
            lastProcessedTradeTimeNanos = Long.MIN_VALUE;
        }

        double bidVolume = sumBidQuantities(orderBook, rollingBookLevels);
        double askVolume = sumAskQuantities(orderBook, rollingBookLevels);
        bidTop5Window.addStat(bidVolume, bookTimeNanos);
        askTop5Window.addStat(askVolume, bookTimeNanos);

        lastProcessedOrderBook = orderBook;
        lastProcessedBookTimeNanos = bookTimeNanos;
    }

    // Моментальный TOP20 сохраняется как в текущем BaseImbalanceStrategy,
    // хотя его проверка пока отключена в итоговом условии сигнала.
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

    // Повторяет текущий фильтр BaseImbalanceStrategy. Отличие только в том, что
    // противоположная или нейтральная сторона может получить прибыльный exit-ордер.
    private DesiredOrders chooseOrders(
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice,
            DesiredOrders baselineOrders,
            ImbalanceSignal windowSignal,
            ImbalanceSignal instantSignal,
            ImbalanceSignal aggressionSignal
    ) {
        if (windowSignal == ImbalanceSignal.BID_DOMINANT
                // && instantSignal == ImbalanceSignal.BID_DOMINANT
                && aggressionSignal == ImbalanceSignal.BID_DOMINANT) {
            return new DesiredOrders(
                    quoteOrCancel(baselineOrders.getBid()),
                    profitableAsk(orderBook, inventory, averageEntryPrice)
            );
        }

        if (windowSignal == ImbalanceSignal.ASK_DOMINANT
                // && instantSignal == ImbalanceSignal.ASK_DOMINANT
                && aggressionSignal == ImbalanceSignal.ASK_DOMINANT) {
            return new DesiredOrders(
                    profitableBid(orderBook, inventory, averageEntryPrice),
                    quoteOrCancel(baselineOrders.getAsk())
            );
        }

        return profitableExitOnly(orderBook, inventory, averageEntryPrice);
    }

    // Без сильного сигнала оставляет только прибыльный ордер, уменьшающий inventory.
    private DesiredOrders profitableExitOnly(
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice
    ) {
        if (inventory > 0.0) {
            return new DesiredOrders(
                    OrderInstruction.cancel(),
                    profitableAsk(orderBook, inventory, averageEntryPrice)
            );
        }
        if (inventory < 0.0) {
            return new DesiredOrders(
                    profitableBid(orderBook, inventory, averageEntryPrice),
                    OrderInstruction.cancel()
            );
        }
        return DesiredOrders.empty();
    }

    // Для long выставляет ask только по текущей лучшей цене и только при достаточной прибыли.
    private OrderInstruction profitableAsk(
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice
    ) {
        if (inventory <= 0.0) {
            return OrderInstruction.cancel();
        }
        validateAverageEntryPrice(averageEntryPrice);

        double bestAsk = orderBook.getAskPrice(0);
        if (bestAsk - averageEntryPrice < minimumExitProfit) {
            return OrderInstruction.cancel();
        }

        double exitSize = Math.min(orderSize, inventory);
        return OrderInstruction.quote(new DesiredOrder(OrderSide.SELL, bestAsk, exitSize));
    }

    // Для short выставляет bid только по текущей лучшей цене и только при достаточной прибыли.
    private OrderInstruction profitableBid(
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice
    ) {
        if (inventory >= 0.0) {
            return OrderInstruction.cancel();
        }
        validateAverageEntryPrice(averageEntryPrice);

        double bestBid = orderBook.getBidPrice(0);
        if (averageEntryPrice - bestBid < minimumExitProfit) {
            return OrderInstruction.cancel();
        }

        double exitSize = Math.min(orderSize, Math.abs(inventory));
        return OrderInstruction.quote(new DesiredOrder(OrderSide.BUY, bestBid, exitSize));
    }

    private void validateAverageEntryPrice(double averageEntryPrice) {
        if (!Double.isFinite(averageEntryPrice) || averageEntryPrice <= 0.0) {
            throw new IllegalArgumentException("Для открытой позиции нужна положительная average entry price");
        }
    }

    private OrderInstruction quoteOrCancel(DesiredOrder desiredOrder) {
        if (desiredOrder == null) {
            return OrderInstruction.cancel();
        }
        return OrderInstruction.quote(desiredOrder);
    }

    private double sumBidQuantities(OrderBookEvent orderBook, int levelCount) {
        double total = 0.0;
        for (int level = 0; level < levelCount; level++) {
            total += orderBook.getBidQuantity(level);
        }
        return total;
    }

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
}
