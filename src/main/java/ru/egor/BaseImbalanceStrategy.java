package ru.egor;

public final class BaseImbalanceStrategy implements MarketMakingStrategy {
    public static final long DEFAULT_IMBALANCE_WINDOW_NANOS = 120_000_000_000L;
    public static final long DEFAULT_AGGRESSION_WINDOW_NANOS = 120_000_000_000L;
    public static final double DEFAULT_AGGRESSION_RATIO = 1.01; // 1.01
    public static final double WINDOW_IMBALANCE_RATIO = 1.6;   // 1.6
    public static final double INSTANT_IMBALANCE_RATIO = 1.2;
    public static final int DEFAULT_ROLLING_BOOK_LEVELS = 5;
    public static final int DEFAULT_INSTANT_BOOK_LEVELS = 20;

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
    }

    // Накапливает отдельно объем агрессивных покупок и продаж за 120 секунд.
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

        return filterAllowedSides(
                baselineOrders,
                projectedBid,
                projectedAsk,
                windowSignal,
                instantSignal,
                aggressionSignal
        );
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

    // Совпадение стаканного окна и aggression разрешает только сторону сигнала.
    // Моментальный TOP20 сейчас рассчитан, но вручную исключен из условия.
    private DesiredOrders filterAllowedSides(
            DesiredOrders baselineOrders,
            Order projectedBid,
            Order projectedAsk,
            ImbalanceSignal windowSignal,
            ImbalanceSignal instantSignal,
            ImbalanceSignal aggressionSignal
    ) {
        if (windowSignal == ImbalanceSignal.BID_DOMINANT
                //&& instantSignal == ImbalanceSignal.BID_DOMINANT
                //&& aggressionSignal == ImbalanceSignal.BID_DOMINANT
        ) {
            return new DesiredOrders(
                    quoteOrCancel(baselineOrders.getBid()),
                    //keepOrQuoteOrCancel(baselineOrders.getBid(), projectedBid),
                    OrderInstruction.cancel()
            );
        }

        if (windowSignal == ImbalanceSignal.ASK_DOMINANT
                //&& instantSignal == ImbalanceSignal.ASK_DOMINANT
                //&& aggressionSignal == ImbalanceSignal.ASK_DOMINANT
        ) {
            return new DesiredOrders(
                    OrderInstruction.cancel(),
                    quoteOrCancel(baselineOrders.getAsk())
                    //keepOrQuoteOrCancel(baselineOrders.getAsk(), projectedAsk)
            );
        }

        return new DesiredOrders(
                //keepOrCancel(baselineOrders.getBid()),
                //keepOrCancel(baselineOrders.getAsk())
                keepOrCancel( null ),
                keepOrCancel( null )
        );
    }

    // Risk logic S0 имеет приоритет: если baseline запретил сторону, ее нужно отменить.
    private OrderInstruction quoteOrCancel(DesiredOrder desiredOrder) {
        if (desiredOrder == null) {
            return OrderInstruction.cancel();
        }
        return OrderInstruction.quote(desiredOrder);
    }

    // Возвращает KEEP для существующей baseline-стороны либо CANCEL для запрещенной.
    // Текущая ветка слабого сигнала намеренно передает сюда null и отменяет обе стороны.
    private OrderInstruction keepOrCancel(DesiredOrder desiredOrder) {
        if (desiredOrder == null) {
            return OrderInstruction.cancel();
        }
        return OrderInstruction.keep();
    }

    // Если ордер уже есть или стоит в pending-командах, оставляем его.
    // Если стороны еще нет, создаем baseline-ордер; если baseline запретил сторону, отменяем.
    private OrderInstruction keepOrQuoteOrCancel(DesiredOrder desiredOrder, Order projectedOrder) {
        if (desiredOrder == null) {
            return OrderInstruction.cancel();
        }
        if (projectedOrder == null) {
            return OrderInstruction.quote(desiredOrder);
        }
        return OrderInstruction.keep();
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
}
