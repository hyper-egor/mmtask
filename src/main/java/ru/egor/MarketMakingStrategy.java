package ru.egor;

public interface MarketMakingStrategy {

    // Получает каждый trade ровно один раз после обработки возможных fills.
    // Стратегии без trade-сигналов используют пустую реализацию по умолчанию.
    default void onTrade(TradeEvent trade) {
    }

    // Возвращает желаемые bid/ask только по уже известному рынку и текущему состоянию риска.
    DesiredOrders decide(
            OrderBookEvent orderBook,
            boolean bookFresh,
            double inventory,
            double averageEntryPrice,
            PositionRange positionRange,
            Order projectedBid,
            Order projectedAsk
    );
}
