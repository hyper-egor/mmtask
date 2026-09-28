package ru.egor;

public final class StatisticsSnapshot {
    private final double midPrice;
    private final double inventory;
    private final double realizedPnl;
    private final double unrealizedPnl;
    private final double grossPnl;
    private final double feesPaid;
    private final double fundingPnl;
    private final double netPnl;

    public StatisticsSnapshot(Portfolio portfolio, double midPrice) {
        this.midPrice = midPrice;
        this.inventory = portfolio.getInventory();
        this.realizedPnl = portfolio.getRealizedPnl();
        this.unrealizedPnl = portfolio.calculateUnrealizedPnl(midPrice);
        this.grossPnl = portfolio.calculateGrossPnl(midPrice);
        this.feesPaid = portfolio.getFeesPaid();
        this.fundingPnl = portfolio.getFundingPnl();
        this.netPnl = portfolio.calculateNetPnl(midPrice);
    }

    public double getMidPrice() {
        return midPrice;
    }

    public double getInventory() {
        return inventory;
    }

    public double getRealizedPnl() {
        return realizedPnl;
    }

    public double getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public double getGrossPnl() {
        return grossPnl;
    }

    public double getFeesPaid() {
        return feesPaid;
    }

    public double getFundingPnl() {
        return fundingPnl;
    }

    public double getNetPnl() {
        return netPnl;
    }
}
