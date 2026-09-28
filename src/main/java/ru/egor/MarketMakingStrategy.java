package ru.egor;

public interface MarketMakingStrategy {

    // Возвращает желаемые bid/ask только по уже известному рынку и текущему состоянию риска.
    DesiredOrders decide(
            OrderBookEvent orderBook,
            boolean bookFresh,
            double inventory,
            PositionRange positionRange,
            Order projectedBid,
            Order projectedAsk
    );
}
