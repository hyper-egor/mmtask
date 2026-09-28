package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BacktestParametersTest {

    @Test
    void baselineContainsApprovedS0Values() {
        BacktestParameters parameters = BacktestParameters.baseline();

        assertEquals(1.0, parameters.getOrderSize());
        assertEquals(5.0, parameters.getHardInventoryLimit());
        assertEquals(10_000_000L, parameters.getOrderLatencyNanos());
        assertEquals(1_000_000_000L, parameters.getMaxBookAgeNanos());
        assertEquals(0.0, parameters.getMakerFeeBps());
    }
}
