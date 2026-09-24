package ru.egor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventOrderingTest {

    @Test
    void tradeComesBeforeOrderBookAndFundingAtSameTimestamp() {
        long timestamp = 100L;
        List<Event> events = new ArrayList<>();
        events.add(new FundingRateEvent(timestamp, 0.0001));
        events.add(emptyOrderBook(timestamp));
        events.add(new TradeEvent(timestamp, 2200.0, 1.0, true));

        events.sort(null);

        assertEquals(EventType.TRADE, events.get(0).getType());
        assertEquals(EventType.ORDER_BOOK, events.get(1).getType());
        assertEquals(EventType.FUNDING_RATE, events.get(2).getType());
    }

    private OrderBookEvent emptyOrderBook(long timestamp) {
        return new OrderBookEvent(
                timestamp,
                new double[OrderBookEvent.LEVEL_COUNT],
                new double[OrderBookEvent.LEVEL_COUNT],
                new double[OrderBookEvent.LEVEL_COUNT],
                new double[OrderBookEvent.LEVEL_COUNT]
        );
    }
}
