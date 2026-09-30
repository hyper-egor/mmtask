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
    private final long reduceActivatedOrders;
    private final long fillEvents;
    private final long fullyFilledOrders;
    private final long partiallyFilledOrders;
    private final long reduceFillEvents;
    private final long reduceFullyFilledOrders;
    private final long reducePartiallyFilledOrders;
    private final double reduceFillVolume;
    private final double buyFillVolume;
    private final double sellFillVolume;
    private final long cancelCommands;
    private final long replacements;
    private final long reduceReplacements;
    private final double averageOrderLifetimeMillis;
    private final double averageReduceOrderLifetimeMillis;
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
            long reduceActivatedOrders,
            long fillEvents,
            long fullyFilledOrders,
            long partiallyFilledOrders,
            long reduceFillEvents,
            long reduceFullyFilledOrders,
            long reducePartiallyFilledOrders,
            double reduceFillVolume,
            double buyFillVolume,
            double sellFillVolume,
            long cancelCommands,
            long replacements,
            long reduceReplacements,
            double averageOrderLifetimeMillis,
            double averageReduceOrderLifetimeMillis,
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
        this.reduceActivatedOrders = reduceActivatedOrders;
        this.fillEvents = fillEvents;
        this.fullyFilledOrders = fullyFilledOrders;
        this.partiallyFilledOrders = partiallyFilledOrders;
        this.reduceFillEvents = reduceFillEvents;
        this.reduceFullyFilledOrders = reduceFullyFilledOrders;
        this.reducePartiallyFilledOrders = reducePartiallyFilledOrders;
        this.reduceFillVolume = reduceFillVolume;
        this.buyFillVolume = buyFillVolume;
        this.sellFillVolume = sellFillVolume;
        this.cancelCommands = cancelCommands;
        this.replacements = replacements;
        this.reduceReplacements = reduceReplacements;
        this.averageOrderLifetimeMillis = averageOrderLifetimeMillis;
        this.averageReduceOrderLifetimeMillis = averageReduceOrderLifetimeMillis;
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
    public long getReduceActivatedOrders() { return reduceActivatedOrders; }
    public long getFillEvents() { return fillEvents; }
    public long getFullyFilledOrders() { return fullyFilledOrders; }
    public long getPartiallyFilledOrders() { return partiallyFilledOrders; }
    public long getReduceFillEvents() { return reduceFillEvents; }
    public long getReduceFullyFilledOrders() { return reduceFullyFilledOrders; }
    public long getReducePartiallyFilledOrders() { return reducePartiallyFilledOrders; }
    public double getReduceFillVolume() { return reduceFillVolume; }
    public double getBuyFillVolume() { return buyFillVolume; }
    public double getSellFillVolume() { return sellFillVolume; }
    public double getTotalFillVolume() { return buyFillVolume + sellFillVolume; }
    public double getAverageFillSize() { return fillEvents == 0L ? 0.0 : getTotalFillVolume() / fillEvents; }
    public long getCancelCommands() { return cancelCommands; }
    public long getReplacements() { return replacements; }
    public long getReduceReplacements() { return reduceReplacements; }
    public double getAverageOrderLifetimeMillis() { return averageOrderLifetimeMillis; }
    public double getAverageReduceOrderLifetimeMillis() { return averageReduceOrderLifetimeMillis; }
    public long getGapResets() { return gapResets; }
    public double getStaleSeconds() { return staleSeconds; }
}
