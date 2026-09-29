package ru.egor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class PriceWallDetector {
    private static final long PRESENCE_CREDIT_CAP_NANOS = 250_000_000L;

    private final double tickSize;
    private final double wallRatio;
    private final double minimumWallVolume;
    private final long minimumWallAgeNanos;
    private final double minimumPresenceShare;
    private final double minimumRetainedVolumeShare;
    private final long wallMissingGraceNanos;
    private final int minimumDistanceTicks;
    private final int maximumDistanceTicks;
    private final double maximumThinVolumeRatio;

    private final Map<Long, WallState> bidStates;
    private final Map<Long, WallState> askStates;
    private final List<Wall> currentBidWalls;
    private final List<Wall> currentAskWalls;

    PriceWallDetector(
            double tickSize,
            double wallRatio,
            double minimumWallVolume,
            long minimumWallAgeNanos,
            double minimumPresenceShare,
            double minimumRetainedVolumeShare,
            long wallMissingGraceNanos,
            int minimumDistanceTicks,
            int maximumDistanceTicks,
            double maximumThinVolumeRatio
    ) {
        this.tickSize = tickSize;
        this.wallRatio = wallRatio;
        this.minimumWallVolume = minimumWallVolume;
        this.minimumWallAgeNanos = minimumWallAgeNanos;
        this.minimumPresenceShare = minimumPresenceShare;
        this.minimumRetainedVolumeShare = minimumRetainedVolumeShare;
        this.wallMissingGraceNanos = wallMissingGraceNanos;
        this.minimumDistanceTicks = minimumDistanceTicks;
        this.maximumDistanceTicks = maximumDistanceTicks;
        this.maximumThinVolumeRatio = maximumThinVolumeRatio;
        this.bidStates = new HashMap<>();
        this.askStates = new HashMap<>();
        this.currentBidWalls = new ArrayList<>();
        this.currentAskWalls = new ArrayList<>();
    }

    // Обновляет историю каждого аномального уровня только по текущему и прошлым snapshots.
    void update(OrderBookEvent orderBook) {
        long nowNanos = orderBook.getTimestampNanos();
        currentBidWalls.clear();
        currentAskWalls.clear();

        updateSide(orderBook, OrderSide.BUY, bidStates, currentBidWalls, nowNanos);
        updateSide(orderBook, OrderSide.SELL, askStates, currentAskWalls, nowNanos);
        removeExpiredStates(bidStates, nowNanos);
        removeExpiredStates(askStates, nowNanos);
    }

    // Возвращает ближайшую подтвержденную стену по направлению движения цены.
    Wall findNearest(PriceInertiaWindow.Signal direction) {
        List<Wall> walls;
        if (direction == PriceInertiaWindow.Signal.UP) {
            walls = currentAskWalls;
        } else if (direction == PriceInertiaWindow.Signal.DOWN) {
            walls = currentBidWalls;
        } else {
            return null;
        }

        Wall nearest = null;
        for (Wall wall : walls) {
            if (nearest == null || wall.distanceTicks < nearest.distanceTicks) {
                nearest = wall;
            }
        }
        return nearest;
    }

    void clear() {
        bidStates.clear();
        askStates.clear();
        currentBidWalls.clear();
        currentAskWalls.clear();
    }

    private void updateSide(
            OrderBookEvent orderBook,
            OrderSide side,
            Map<Long, WallState> states,
            List<Wall> currentWalls,
            long nowNanos
    ) {
        double medianVolume = medianVolume(orderBook, side);
        if (!Double.isFinite(medianVolume) || medianVolume <= 0.0) {
            return;
        }

        double bestPrice = side == OrderSide.BUY
                ? orderBook.getBidPrice(0)
                : orderBook.getAskPrice(0);

        for (int level = 0; level < OrderBookEvent.LEVEL_COUNT; level++) {
            double price = price(orderBook, side, level);
            double volume = quantity(orderBook, side, level);
            if (!Double.isFinite(price)
                    || !Double.isFinite(volume)
                    || price <= 0.0
                    || volume < minimumWallVolume
                    || volume < medianVolume * wallRatio) {
                continue;
            }

            long key = priceKey(price);
            WallState state = updateState(states, key, volume, nowNanos);
            double ageNanos = nowNanos - state.firstSeenNanos;
            double presenceShare = ageNanos == 0.0
                    ? 0.0
                    : Math.min(1.0, state.presentNanos / ageNanos);

            boolean confirmed = ageNanos >= minimumWallAgeNanos
                    && presenceShare >= minimumPresenceShare;
            if (confirmed && state.confirmedVolume == null) {
                state.confirmedVolume = volume;
            }
            boolean retained = state.confirmedVolume != null
                    && volume >= state.confirmedVolume * minimumRetainedVolumeShare;
            if (!confirmed || !retained) {
                continue;
            }

            Integer distanceTicks = distanceTicks(side, bestPrice, price);
            if (distanceTicks == null
                    || distanceTicks < minimumDistanceTicks
                    || distanceTicks > maximumDistanceTicks) {
                continue;
            }

            double thinVolume = 0.0;
            for (int precedingLevel = 0; precedingLevel < level; precedingLevel++) {
                double precedingVolume = quantity(orderBook, side, precedingLevel);
                if (Double.isFinite(precedingVolume) && precedingVolume > 0.0) {
                    thinVolume += precedingVolume;
                }
            }
            if (thinVolume / volume > maximumThinVolumeRatio) {
                continue;
            }

            currentWalls.add(new Wall(price, distanceTicks));
        }
    }

    private WallState updateState(
            Map<Long, WallState> states,
            long key,
            double volume,
            long nowNanos
    ) {
        WallState state = states.get(key);
        if (state == null || nowNanos - state.lastSeenNanos > wallMissingGraceNanos) {
            state = new WallState(nowNanos);
            state.lastVolume = volume;
            states.put(key, state);
            return state;
        }

        long intervalNanos = nowNanos - state.lastSeenNanos;
        state.presentNanos += Math.min(intervalNanos, PRESENCE_CREDIT_CAP_NANOS);
        state.lastSeenNanos = nowNanos;
        state.lastVolume = volume;
        return state;
    }

    private void removeExpiredStates(Map<Long, WallState> states, long nowNanos) {
        Iterator<Map.Entry<Long, WallState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            WallState state = iterator.next().getValue();
            if (nowNanos - state.lastSeenNanos > wallMissingGraceNanos) {
                iterator.remove();
            }
        }
    }

    private Integer distanceTicks(OrderSide side, double bestPrice, double wallPrice) {
        double rawDistance = side == OrderSide.BUY
                ? (bestPrice - wallPrice) / tickSize
                : (wallPrice - bestPrice) / tickSize;
        int roundedDistance = (int) Math.round(rawDistance);
        if (Math.abs(rawDistance - roundedDistance) > 1e-5) {
            return null;
        }
        return roundedDistance;
    }

    private double medianVolume(OrderBookEvent orderBook, OrderSide side) {
        double[] volumes = new double[OrderBookEvent.LEVEL_COUNT];
        int count = 0;
        for (int level = 0; level < OrderBookEvent.LEVEL_COUNT; level++) {
            double volume = quantity(orderBook, side, level);
            if (Double.isFinite(volume) && volume > 0.0) {
                volumes[count] = volume;
                count++;
            }
        }
        if (count == 0) {
            return Double.NaN;
        }

        Arrays.sort(volumes, 0, count);
        int middle = count / 2;
        if (count % 2 == 1) {
            return volumes[middle];
        }
        return (volumes[middle - 1] + volumes[middle]) / 2.0;
    }

    private double price(OrderBookEvent orderBook, OrderSide side, int level) {
        return side == OrderSide.BUY
                ? orderBook.getBidPrice(level)
                : orderBook.getAskPrice(level);
    }

    private double quantity(OrderBookEvent orderBook, OrderSide side, int level) {
        return side == OrderSide.BUY
                ? orderBook.getBidQuantity(level)
                : orderBook.getAskQuantity(level);
    }

    private long priceKey(double price) {
        return Math.round(price / tickSize);
    }

    static final class Wall {
        private final double price;
        private final int distanceTicks;

        private Wall(double price, int distanceTicks) {
            this.price = price;
            this.distanceTicks = distanceTicks;
        }

        double getPrice() {
            return price;
        }

    }

    private static final class WallState {
        private final long firstSeenNanos;
        private long lastSeenNanos;
        private double presentNanos;
        private Double confirmedVolume;
        private double lastVolume;

        private WallState(long firstSeenNanos) {
            this.firstSeenNanos = firstSeenNanos;
            this.lastSeenNanos = firstSeenNanos;
            this.presentNanos = 0.0;
            this.confirmedVolume = null;
            this.lastVolume = 0.0;
        }
    }
}
