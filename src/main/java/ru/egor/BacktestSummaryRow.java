package ru.egor;

public final class BacktestSummaryRow {
    private final String period;
    private final StatisticsSnapshot snapshot;
    private final double dailyPnl;
    private final double maxLongInventory;
    private final double maxShortInventory;
    private final double averageAbsInventory;
    private final double maxDrawdown;
    private final long activatedOrders;
    private final long fillEvents;
    private final long fullyFilledOrders;
    private final long partiallyFilledOrders;
    private final double buyFillVolume;
    private final double sellFillVolume;
    private final long cancelCommands;
    private final long replacements;
    private final double averageOrderLifetimeMillis;
    private final long gapResets;
    private final double staleSeconds;

    public BacktestSummaryRow(
            String period,
            StatisticsSnapshot snapshot,
            double dailyPnl,
            double maxLongInventory,
            double maxShortInventory,
            double averageAbsInventory,
            double maxDrawdown,
            long activatedOrders,
            long fillEvents,
            long fullyFilledOrders,
            long partiallyFilledOrders,
            double buyFillVolume,
            double sellFillVolume,
            long cancelCommands,
            long replacements,
            double averageOrderLifetimeMillis,
            long gapResets,
            double staleSeconds
    ) {
        this.period = period;
        this.snapshot = snapshot;
        this.dailyPnl = dailyPnl;
        this.maxLongInventory = maxLongInventory;
        this.maxShortInventory = maxShortInventory;
        this.averageAbsInventory = averageAbsInventory;
        this.maxDrawdown = maxDrawdown;
        this.activatedOrders = activatedOrders;
        this.fillEvents = fillEvents;
        this.fullyFilledOrders = fullyFilledOrders;
        this.partiallyFilledOrders = partiallyFilledOrders;
        this.buyFillVolume = buyFillVolume;
        this.sellFillVolume = sellFillVolume;
        this.cancelCommands = cancelCommands;
        this.replacements = replacements;
        this.averageOrderLifetimeMillis = averageOrderLifetimeMillis;
        this.gapResets = gapResets;
        this.staleSeconds = staleSeconds;
    }

    public String getPeriod() { return period; }
    public StatisticsSnapshot getSnapshot() { return snapshot; }
    public double getDailyPnl() { return dailyPnl; }
    public double getMaxLongInventory() { return maxLongInventory; }
    public double getMaxShortInventory() { return maxShortInventory; }
    public double getAverageAbsInventory() { return averageAbsInventory; }
    public double getMaxDrawdown() { return maxDrawdown; }
    public long getActivatedOrders() { return activatedOrders; }
    public long getFillEvents() { return fillEvents; }
    public long getFullyFilledOrders() { return fullyFilledOrders; }
    public long getPartiallyFilledOrders() { return partiallyFilledOrders; }
    public double getBuyFillVolume() { return buyFillVolume; }
    public double getSellFillVolume() { return sellFillVolume; }
    public double getTotalFillVolume() { return buyFillVolume + sellFillVolume; }
    public double getAverageFillSize() { return fillEvents == 0L ? 0.0 : getTotalFillVolume() / fillEvents; }
    public long getCancelCommands() { return cancelCommands; }
    public long getReplacements() { return replacements; }
    public double getAverageOrderLifetimeMillis() { return averageOrderLifetimeMillis; }
    public long getGapResets() { return gapResets; }
    public double getStaleSeconds() { return staleSeconds; }
}
