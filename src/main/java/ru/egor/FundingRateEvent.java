package ru.egor;

public final class FundingRateEvent extends Event {
    private final double fundingRate;

    public FundingRateEvent(long timestampNanos, double fundingRate) {
        super(timestampNanos, EventType.FUNDING_RATE);
        this.fundingRate = fundingRate;
    }

    public double getFundingRate() {
        return fundingRate;
    }
}
