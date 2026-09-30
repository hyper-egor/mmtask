package ru.egor;

public final class DesiredOrder {
    private final OrderSide side;
    private final double price;
    private final double size;
    private final boolean reduceOnly;

    public DesiredOrder(OrderSide side, double price, double size) {
        this(side, price, size, false);
    }

    public DesiredOrder(OrderSide side, double price, double size, boolean reduceOnly) {
        if (price <= 0.0 || size <= 0.0) {
            throw new IllegalArgumentException("Цена и объем желаемого ордера должны быть положительными");
        }

        this.side = side;
        this.price = price;
        this.size = size;
        this.reduceOnly = reduceOnly;
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

    public boolean isReduceOnly() {
        return reduceOnly;
    }

    // Проверяет, совпадает ли уже спроецированный ордер с желаемой котировкой.
    public boolean matches(Order order) {
        return order != null
                && side == order.getSide()
                && Double.compare(price, order.getPrice()) == 0
                && Double.compare(size, order.getOriginalSize()) == 0
                && reduceOnly == order.isReduceOnly();
    }
}
