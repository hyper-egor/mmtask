package ru.egor;

public final class DesiredOrders {
    private static final DesiredOrders EMPTY = new DesiredOrders(null, null);

    private final DesiredOrder bid;
    private final DesiredOrder ask;

    public DesiredOrders(DesiredOrder bid, DesiredOrder ask) {
        if (bid != null && bid.getSide() != OrderSide.BUY) {
            throw new IllegalArgumentException("Bid должен иметь сторону BUY");
        }
        if (ask != null && ask.getSide() != OrderSide.SELL) {
            throw new IllegalArgumentException("Ask должен иметь сторону SELL");
        }

        this.bid = bid;
        this.ask = ask;
    }

    public static DesiredOrders empty() {
        return EMPTY;
    }

    public DesiredOrder getBid() {
        return bid;
    }

    public DesiredOrder getAsk() {
        return ask;
    }

    public DesiredOrder get(OrderSide side) {
        return side == OrderSide.BUY ? bid : ask;
    }
}
