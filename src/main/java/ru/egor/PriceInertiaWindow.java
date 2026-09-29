package ru.egor;

import java.util.ArrayDeque;
import java.util.Deque;

public final class PriceInertiaWindow {
    public enum Signal {
        UP,
        DOWN,
        FLAT
    }

    private final Deque<PricePoint> points;
    private final long timeframeNanos;
    private final double tickSize;
    private final double minimumMoveTicks;
    private final double minimumWindowCoverageShare;

    public PriceInertiaWindow(
            long timeframeNanos,
            double tickSize,
            double minimumMoveTicks,
            double minimumWindowCoverageShare
    ) {
        if (timeframeNanos <= 0L) {
            throw new IllegalArgumentException("Длина окна инерции должна быть положительной");
        }
        if (!Double.isFinite(tickSize) || tickSize <= 0.0) {
            throw new IllegalArgumentException("Tick size должен быть конечным и положительным");
        }
        if (!Double.isFinite(minimumMoveTicks) || minimumMoveTicks <= 0.0) {
            throw new IllegalArgumentException("Порог движения должен быть конечным и положительным");
        }
        if (!Double.isFinite(minimumWindowCoverageShare)
                || minimumWindowCoverageShare <= 0.0
                || minimumWindowCoverageShare > 1.0) {
            throw new IllegalArgumentException("Заполнение окна должно быть в диапазоне (0; 1]");
        }

        this.points = new ArrayDeque<>();
        this.timeframeNanos = timeframeNanos;
        this.tickSize = tickSize;
        this.minimumMoveTicks = minimumMoveTicks;
        this.minimumWindowCoverageShare = minimumWindowCoverageShare;
    }

    // Добавляет новый top of book и оставляет только цены внутри заданного окна.
    public void addSnapshot(double bestBid, double bestAsk, long timeNanos) {
        validateTopOfBook(bestBid, bestAsk);

        PricePoint last = points.peekLast();
        if (last != null && timeNanos < last.timeNanos) {
            clear();
        }

        points.addLast(new PricePoint(timeNanos, bestBid, bestAsk));
        while (!points.isEmpty()) {
            PricePoint first = points.peekFirst();
            if (timeNanos - first.timeNanos > timeframeNanos) {
                points.removeFirst();
            } else {
                break;
            }
        }
    }

    // Сравнивает текущую цену с самой старой ценой окна и возвращает направление движения.
    public Signal getSignal() {
        if (points.size() < 2) {
            return Signal.FLAT;
        }

        PricePoint first = points.peekFirst();
        PricePoint last = points.peekLast();
        double minimumCoveredNanos = timeframeNanos * minimumWindowCoverageShare;
        if (last.timeNanos - first.timeNanos < minimumCoveredNanos) {
            return Signal.FLAT;
        }

        double upMoveTicks = (last.bestAsk - first.bestAsk) / tickSize;
        double downMoveTicks = (first.bestBid - last.bestBid) / tickSize;

        boolean movedUp = upMoveTicks >= minimumMoveTicks;
        boolean movedDown = downMoveTicks >= minimumMoveTicks;
        if (movedUp && movedDown) {
            int comparison = Double.compare(upMoveTicks, downMoveTicks);
            if (comparison > 0) {
                return Signal.UP;
            }
            if (comparison < 0) {
                return Signal.DOWN;
            }
            return Signal.FLAT;
        }
        if (movedUp) {
            return Signal.UP;
        }
        if (movedDown) {
            return Signal.DOWN;
        }
        return Signal.FLAT;
    }

    public void clear() {
        points.clear();
    }

    private void validateTopOfBook(double bestBid, double bestAsk) {
        if (!Double.isFinite(bestBid)
                || !Double.isFinite(bestAsk)
                || bestBid <= 0.0
                || bestAsk <= bestBid) {
            throw new IllegalArgumentException("Для окна инерции нужен корректный top of book");
        }
    }

    private static final class PricePoint {
        private final long timeNanos;
        private final double bestBid;
        private final double bestAsk;

        private PricePoint(long timeNanos, double bestBid, double bestAsk) {
            this.timeNanos = timeNanos;
            this.bestBid = bestBid;
            this.bestAsk = bestAsk;
        }
    }
}
