package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MMATemaTest {

    @Test
    void sumsValuesInsideWindowAndRemovesOldValues() {
        MMATema window = new MMATema(10L);

        window.addStat(2.0, 0L);
        window.addStat(3.0, 10L);
        assertEquals(5.0, window.getSum(), 1e-9);

        window.addStat(4.0, 11L);
        assertEquals(7.0, window.getSum(), 1e-9);
    }

    @Test
    void clearStartsWindowFromZero() {
        MMATema window = new MMATema(10L);
        window.addStat(5.0, 1L);

        window.clear();

        assertEquals(0.0, window.getSum(), 1e-9);
        window.addStat(2.0, 0L);
        assertEquals(2.0, window.getSum(), 1e-9);
    }

    @Test
    void rejectsEventsWithDecreasingTime() {
        MMATema window = new MMATema(10L);
        window.addStat(1.0, 2L);

        assertThrows(IllegalArgumentException.class, () -> window.addStat(1.0, 1L));
    }
}
