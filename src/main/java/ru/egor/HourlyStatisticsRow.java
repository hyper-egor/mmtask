package ru.egor;

public final class HourlyStatisticsRow {
    private final long hourEndNanos;
    private final StatisticsSnapshot snapshot;
    private final long fillsInHour;
    private final double tradedVolumeInHour;
    private final long gapResetsInHour;

    public HourlyStatisticsRow(
            long hourEndNanos,
            StatisticsSnapshot snapshot,
            long fillsInHour,
            double tradedVolumeInHour,
            long gapResetsInHour
    ) {
        this.hourEndNanos = hourEndNanos;
        this.snapshot = snapshot;
        this.fillsInHour = fillsInHour;
        this.tradedVolumeInHour = tradedVolumeInHour;
        this.gapResetsInHour = gapResetsInHour;
    }

    public long getHourEndNanos() {
        return hourEndNanos;
    }

    public StatisticsSnapshot getSnapshot() {
        return snapshot;
    }

    public long getFillsInHour() {
        return fillsInHour;
    }

    public double getTradedVolumeInHour() {
        return tradedVolumeInHour;
    }

    public long getGapResetsInHour() {
        return gapResetsInHour;
    }
}
