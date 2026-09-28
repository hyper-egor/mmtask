package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderTest {

    @Test
    void queueIsInitializedOnceAndConsumedBeforeOrder() {
        Order order = new Order(1L, OrderSide.BUY, 100.0, 3.0);

        assertFalse(order.isQueueInitialized());
        assertThrows(IllegalStateException.class, order::getQueueAhead);

        order.initializeQueue(5.0);

        assertEquals(0.0, order.consumeQueue(2.0));
        assertEquals(3.0, order.getQueueAhead());
        assertEquals(1.0, order.consumeQueue(4.0));
        assertEquals(0.0, order.getQueueAhead());
        assertThrows(IllegalStateException.class, () -> order.initializeQueue(1.0));
    }

    @Test
    void copyPreservesRemainingSizeAndQueue() {
        Order order = new Order(1L, OrderSide.SELL, 101.0, 3.0);
        order.initializeQueue(5.0);
        order.consumeQueue(2.0);
        order.applyFill(1.0);

        Order copy = order.copy();

        assertTrue(copy.isQueueInitialized());
        assertEquals(3.0, copy.getQueueAhead());
        assertEquals(2.0, copy.getRemainingSize());
        assertEquals(3.0, copy.getOriginalSize());
    }
}
