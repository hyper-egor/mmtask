package ru.egor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BacktestMetricsTest {

    @Test
    void separatesReduceOnlyActivityFromCommonOrderMetrics() {
        BacktestMetrics metrics = new BacktestMetrics();
        Portfolio portfolio = new Portfolio();
        long startedAt = nanos("2026-03-19T00:00:00Z");
        long filledAt = startedAt + 2_000_000_000L;
        Order reduceOrder = new Order(7L, OrderSide.SELL, 101.0, 0.4, true);
        Fill fill = new Fill(filledAt, 7L, OrderSide.SELL, 101.0, 0.4);

        metrics.beforeEvent(startedAt);
        metrics.afterEvent(new StatisticsSnapshot(portfolio, 100.0));
        metrics.onOrderActivated(reduceOrder, startedAt);
        metrics.onFill(fill, true, false, true);
        metrics.onOrderClosed(reduceOrder, filledAt);
        ReconciliationResult reconciliation = new ReconciliationResult();
        reconciliation.recordReplacement();
        reconciliation.recordReduceReplacement();
        metrics.onReconciliation(reconciliation);
        metrics.beforeEvent(filledAt);
        metrics.afterEvent(new StatisticsSnapshot(portfolio, 100.0));

        BacktestSummaryRow total = metrics.finish(filledAt).get(1);
        assertEquals(1L, total.getReduceActivatedOrders());
        assertEquals(1L, total.getReduceFillEvents());
        assertEquals(1L, total.getReduceFullyFilledOrders());
        assertEquals(0L, total.getReducePartiallyFilledOrders());
        assertEquals(0.4, total.getReduceFillVolume(), 1e-9);
        assertEquals(1L, total.getReduceReplacements());
        assertEquals(2_000.0, total.getAverageReduceOrderLifetimeMillis(), 1e-9);
    }

    @Test
    void dailyPnlAddsUpToTotalAcrossUtcBoundary() {
        BacktestMetrics metrics = new BacktestMetrics();
        Portfolio portfolio = new Portfolio();

        long dayOneStart = nanos("2026-03-19T00:00:00Z");
        metrics.beforeEvent(dayOneStart);
        metrics.afterEvent(new StatisticsSnapshot(portfolio, 100.0));

        long dayOneNoon = nanos("2026-03-19T12:00:00Z");
        metrics.beforeEvent(dayOneNoon);
        portfolio.applyFill(new Fill(dayOneNoon, 1L, OrderSide.BUY, 100.0, 1.0), 0.0);
        metrics.afterEvent(new StatisticsSnapshot(portfolio, 100.0));

        long dayTwoEvent = nanos("2026-03-20T01:00:00Z");
        metrics.beforeEvent(dayTwoEvent);
        portfolio.applyFill(new Fill(dayTwoEvent, 1L, OrderSide.SELL, 110.0, 1.0), 0.0);
        metrics.afterEvent(new StatisticsSnapshot(portfolio, 110.0));

        List<BacktestSummaryRow> rows = metrics.finish(dayTwoEvent);

        assertEquals(3, rows.size());
        assertEquals(0.0, rows.get(0).getDailyPnl());
        assertEquals(10.0, rows.get(1).getDailyPnl());
        assertEquals(10.0, rows.get(2).getSnapshot().getNetPnl());
        assertEquals(0.5, rows.get(0).getAverageAbsInventory(), 1e-9);
    }

    private long nanos(String timestamp) {
        Instant instant = Instant.parse(timestamp);
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
