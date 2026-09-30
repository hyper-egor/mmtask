package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BacktestParametersTest {

    @Test
    void baselineContainsApprovedS0Values() {
        BacktestParameters parameters = BacktestParameters.baseline();

        assertEquals(BacktestParameters.DEFAULT_ORDER_SIZE, parameters.getOrderSize());
        assertEquals(BacktestParameters.DEFAULT_HARD_INVENTORY_LIMIT, parameters.getHardInventoryLimit());
        assertEquals(BacktestParameters.DEFAULT_ORDER_LATENCY_NANOS, parameters.getOrderLatencyNanos());
        assertEquals(BacktestParameters.DEFAULT_MAX_BOOK_AGE_NANOS, parameters.getMaxBookAgeNanos());
        assertEquals(BacktestParameters.DEFAULT_MAKER_FEE_BPS, parameters.getMakerFeeBps());
    }
}
