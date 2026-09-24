package ru.egor;

public enum EventType {
    TRADE(0),
    ORDER_BOOK(1),
    FUNDING_RATE(2);

    private final int orderAtSameTimestamp;

    EventType(int orderAtSameTimestamp) {
        this.orderAtSameTimestamp = orderAtSameTimestamp;
    }

    public int getOrderAtSameTimestamp() {
        return orderAtSameTimestamp;
    }
}
