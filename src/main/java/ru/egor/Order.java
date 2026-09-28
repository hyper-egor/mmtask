package ru.egor;

public final class Order {
    private final long id;
    private final OrderSide side;
    private final double price;
    private final double originalSize;
    private double remainingSize;
    private double queueAhead;
    private boolean queueInitialized;

    public Order(long id, OrderSide side, double price, double remainingSize) {
        if (price <= 0.0 || remainingSize <= 0.0) {
            throw new IllegalArgumentException("Цена и объем ордера должны быть положительными");
        }

        this.id = id;
        this.side = side;
        this.price = price;
        this.originalSize = remainingSize;
        this.remainingSize = remainingSize;
        this.queueAhead = 0.0;
        this.queueInitialized = false;
    }

    public long getId() {
        return id;
    }

    public OrderSide getSide() {
        return side;
    }

    public double getPrice() {
        return price;
    }

    public double getRemainingSize() {
        return remainingSize;
    }

    public double getOriginalSize() {
        return originalSize;
    }

    public double getQueueAhead() {
        if (!queueInitialized) {
            throw new IllegalStateException("Очередь еще не инициализирована");
        }
        return queueAhead;
    }

    public boolean isQueueInitialized() {
        return queueInitialized;
    }

    // Фиксирует видимый объем перед ордером ровно один раз, при его активации.
    public void initializeQueue(double visibleSizeAhead) {
        if (queueInitialized) {
            throw new IllegalStateException("Очередь ордера уже инициализирована");
        }
        if (!Double.isFinite(visibleSizeAhead) || visibleSizeAhead < 0.0) {
            throw new IllegalArgumentException("Очередь должна быть конечной и неотрицательной");
        }

        queueAhead = visibleSizeAhead;
        queueInitialized = true;
    }

    // Сначала расходует очередь и возвращает объем trade, дошедший до нашего ордера.
    public double consumeQueue(double tradeSize) {
        if (!queueInitialized) {
            throw new IllegalStateException("Нельзя расходовать неинициализированную очередь");
        }
        if (!Double.isFinite(tradeSize) || tradeSize < 0.0) {
            throw new IllegalArgumentException("Объем trade должен быть конечным и неотрицательным");
        }

        double consumedSize = Math.min(queueAhead, tradeSize);
        queueAhead -= consumedSize;
        return tradeSize - consumedSize;
    }

    // Уменьшает остаток активного ордера после частичного или полного fill.
    public void applyFill(double fillSize) {
        if (fillSize <= 0.0 || fillSize > remainingSize) {
            throw new IllegalArgumentException("Некорректный размер fill: " + fillSize);
        }
        remainingSize -= fillSize;
    }

    public boolean isFilled() {
        return remainingSize == 0.0;
    }

    // Копия нужна для projected state и обязана сохранять уже накопленное состояние очереди.
    public Order copy() {
        Order copy = new Order(id, side, price, originalSize);
        copy.remainingSize = remainingSize;
        if (queueInitialized) {
            copy.initializeQueue(queueAhead);
        }
        return copy;
    }
}
