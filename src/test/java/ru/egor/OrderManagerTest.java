package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderManagerTest {

    @Test
    void commandWithEqualTimestampDoesNotBecomeActive() {
        OrderManager manager = new OrderManager(10L);
        DesiredOrders desired = new DesiredOrders(
                new DesiredOrder(OrderSide.BUY, 100.0, 2.0),
                null
        );

        manager.reconcile(desired, 100L);
        manager.applyCommandsBefore(110L);

        assertNull(manager.getActiveOrder(OrderSide.BUY));
        assertEquals(1, manager.getPendingCommandCount());

        manager.applyCommandsBefore(111L);

        assertEquals(100.0, manager.getActiveOrder(OrderSide.BUY).getPrice());
        assertEquals(0, manager.getPendingCommandCount());
    }

    @Test
    void pendingPlaceIsNotDuplicatedByNextDecision() {
        OrderManager manager = new OrderManager(10L);
        DesiredOrders desired = new DesiredOrders(
                new DesiredOrder(OrderSide.BUY, 100.0, 2.0),
                null
        );

        manager.reconcile(desired, 100L);
        manager.reconcile(desired, 105L);

        assertEquals(1, manager.getPendingCommandCount());
        assertEquals(2.0, manager.getProjectedOrder(OrderSide.BUY).getRemainingSize());
    }

    @Test
    void priceChangeCreatesCancelBeforeNewPlace() {
        OrderManager manager = new OrderManager(10L);
        manager.reconcile(
                new DesiredOrders(new DesiredOrder(OrderSide.BUY, 100.0, 2.0), null),
                100L
        );
        manager.applyCommandsBefore(111L);

        manager.reconcile(
                new DesiredOrders(new DesiredOrder(OrderSide.BUY, 99.0, 3.0), null),
                120L
        );

        assertEquals(2, manager.getPendingCommandCount());
        assertEquals(99.0, manager.getProjectedOrder(OrderSide.BUY).getPrice());

        manager.applyCommandsBefore(131L);

        assertEquals(99.0, manager.getActiveOrder(OrderSide.BUY).getPrice());
        assertEquals(3.0, manager.getActiveOrder(OrderSide.BUY).getRemainingSize());
    }

    @Test
    void countsReplacementBetweenReduceOnlyOrdersSeparately() {
        OrderManager manager = new OrderManager(10L);
        manager.reconcile(
                new DesiredOrders(
                        new DesiredOrder(OrderSide.BUY, 100.0, 2.0, true),
                        null
                ),
                100L
        );
        manager.applyCommandsBefore(111L);

        ReconciliationResult result = manager.reconcile(
                new DesiredOrders(
                        new DesiredOrder(OrderSide.BUY, 101.0, 2.0, true),
                        null
                ),
                120L
        );

        assertEquals(1, result.getReplacements());
        assertEquals(1, result.getReduceReplacements());
    }

    @Test
    void riskRangeIncludesActiveAndPendingOrders() {
        OrderManager manager = new OrderManager(10L);
        DesiredOrders bothSides = new DesiredOrders(
                new DesiredOrder(OrderSide.BUY, 100.0, 10.0),
                new DesiredOrder(OrderSide.SELL, 101.0, 20.0)
        );

        manager.reconcile(bothSides, 100L);
        PositionRange range = manager.calculatePositionRange(5.0);

        assertEquals(-15.0, range.getMinPosition());
        assertEquals(15.0, range.getMaxPosition());
    }

    @Test
    void sizeChangeCreatesNewOrderAndNewQueue() {
        OrderManager manager = new OrderManager(10L);
        manager.reconcile(
                new DesiredOrders(new DesiredOrder(OrderSide.BUY, 100.0, 2.0), null),
                100L
        );
        manager.applyCommandsBefore(111L);
        long orderId = manager.getActiveOrder(OrderSide.BUY).getId();
        manager.getActiveOrder(OrderSide.BUY).initializeQueue(7.0);

        manager.reconcile(
                new DesiredOrders(new DesiredOrder(OrderSide.BUY, 100.0, 5.0), null),
                120L
        );
        manager.applyCommandsBefore(131L);

        Order replacement = manager.getActiveOrder(OrderSide.BUY);
        assertEquals(orderId + 1L, replacement.getId());
        assertEquals(5.0, manager.getActiveOrder(OrderSide.BUY).getRemainingSize());
        assertEquals(false, replacement.isQueueInitialized());
    }

    @Test
    void partialAndFullFillChangeActiveOrder() {
        OrderManager manager = new OrderManager(10L);
        manager.reconcile(
                new DesiredOrders(new DesiredOrder(OrderSide.BUY, 100.0, 2.0), null),
                100L
        );
        manager.applyCommandsBefore(111L);
        long orderId = manager.getActiveOrder(OrderSide.BUY).getId();

        manager.applyFill(new Fill(120L, orderId, OrderSide.BUY, 100.0, 0.5));
        assertEquals(1.5, manager.getActiveOrder(OrderSide.BUY).getRemainingSize());

        manager.applyFill(new Fill(121L, orderId, OrderSide.BUY, 100.0, 1.5));
        assertNull(manager.getActiveOrder(OrderSide.BUY));
    }

    @Test
    void pendingCancelKeepsOldOrderInRiskAndAllowsFill() {
        OrderManager manager = new OrderManager(10L);
        manager.reconcile(
                new DesiredOrders(new DesiredOrder(OrderSide.BUY, 100.0, 2.0), null),
                100L
        );
        manager.applyCommandsBefore(111L);
        Order oldOrder = manager.getActiveOrder(OrderSide.BUY);

        manager.reconcile(DesiredOrders.empty(), 120L);

        assertEquals(2.0, manager.calculatePositionRange(0.0).getMaxPosition());
        manager.applyFill(new Fill(125L, oldOrder.getId(), OrderSide.BUY, 100.0, 2.0));
        assertNull(manager.getActiveOrder(OrderSide.BUY));

        manager.applyCommandsBefore(131L);
        assertNull(manager.getActiveOrder(OrderSide.BUY));
    }

    @Test
    void gapResetRemovesActiveAndPendingOrders() {
        OrderManager manager = new OrderManager(10L);
        manager.reconcile(
                new DesiredOrders(
                        new DesiredOrder(OrderSide.BUY, 100.0, 2.0),
                        new DesiredOrder(OrderSide.SELL, 101.0, 2.0)
                ),
                100L
        );
        manager.applyCommandsBefore(111L);
        manager.reconcile(DesiredOrders.empty(), 120L);

        manager.resetOrdersForGap();

        assertNull(manager.getActiveOrder(OrderSide.BUY));
        assertNull(manager.getActiveOrder(OrderSide.SELL));
        assertEquals(0, manager.getPendingCommandCount());
    }

    @Test
    void keepInstructionDoesNotPlaceOrCancelOrders() {
        OrderManager manager = new OrderManager(10L);
        manager.reconcile(
                new DesiredOrders(new DesiredOrder(OrderSide.BUY, 100.0, 2.0), null),
                100L
        );
        manager.applyCommandsBefore(111L);
        long activeBidId = manager.getActiveOrder(OrderSide.BUY).getId();

        manager.reconcile(DesiredOrders.keepBoth(), 120L);

        assertEquals(activeBidId, manager.getActiveOrder(OrderSide.BUY).getId());
        assertNull(manager.getProjectedOrder(OrderSide.SELL));
        assertEquals(0, manager.getPendingCommandCount());
    }
}
