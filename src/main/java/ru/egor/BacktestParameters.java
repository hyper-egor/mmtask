package ru.egor;

public final class BacktestParameters {
    public static final long DEFAULT_MAX_BOOK_AGE_NANOS = 1_000_000_000L;

    private final long orderLatencyNanos;
    private final long maxBookAgeNanos;
    private final double makerFeeBps;

    public BacktestParameters(long orderLatencyNanos, double makerFeeBps) {
        this(orderLatencyNanos, DEFAULT_MAX_BOOK_AGE_NANOS, makerFeeBps);
    }

    public BacktestParameters(long orderLatencyNanos, long maxBookAgeNanos, double makerFeeBps) {
        if (orderLatencyNanos < 0L) {
            throw new IllegalArgumentException("Latency не может быть отрицательной");
        }
        if (maxBookAgeNanos <= 0L) {
            throw new IllegalArgumentException("Максимальный возраст стакана должен быть положительным");
        }
        if (!Double.isFinite(makerFeeBps)) {
            throw new IllegalArgumentException("Maker fee должна быть конечным числом");
        }

        this.orderLatencyNanos = orderLatencyNanos;
        this.maxBookAgeNanos = maxBookAgeNanos;
        this.makerFeeBps = makerFeeBps;
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
