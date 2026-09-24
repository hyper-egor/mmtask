package ru.egor;

public final class OrderBookEvent extends Event {
    public static final int LEVEL_COUNT = 20;

    private final double[] bidPrices;
    private final double[] askPrices;
    private final double[] bidQuantities;
    private final double[] askQuantities;

    public OrderBookEvent(
            long timestampNanos,
            double[] bidPrices,
            double[] askPrices,
            double[] bidQuantities,
            double[] askQuantities
    ) {
        super(timestampNanos, EventType.ORDER_BOOK);
        this.bidPrices = bidPrices;
        this.askPrices = askPrices;
        this.bidQuantities = bidQuantities;
        this.askQuantities = askQuantities;
    }

    public double getBidPrice(int levelIndex) {
        return bidPrices[levelIndex];
    }

    public double getAskPrice(int levelIndex) {
        return askPrices[levelIndex];
    }

    public double getBidQuantity(int levelIndex) {
        return bidQuantities[levelIndex];
    }

    public double getAskQuantity(int levelIndex) {
        return askQuantities[levelIndex];
    }
}
