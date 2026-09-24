package ru.egor;

public final class TradeEvent extends Event {
    private final double price;
    private final double size;
    private final boolean makerAsk;

    public TradeEvent(long timestampNanos, double price, double size, boolean makerAsk) {
        super(timestampNanos, EventType.TRADE);
        this.price = price;
        this.size = size;
        this.makerAsk = makerAsk;
    }

    public double getPrice() {
        return price;
    }

    public double getSize() {
        return size;
    }

    public boolean isMakerAsk() {
        return makerAsk;
    }
}
