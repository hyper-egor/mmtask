package ru.egor;

public final class SymmetricMarketMakingStrategy implements MarketMakingStrategy {
    private final double orderSize;
    private final double hardInventoryLimit;

    public SymmetricMarketMakingStrategy(double orderSize, double hardInventoryLimit) {
        if (!Double.isFinite(orderSize) || orderSize <= 0.0) {
            throw new IllegalArgumentException("Размер ордера должен быть конечным и положительным");
        }
        if (!Double.isFinite(hardInventoryLimit) || hardInventoryLimit < orderSize) {
            throw new IllegalArgumentException("Hard limit должен быть не меньше размера ордера");
        }

        this.orderSize = orderSize;
        this.hardInventoryLimit = hardInventoryLimit;
    }

    // Котирует лучшие bid/ask фиксированным размером и отключает только небезопасную сторону.
    // Funding, trades и будущие события намеренно не входят в контракт стратегии S0.
    @Override
    public DesiredOrders decide(
            OrderBookEvent orderBook,
            boolean bookFresh,
            double inventory,
            PositionRange positionRange,
            Order projectedBid,
            Order projectedAsk
    ) {
        if (!bookFresh || !hasValidTopOfBook(orderBook)) {
            return DesiredOrders.empty();
        }

        DesiredOrder bid = new DesiredOrder(
                OrderSide.BUY,
                orderBook.getBidPrice(0),
                orderSize
        );
        DesiredOrder ask = new DesiredOrder(
                OrderSide.SELL,
                orderBook.getAskPrice(0),
                orderSize
        );

        if (!canQuoteBid(bid, positionRange, projectedBid)) {
            bid = null;
        }
        if (!canQuoteAsk(ask, positionRange, projectedAsk)) {
            ask = null;
        }

        return new DesiredOrders(bid, ask);
    }

    // Уже существующий совпадающий bid учтен в PositionRange; новый или replacement добавляет полный размер.
    private boolean canQuoteBid(DesiredOrder desiredBid, PositionRange positionRange, Order projectedBid) {
        double additionalRisk = desiredBid.matches(projectedBid) ? 0.0 : orderSize;
        double positionAfterAllBuyFills = positionRange.getMaxPosition() + additionalRisk;
        return positionAfterAllBuyFills <= hardInventoryLimit;
    }

    // Уже существующий совпадающий ask учтен в PositionRange; новый или replacement добавляет полный размер.
    private boolean canQuoteAsk(DesiredOrder desiredAsk, PositionRange positionRange, Order projectedAsk) {
        double additionalRisk = desiredAsk.matches(projectedAsk) ? 0.0 : orderSize;
        double positionAfterAllSellFills = positionRange.getMinPosition() - additionalRisk;
        return positionAfterAllSellFills >= -hardInventoryLimit;
    }

    // Некорректный или crossed top of book не используется для выставления лимитных ордеров.
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
}
