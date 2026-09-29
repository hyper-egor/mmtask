package ru.egor;

import java.util.ArrayDeque;
import java.util.Deque;

public final class MMATema {
    private final Deque<TimedValue> stat;
    private final long timeframeNanos;
    private double totalAmount;

    public MMATema(long timeframeNanos) {
        if (timeframeNanos <= 0L) {
            throw new IllegalArgumentException("Длина окна должна быть положительной");
        }

        this.timeframeNanos = timeframeNanos;
        this.stat = new ArrayDeque<>();
        this.totalAmount = 0.0;
    }

    // Добавляет значение в окно и удаляет все обновления, которые уже старше timeframe.
    // Каждый snapshot имеет одинаковый вес, взвешивание по времени намеренно не используется.
    public void addStat(double value, long timeNanos) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Значение окна должно быть конечным и неотрицательным");
        }

        TimedValue last = stat.peekLast();
        if (last != null && timeNanos < last.timeNanos) {
            throw new IllegalArgumentException("Время нового значения не может быть меньше предыдущего");
        }

        stat.addLast(new TimedValue(value, timeNanos));
        totalAmount += value;

        while (!stat.isEmpty()) {
            TimedValue first = stat.peekFirst();
            if (timeNanos - first.timeNanos > timeframeNanos) {
                totalAmount -= first.value;
                stat.removeFirst();
            } else {
                break;
            }
        }
    }

    public double getSum() {
        return totalAmount;
    }

    // Очищение нужно только при повторном replay, когда время событий начинается заново.
    public void clear() {
        stat.clear();
        totalAmount = 0.0;
    }

    private static final class TimedValue {
        private final double value;
        private final long timeNanos;

        private TimedValue(double value, long timeNanos) {
            this.value = value;
            this.timeNanos = timeNanos;
        }
    }
}
