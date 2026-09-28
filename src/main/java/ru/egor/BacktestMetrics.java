package ru.egor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BacktestMetrics {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final MetricAccumulator total = new MetricAccumulator();
    private final List<BacktestSummaryRow> dailyRows = new ArrayList<>();
    private final Map<Long, Long> activeOrderStartedAt = new HashMap<>();
    private final Map<LocalDate, Long> staleNanosByDate = new HashMap<>();

    private MetricAccumulator currentDay;
    private LocalDate currentDate;
    private long lastEventTimeNanos = Long.MIN_VALUE;
    private double lastInventory;
    private StatisticsSnapshot lastSnapshot;
    private double previousDayEndEquity;

    // Переводит накопители к времени нового события, разделяя интервалы на границе UTC-дней.
    public void beforeEvent(long eventTimeNanos) {
        if (lastEventTimeNanos == Long.MIN_VALUE) {
            currentDate = toUtcDate(eventTimeNanos);
            currentDay = new MetricAccumulator();
            currentDay.initializeEquity(previousDayEndEquity);
            lastEventTimeNanos = eventTimeNanos;
            return;
        }
        if (eventTimeNanos < lastEventTimeNanos) {
            throw new IllegalStateException("Время событий пошло назад");
        }

        while (!toUtcDate(eventTimeNanos).equals(currentDate)) {
            long nextDayStart = startOfNextUtcDay(currentDate);
            addInventoryDuration(nextDayStart - lastEventTimeNanos);
            lastEventTimeNanos = nextDayStart;
            finishCurrentDay();

            currentDate = currentDate.plusDays(1L);
            currentDay = new MetricAccumulator();
            currentDay.initializeEquity(previousDayEndEquity);
        }

        addInventoryDuration(eventTimeNanos - lastEventTimeNanos);
        lastEventTimeNanos = eventTimeNanos;
    }

    // Сохраняет post-event состояние для PnL, drawdown и следующего временного интервала.
    public void afterEvent(StatisticsSnapshot snapshot) {
        lastSnapshot = snapshot;
        lastInventory = snapshot.getInventory();
        total.observeState(snapshot);
        currentDay.observeState(snapshot);
    }

    public void onOrderActivated(Order order, long eventTimeNanos) {
        total.activatedOrders++;
        currentDay.activatedOrders++;
        activeOrderStartedAt.put(order.getId(), eventTimeNanos);
    }

    // Закрывает время жизни ордера один раз: по full fill, cancel, reject, gap или концу replay.
    public void onOrderClosed(Order order, long eventTimeNanos) {
        Long startedAt = activeOrderStartedAt.remove(order.getId());
        if (startedAt == null) {
            return;
        }
        long lifetimeNanos = Math.max(0L, eventTimeNanos - startedAt);
        total.addOrderLifetime(lifetimeNanos);
        currentDay.addOrderLifetime(lifetimeNanos);
    }

    public void onFill(Fill fill, boolean fullFill, boolean partialFill) {
        total.addFill(fill, fullFill, partialFill);
        currentDay.addFill(fill, fullFill, partialFill);
    }

    public void onReconciliation(ReconciliationResult result) {
        total.cancelCommands += result.getCancelCommands();
        total.replacements += result.getReplacements();
        currentDay.cancelCommands += result.getCancelCommands();
        currentDay.replacements += result.getReplacements();
    }

    public void onGapReset() {
        total.gapResets++;
        currentDay.gapResets++;
    }

    // Раскладывает stale-интервал по календарным UTC-дням, даже если gap пересек полночь.
    public void onStalePeriod(long startedAtNanos, long endedAtNanos) {
        long cursor = startedAtNanos;
        while (cursor < endedAtNanos) {
            LocalDate date = toUtcDate(cursor);
            long boundary = Math.min(endedAtNanos, startOfNextUtcDay(date));
            long duration = boundary - cursor;
            staleNanosByDate.put(date, staleNanosByDate.getOrDefault(date, 0L) + duration);
            total.staleNanos += duration;
            cursor = boundary;
        }
    }

    // Завершает последний день и формирует строку TOTAL без искусственного закрытия позиции.
    public List<BacktestSummaryRow> finish(long endTimeNanos) {
        beforeEvent(endTimeNanos);
        finishCurrentDay();

        List<BacktestSummaryRow> result = new ArrayList<>(dailyRows);
        result.add(total.toRow("TOTAL", lastSnapshot, lastSnapshot.getNetPnl()));
        return result;
    }

    private void addInventoryDuration(long durationNanos) {
        if (durationNanos <= 0L) {
            return;
        }
        total.addInventoryDuration(lastInventory, durationNanos);
        currentDay.addInventoryDuration(lastInventory, durationNanos);
    }

    private void finishCurrentDay() {
        if (lastSnapshot == null) {
            return;
        }
        currentDay.staleNanos = staleNanosByDate.getOrDefault(currentDate, 0L);
        double dailyPnl = lastSnapshot.getNetPnl() - previousDayEndEquity;
        dailyRows.add(currentDay.toRow(currentDate.toString(), lastSnapshot, dailyPnl));
        previousDayEndEquity = lastSnapshot.getNetPnl();
    }

    private static LocalDate toUtcDate(long timestampNanos) {
        long seconds = Math.floorDiv(timestampNanos, NANOS_PER_SECOND);
        long nanos = Math.floorMod(timestampNanos, NANOS_PER_SECOND);
        return Instant.ofEpochSecond(seconds, nanos).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static long startOfNextUtcDay(LocalDate date) {
        long seconds = date.plusDays(1L).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return Math.multiplyExact(seconds, NANOS_PER_SECOND);
    }

    private static final class MetricAccumulator {
        private long inventoryDurationNanos;
        private double absoluteInventoryNanos;
        private double maxLongInventory;
        private double maxShortInventory;
        private double peakEquity;
        private double maxDrawdown;
        private long activatedOrders;
        private long fillEvents;
        private long fullyFilledOrders;
        private final Set<Long> partiallyFilledOrderIds = new HashSet<>();
        private double buyFillVolume;
        private double sellFillVolume;
        private long cancelCommands;
        private long replacements;
        private long closedOrderCount;
        private long totalOrderLifetimeNanos;
        private long gapResets;
        private long staleNanos;

        private void initializeEquity(double equity) {
            peakEquity = equity;
        }

        private void observeState(StatisticsSnapshot snapshot) {
            maxLongInventory = Math.max(maxLongInventory, snapshot.getInventory());
            maxShortInventory = Math.min(maxShortInventory, snapshot.getInventory());
            peakEquity = Math.max(peakEquity, snapshot.getNetPnl());
            maxDrawdown = Math.max(maxDrawdown, peakEquity - snapshot.getNetPnl());
        }

        private void addInventoryDuration(double inventory, long durationNanos) {
            inventoryDurationNanos += durationNanos;
            absoluteInventoryNanos += Math.abs(inventory) * durationNanos;
        }

        private void addFill(Fill fill, boolean fullFill, boolean partialFill) {
            fillEvents++;
            if (fill.getSide() == OrderSide.BUY) {
                buyFillVolume += fill.getSize();
            } else {
                sellFillVolume += fill.getSize();
            }
            if (fullFill) {
                fullyFilledOrders++;
            }
            if (partialFill) {
                partiallyFilledOrderIds.add(fill.getOrderId());
            }
        }

        private void addOrderLifetime(long lifetimeNanos) {
            closedOrderCount++;
            totalOrderLifetimeNanos += lifetimeNanos;
        }

        private BacktestSummaryRow toRow(String period, StatisticsSnapshot snapshot, double dailyPnl) {
            double averageAbsInventory = inventoryDurationNanos == 0L
                    ? Math.abs(snapshot.getInventory())
                    : absoluteInventoryNanos / inventoryDurationNanos;
            double averageLifetimeMillis = closedOrderCount == 0L
                    ? 0.0
                    : totalOrderLifetimeNanos / 1_000_000.0 / closedOrderCount;

            return new BacktestSummaryRow(
                    period,
                    snapshot,
                    dailyPnl,
                    maxLongInventory,
                    maxShortInventory,
                    averageAbsInventory,
                    maxDrawdown,
                    activatedOrders,
                    fillEvents,
                    fullyFilledOrders,
                    partiallyFilledOrderIds.size(),
                    buyFillVolume,
                    sellFillVolume,
                    cancelCommands,
                    replacements,
                    averageLifetimeMillis,
                    gapResets,
                    staleNanos / 1_000_000_000.0
            );
        }
    }
}
