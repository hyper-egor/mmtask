package ru.egor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HourlyStatisticsCollectorTest {

    @Test
    void createsOneRowPerUtcHourWithoutUsingFutureState() {
        HourlyStatisticsCollector collector = new HourlyStatisticsCollector();
        Portfolio portfolio = new Portfolio();
        long firstEvent = nanos("2026-03-19T00:10:00Z");

        collector.beforeEvent(firstEvent);
        collector.afterEvent(new StatisticsSnapshot(portfolio, 100.0));
        collector.onFill(new Fill(firstEvent, 1L, OrderSide.BUY, 100.0, 0.5), true);

        long secondEvent = nanos("2026-03-19T01:05:00Z");
        collector.beforeEvent(secondEvent);
        portfolio.applyFill(new Fill(secondEvent, 1L, OrderSide.BUY, 100.0, 1.0), 0.0);
        collector.afterEvent(new StatisticsSnapshot(portfolio, 101.0));
        collector.finish();

        List<HourlyStatisticsRow> rows = collector.getRows();
        assertEquals(2, rows.size());
        assertEquals(nanos("2026-03-19T01:00:00Z"), rows.get(0).getHourEndNanos());
        assertEquals(0.0, rows.get(0).getSnapshot().getInventory());
        assertEquals(1L, rows.get(0).getFillsInHour());
        assertEquals(1L, rows.get(0).getReduceFillsInHour());
        assertEquals(0.5, rows.get(0).getReduceVolumeInHour());
        assertEquals(nanos("2026-03-19T02:00:00Z"), rows.get(1).getHourEndNanos());
        assertEquals(1.0, rows.get(1).getSnapshot().getInventory());
    }

    private long nanos(String timestamp) {
        Instant instant = Instant.parse(timestamp);
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
