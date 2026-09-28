package ru.egor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class OrderManager {
    private final long orderLatencyNanos;
    private final List<OrderCommand> pendingCommands = new ArrayList<>();

    // В рамках тестового - мы УПРОЩАЕМ -
    // принимаем что в один момент вермени у стратегии всего один ордер на покупку и один на продажу..
    private final OrderState activeState = new OrderState(null, null);

    private long nextOrderId = 1L;

    public OrderManager(long orderLatencyNanos) {
        if (orderLatencyNanos < 0L) {
            throw new IllegalArgumentException("Latency не может быть отрицательной");
        }
        this.orderLatencyNanos = orderLatencyNanos;
    }

    // Применяет только команды, пришедшие строго раньше market event.
    // Равный timestamp остается pending и не может повлиять на это событие.
    public List<Order> applyCommandsBefore(long eventTimeNanos) {
        List<Order> activatedOrders = new ArrayList<>();
        Iterator<OrderCommand> iterator = pendingCommands.iterator();
        while (iterator.hasNext()) {
            OrderCommand command = iterator.next();
            if (command.effectiveTimeNanos < eventTimeNanos) {
                Order activatedOrder = applyCommand(command, activeState);
                if (activatedOrder != null) {
                    activatedOrders.add(activatedOrder);
                }
                iterator.remove();
            }
        }
        return activatedOrders;
    }

    // Сравнивает желаемые bid/ask с состоянием после всех команд в полете
    // и отправляет только недостающие place, cancel или cancel + place.
    public void reconcile(DesiredOrders desiredOrders, long decisionTimeNanos) {
        OrderState projectedState = projectOrderState();
        reconcileSide(OrderSide.BUY, desiredOrders.getBid(), projectedState.bid, decisionTimeNanos);
        reconcileSide(OrderSide.SELL, desiredOrders.getAsk(), projectedState.ask, decisionTimeNanos);
    }

    // Уменьшает остаток именно того активного ордера, который был исполнен.
    public void applyFill(Fill fill) {
        Order order = getActiveOrderInternal(fill.getSide());
        if (order == null || order.getId() != fill.getOrderId()) {
            throw new IllegalStateException("Fill относится к неактивному ордеру " + fill.getOrderId());
        }

        order.applyFill(fill.getSize());
        if (order.isFilled()) {
            activeState.set(fill.getSide(), null);
        }
    }

    public Order getActiveOrder(OrderSide side) {
        return getActiveOrderInternal(side);
    }

    public Order getProjectedOrder(OrderSide side) {
        Order order = projectOrderState().get(side);
        return order == null ? null : order.copy();
    }

    // Удаляет только что активированный ордер, если baseline не может честно смоделировать его как maker.
    public void rejectActiveOrder(Order order) {
        Order activeOrder = getActiveOrderInternal(order.getSide());
        if (activeOrder == null || activeOrder.getId() != order.getId()) {
            throw new IllegalStateException("Нельзя отклонить неактивный ордер " + order.getId());
        }

        activeState.set(order.getSide(), null);
    }

    public int getPendingCommandCount() {
        return pendingCommands.size();
    }

    // Считает худшие границы позиции: каждый еще возможный buy/sell может исполниться полностью.
    public PositionRange calculatePositionRange(double currentInventory) {
        Map<Long, PotentialOrder> possibleOrders = new HashMap<>();
        addPossibleOrder(possibleOrders, activeState.bid);
        addPossibleOrder(possibleOrders, activeState.ask);

        for (OrderCommand command : pendingCommands) {
            if (command.type == CommandType.PLACE) {
                addPossibleOrder(possibleOrders, command.order);
            }
            // Pending cancel не убирает риск: до применения cancel ордер еще может исполниться.
        }

        double minPosition = currentInventory;
        double maxPosition = currentInventory;
        for (PotentialOrder order : possibleOrders.values()) {
            if (order.side == OrderSide.BUY) {
                maxPosition += order.maxSize;
            } else {
                minPosition -= order.maxSize;
            }
        }
        return new PositionRange(minPosition, maxPosition);
    }

    // Для одной стороны выбирает минимальную команду: ничего, place, cancel или cancel + place.
    private void reconcileSide(
            OrderSide side,
            DesiredOrder desiredOrder,
            Order projectedOrder,
            long decisionTimeNanos
    ) {
        if (desiredOrder == null && projectedOrder == null) {
            return;
        }
        if (desiredOrder == null) {
            sendCancel(projectedOrder, decisionTimeNanos);
            return;
        }
        if (projectedOrder == null) {
            sendPlace(desiredOrder, decisionTimeNanos);
            return;
        }
        if (desiredOrder.matches(projectedOrder)) {
            return;
        }

        // Цена или желаемый размер изменились: новый id гарантирует новую очередь.
        // Cancel добавляется раньше place при одинаковых sent time и latency.
        sendCancel(projectedOrder, decisionTimeNanos);
        sendPlace(desiredOrder, decisionTimeNanos);
    }

    // Резервирует id будущего ордера сразу, чтобы следующие решения видели pending place.
    private void sendPlace(DesiredOrder desiredOrder, long sentTimeNanos) {
        Order order = new Order(
                nextOrderId++,
                desiredOrder.getSide(),
                desiredOrder.getPrice(),
                desiredOrder.getSize()
        );
        pendingCommands.add(OrderCommand.place(order, effectiveTime(sentTimeNanos)));
    }

    // Отмена адресуется конкретному id и не может случайно снять более новый ордер той же стороны.
    private void sendCancel(Order order, long sentTimeNanos) {
        pendingCommands.add(OrderCommand.cancel(
                order.getId(),
                order.getSide(),
                effectiveTime(sentTimeNanos)
        ));
    }

    // Проигрывает pending-команды на копии активных ордеров, не меняя реальное состояние биржи.
    private OrderState projectOrderState() {
        OrderState state = new OrderState(copy(activeState.bid), copy(activeState.ask));
        for (OrderCommand command : pendingCommands) {
            applyCommand(command, state);
        }
        return state;
    }

    // Одинаково применяет команду к реальному или проецируемому состоянию ордеров.
    private Order applyCommand(OrderCommand command, OrderState state) {
        Order currentOrder = state.get(command.side);

        if (command.type == CommandType.PLACE) {
            if (currentOrder != null) {
                throw new IllegalStateException("Нельзя разместить второй активный ордер на стороне " + command.side);
            }
            Order activatedOrder = command.order.copy();
            state.set(command.side, activatedOrder);
            return activatedOrder;
        }

        // Cancel становится no-op, если ордер успел полностью исполниться раньше команды.
        if (currentOrder == null || currentOrder.getId() != command.orderId) {
            return null;
        }
        state.set(command.side, null);
        return null;
    }

    // Gap watchdog удаляет биржевые ордера и все команды в полете, не затрагивая portfolio.
    public void resetOrdersForGap() {
        activeState.bid = null;
        activeState.ask = null;
        pendingCommands.clear();
    }

    private long effectiveTime(long sentTimeNanos) {
        return Math.addExact(sentTimeNanos, orderLatencyNanos);
    }

    private Order getActiveOrderInternal(OrderSide side) {
        return activeState.get(side);
    }

    private static Order copy(Order order) {
        return order == null ? null : order.copy();
    }

    // Учитывает один order id один раз и сохраняет его максимальный еще возможный объем.
    private static void addPossibleOrder(Map<Long, PotentialOrder> possibleOrders, Order order) {
        if (order == null) {
            return;
        }
        PotentialOrder existing = possibleOrders.get(order.getId());
        if (existing == null) {
            possibleOrders.put(order.getId(), new PotentialOrder(order.getSide(), order.getRemainingSize()));
        } else {
            existing.maxSize = Math.max(existing.maxSize, order.getRemainingSize());
        }
    }

    private enum CommandType {
        PLACE,
        CANCEL
    }

    private static final class OrderCommand {
        private final CommandType type;
        private final long orderId;
        private final OrderSide side;
        private final Order order;
        private final long effectiveTimeNanos;

        private OrderCommand(
                CommandType type,
                long orderId,
                OrderSide side,
                Order order,
                long effectiveTimeNanos
        ) {
            this.type = type;
            this.orderId = orderId;
            this.side = side;
            this.order = order;
            this.effectiveTimeNanos = effectiveTimeNanos;
        }

        private static OrderCommand place(Order order, long effectiveTimeNanos) {
            return new OrderCommand(
                    CommandType.PLACE,
                    order.getId(),
                    order.getSide(),
                    order,
                    effectiveTimeNanos
            );
        }

        private static OrderCommand cancel(
                long orderId,
                OrderSide side,
                long effectiveTimeNanos
        ) {
            return new OrderCommand(
                    CommandType.CANCEL,
                    orderId,
                    side,
                    null,
                    effectiveTimeNanos
            );
        }
    }

    private static final class OrderState {
        private Order bid;
        private Order ask;

        private OrderState(Order bid, Order ask) {
            this.bid = bid;
            this.ask = ask;
        }

        private Order get(OrderSide side) {
            return side == OrderSide.BUY ? bid : ask;
        }

        private void set(OrderSide side, Order order) {
            if (side == OrderSide.BUY) {
                bid = order;
            } else {
                ask = order;
            }
        }
    }

    private static final class PotentialOrder {
        private final OrderSide side;
        private double maxSize;

        private PotentialOrder(OrderSide side, double maxSize) {
            this.side = side;
            this.maxSize = maxSize;
        }
    }
}
