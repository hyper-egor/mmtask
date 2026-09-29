package ru.egor;

public final class OrderInstruction {
    public enum Action {
        KEEP,
        CANCEL,
        QUOTE
    }

    private static final OrderInstruction KEEP = new OrderInstruction(Action.KEEP, null);
    private static final OrderInstruction CANCEL = new OrderInstruction(Action.CANCEL, null);

    private final Action action;
    private final DesiredOrder desiredOrder;

    private OrderInstruction(Action action, DesiredOrder desiredOrder) {
        this.action = action;
        this.desiredOrder = desiredOrder;
    }

    public static OrderInstruction keep() {
        return KEEP;
    }

    public static OrderInstruction cancel() {
        return CANCEL;
    }

    public static OrderInstruction quote(DesiredOrder desiredOrder) {
        if (desiredOrder == null) {
            throw new IllegalArgumentException("Для QUOTE нужен желаемый ордер");
        }
        return new OrderInstruction(Action.QUOTE, desiredOrder);
    }

    public Action getAction() {
        return action;
    }

    public DesiredOrder getDesiredOrder() {
        return desiredOrder;
    }
}
