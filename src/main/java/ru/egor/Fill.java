package ru.egor;

public final class Fill {
    private final long timestampNanos;
    private final long orderId;
    private final OrderSide side;
    private final double price;
    private final double size;

    public Fill(long timestampNanos, long orderId, OrderSide side, double price, double size) {
        if (price <= 0.0 || size <= 0.0) {
            throw new IllegalArgumentException("Цена и объем fill должны быть положительными");
        }

        this.timestampNanos = timestampNanos;
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.size = size;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public long getOrderId() {
        return orderId;
    }

    public OrderSide getSide() {
        return side;
    }

    public double getPrice() {
        return price;
    }

    public double getSize() {
        return size;
    }
}
