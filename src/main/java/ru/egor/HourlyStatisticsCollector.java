package ru.egor;

import java.util.ArrayList;
import java.util.List;

public final class HourlyStatisticsCollector {
    private static final long NANOS_PER_HOUR = 3_600_000_000_000L;

    private final List<HourlyStatisticsRow> rows = new ArrayList<>();
    private long currentHourEndNanos = Long.MIN_VALUE;
    private StatisticsSnapshot lastSnapshot;
    private long fillsInHour;
    private double tradedVolumeInHour;
    private long reduceFillsInHour;
    private double reduceVolumeInHour;
    private long gapResetsInHour;

    // Перед новым событием закрывает прошедшие часы последним состоянием, известным до события.
    public void beforeEvent(long eventTimeNanos) {
        if (currentHourEndNanos == Long.MIN_VALUE) {
            long hourStart = Math.floorDiv(eventTimeNanos, NANOS_PER_HOUR) * NANOS_PER_HOUR;
            currentHourEndNanos = Math.addExact(hourStart, NANOS_PER_HOUR);
            return;
        }

        while (eventTimeNanos >= currentHourEndNanos && lastSnapshot != null) {
            saveCurrentHour();
            currentHourEndNanos = Math.addExact(currentHourEndNanos, NANOS_PER_HOUR);
        }
    }

    // Сохраняет состояние после полного шага replay для будущей часовой границы.
    public void afterEvent(StatisticsSnapshot snapshot) {
        lastSnapshot = snapshot;
    }

    public void onFill(Fill fill, boolean reduceOnly) {
        fillsInHour++;
        tradedVolumeInHour += fill.getSize();
        if (reduceOnly) {
            reduceFillsInHour++;
            reduceVolumeInHour += fill.getSize();
        }
    }

    public void onGapReset() {
        gapResetsInHour++;
    }

    // В конце replay сохраняет последний неполный календарный час как точку на его правой границе.
    public void finish() {
        if (lastSnapshot != null) {
            saveCurrentHour();
        }
    }

    public List<HourlyStatisticsRow> getRows() {
        return List.copyOf(rows);
    }

    private void saveCurrentHour() {
        rows.add(new HourlyStatisticsRow(
                currentHourEndNanos,
                lastSnapshot,
                fillsInHour,
                tradedVolumeInHour,
                reduceFillsInHour,
                reduceVolumeInHour,
                gapResetsInHour
        ));
        fillsInHour = 0L;
        tradedVolumeInHour = 0.0;
        reduceFillsInHour = 0L;
        reduceVolumeInHour = 0.0;
        gapResetsInHour = 0L;
    }
}
