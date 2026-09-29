package ru.egor;

public final class DesiredOrders {
    private static final DesiredOrders EMPTY = new DesiredOrders(
            OrderInstruction.cancel(),
            OrderInstruction.cancel()
    );
    private static final DesiredOrders KEEP_BOTH = new DesiredOrders(
            OrderInstruction.keep(),
            OrderInstruction.keep()
    );

    private final OrderInstruction bidInstruction;
    private final OrderInstruction askInstruction;

    // Старый контракт сохраняется: заданный ордер означает QUOTE, null означает CANCEL.
    public DesiredOrders(DesiredOrder bid, DesiredOrder ask) {
        this(toInstruction(bid), toInstruction(ask));
    }

    public DesiredOrders(OrderInstruction bidInstruction, OrderInstruction askInstruction) {
        if (bidInstruction == null || askInstruction == null) {
            throw new IllegalArgumentException("Инструкции для bid и ask обязательны");
        }

        DesiredOrder bid = bidInstruction.getDesiredOrder();
        DesiredOrder ask = askInstruction.getDesiredOrder();
        if (bid != null && bid.getSide() != OrderSide.BUY) {
            throw new IllegalArgumentException("Bid должен иметь сторону BUY");
        }
        if (ask != null && ask.getSide() != OrderSide.SELL) {
            throw new IllegalArgumentException("Ask должен иметь сторону SELL");
        }

        this.bidInstruction = bidInstruction;
        this.askInstruction = askInstruction;
    }

    public static DesiredOrders empty() {
        return EMPTY;
    }

    public static DesiredOrders keepBoth() {
        return KEEP_BOTH;
    }

    public DesiredOrder getBid() {
        return bidInstruction.getDesiredOrder();
    }

    public DesiredOrder getAsk() {
        return askInstruction.getDesiredOrder();
    }

    public DesiredOrder get(OrderSide side) {
        return getInstruction(side).getDesiredOrder();
    }

    public OrderInstruction getInstruction(OrderSide side) {
        return side == OrderSide.BUY ? bidInstruction : askInstruction;
    }

    private static OrderInstruction toInstruction(DesiredOrder desiredOrder) {
        if (desiredOrder == null) {
            return OrderInstruction.cancel();
        }
        return OrderInstruction.quote(desiredOrder);
    }
}
