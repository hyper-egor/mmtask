package ru.egor;

public final class BacktestParameters {
    public static final double DEFAULT_ORDER_SIZE = 1.0;
    public static final double DEFAULT_HARD_INVENTORY_LIMIT = 5.0;
    public static final long DEFAULT_ORDER_LATENCY_NANOS = 10_000_000L;
    public static final long DEFAULT_MAX_BOOK_AGE_NANOS = 1_000_000_000L;
    public static final double DEFAULT_MAKER_FEE_BPS = 0.0;

    private final double orderSize;
    private final double hardInventoryLimit;
    private final long orderLatencyNanos;
    private final long maxBookAgeNanos;
    private final double makerFeeBps;

    public BacktestParameters(long orderLatencyNanos, double makerFeeBps) {
        this(
                DEFAULT_ORDER_SIZE,
                DEFAULT_HARD_INVENTORY_LIMIT,
                orderLatencyNanos,
                DEFAULT_MAX_BOOK_AGE_NANOS,
                makerFeeBps
        );
    }

    public BacktestParameters(long orderLatencyNanos, long maxBookAgeNanos, double makerFeeBps) {
        this(
                DEFAULT_ORDER_SIZE,
                DEFAULT_HARD_INVENTORY_LIMIT,
                orderLatencyNanos,
                maxBookAgeNanos,
                makerFeeBps
        );
    }

    public BacktestParameters(
            double orderSize,
            double hardInventoryLimit,
            long orderLatencyNanos,
            long maxBookAgeNanos,
            double makerFeeBps
    ) {
        if (!Double.isFinite(orderSize) || orderSize <= 0.0) {
            throw new IllegalArgumentException("Размер ордера должен быть конечным и положительным");
        }
        if (!Double.isFinite(hardInventoryLimit) || hardInventoryLimit < orderSize) {
            throw new IllegalArgumentException("Hard limit должен быть не меньше размера ордера");
        }
        if (orderLatencyNanos < 0L) {
            throw new IllegalArgumentException("Latency не может быть отрицательной");
        }
        if (maxBookAgeNanos <= 0L) {
            throw new IllegalArgumentException("Максимальный возраст стакана должен быть положительным");
        }
        if (!Double.isFinite(makerFeeBps)) {
            throw new IllegalArgumentException("Maker fee должна быть конечным числом");
        }

        this.orderSize = orderSize;
        this.hardInventoryLimit = hardInventoryLimit;
        this.orderLatencyNanos = orderLatencyNanos;
        this.maxBookAgeNanos = maxBookAgeNanos;
        this.makerFeeBps = makerFeeBps;
    }

    // Возвращает один явно зафиксированный набор параметров для первого запуска S0.
    public static BacktestParameters baseline() {
        return new BacktestParameters(
                DEFAULT_ORDER_SIZE,
                DEFAULT_HARD_INVENTORY_LIMIT,
                DEFAULT_ORDER_LATENCY_NANOS,
                DEFAULT_MAX_BOOK_AGE_NANOS,
                DEFAULT_MAKER_FEE_BPS
        );
    }

    public double getOrderSize() {
        return orderSize;
    }

    public double getHardInventoryLimit() {
        return hardInventoryLimit;
    }

    public long getOrderLatencyNanos() {
        return orderLatencyNanos;
    }

    public double getMakerFeeBps() {
        return makerFeeBps;
    }

    public long getMaxBookAgeNanos() {
        return maxBookAgeNanos;
    }
}
