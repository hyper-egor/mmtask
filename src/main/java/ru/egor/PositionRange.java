package ru.egor;

public final class PositionRange {
    private final double minPosition;
    private final double maxPosition;

    public PositionRange(double minPosition, double maxPosition) {
        this.minPosition = minPosition;
        this.maxPosition = maxPosition;
    }

    public double getMinPosition() {
        return minPosition;
    }

    public double getMaxPosition() {
        return maxPosition;
    }
}
