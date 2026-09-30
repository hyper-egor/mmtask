package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseImbalanceStrategyTest {

    @Test
    void profitReduceFlagsWorkIndependently() {
        assertFalse(BaseImbalanceStrategy.shouldUseProfitReduceOrders(false, false, true));
        assertTrue(BaseImbalanceStrategy.shouldUseProfitReduceOrders(true, false, true));

        assertTrue(BaseImbalanceStrategy.shouldUseProfitReduceOrders(false, true, false));
        assertFalse(BaseImbalanceStrategy.shouldUseProfitReduceOrders(true, true, false));
    }

    @Test
    void allowsOnlyBidWhenRollingSignalIsBidDominant() {
        BaseImbalanceStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 100.0, 1.0, true));

        DesiredOrders desired = decide(strategy, orderBook(1L, 3.0, 1.0, 3.0, 1.0));

        assertNotNull(desired.getBid());
        assertNull(desired.getAsk());
        assertEquals(100.0, desired.getBid().getPrice());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void allowsOnlyAskWhenRollingSignalIsAskDominant() {
        BaseImbalanceStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 101.0, 1.0, false));

        DesiredOrders desired = decide(strategy, orderBook(1L, 1.0, 3.0, 1.0, 3.0));

        assertNull(desired.getBid());
        assertNotNull(desired.getAsk());
        assertEquals(101.0, desired.getAsk().getPrice());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
    }

    @Test
    void hysteresisKeepsBidWhileImbalanceStaysAboveSoftThreshold() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 3.0, 1.0, 3.0, 1.0));

        DesiredOrders desired = decide(strategy, orderBook(2L, 1.5, 2.0, 1.5, 2.0));

        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void hysteresisClearsBidAfterImbalanceFallsBelowSoftThreshold() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 3.0, 1.0, 3.0, 1.0));
        decide(strategy, orderBook(2L, 1.5, 2.0, 1.5, 2.0));

        DesiredOrders desired = decide(strategy, orderBook(3L, 1.0, 5.0, 1.0, 5.0));

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void strongOppositeSignalImmediatelySwitchesHysteresisDirection() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 3.0, 1.0, 3.0, 1.0));

        DesiredOrders desired = decide(strategy, orderBook(2L, 1.0, 6.0, 1.0, 6.0));

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void cancelsBothSidesWhenOnlyInstantSignalIsStrong() {
        BaseImbalanceStrategy strategy = strategy();

        DesiredOrders desired = decide(strategy, orderBook(1L, 1.2, 1.0, 1.2, 1.0));

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void removesSnapshotsOlderThanRollingWindow() {
        BaseImbalanceStrategy strategy = new BaseImbalanceStrategy(1.0, 5.0, 10L, 1.2, 5, 20);
        decide(strategy, orderBook(0L, 20.0, 1.0, 20.0, 1.0));
        strategy.onTrade(new TradeEvent(10L, 101.0, 1.0, false));

        DesiredOrders desired = decide(strategy, orderBook(11L, 1.0, 20.0, 1.0, 20.0));

        assertNull(desired.getBid());
        assertNotNull(desired.getAsk());
    }

    @Test
    void doesNotAddSameSnapshotMoreThanOnce() {
        BaseImbalanceStrategy strategy = strategy();
        OrderBookEvent firstBook = orderBook(1L, 10.0, 1.0, 10.0, 1.0);

        for (int i = 0; i < 10; i++) {
            decide(strategy, firstBook);
        }
        strategy.onTrade(new TradeEvent(1L, 101.0, 1.0, false));
        DesiredOrders desired = decide(strategy, orderBook(2L, 1.0, 40.0, 1.0, 40.0));

        assertNull(desired.getBid());
        assertNotNull(desired.getAsk());
    }

    @Test
    void hardLimitBlocksIncreasingBidButAllowsReducingAsk() {
        BaseImbalanceStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 100.0, 1.0, true));
        OrderBookEvent book = orderBook(1L, 3.0, 1.0, 3.0, 1.0);

        DesiredOrders desired = strategy.decide(
                book,
                true,
                4.5,
                100.0,
                new PositionRange(4.5, 4.5),
                null,
                null
        );

        assertNull(desired.getBid());
        assertNotNull(desired.getAsk());
        assertEquals(
                Math.min(BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE, 4.5),
                desired.getAsk().getSize(),
                1e-9
        );
        assertTrue(desired.getAsk().isReduceOnly());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void neutralSignalCreatesBoundedProfitableAskForLong() {
        BaseImbalanceStrategy strategy = strategy();

        DesiredOrders desired = strategy.decide(
                orderBook(1L, 100.0, 100.2, 1.0, 1.0, 1.0, 1.0),
                true,
                0.4,
                100.0,
                new PositionRange(0.4, 0.4),
                null,
                null
        );

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.SELL).getAction());
        assertEquals(BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE, desired.getAsk().getSize(), 1e-9);
        assertEquals(100.2, desired.getAsk().getPrice(), 1e-9);
        assertTrue(desired.getAsk().isReduceOnly());
    }

    @Test
    void neutralSignalDoesNotReduceLongBelowProfitThreshold() {
        BaseImbalanceStrategy strategy = strategy();
        double averageEntryPrice = 100.0;
        double minimumProfit = BaseImbalanceStrategy.PROFIT_REDUCE_MIN_TICKS
                * BaseImbalanceStrategy.TICK_SIZE;
        double bestAsk = averageEntryPrice + minimumProfit - BaseImbalanceStrategy.TICK_SIZE;

        DesiredOrders desired = strategy.decide(
                orderBook(1L, bestAsk - 0.1, bestAsk, 1.0, 1.0, 1.0, 1.0),
                true,
                0.4,
                averageEntryPrice,
                new PositionRange(0.4, 0.4),
                null,
                null
        );

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void neutralSignalCreatesBoundedProfitableBidForShort() {
        BaseImbalanceStrategy strategy = strategy();

        DesiredOrders desired = strategy.decide(
                orderBook(1L, 99.8, 99.9, 1.0, 1.0, 1.0, 1.0),
                true,
                -0.3,
                100.0,
                new PositionRange(-0.3, -0.3),
                null,
                null
        );

        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE, desired.getBid().getSize(), 1e-9);
        assertEquals(99.8, desired.getBid().getPrice(), 1e-9);
        assertTrue(desired.getBid().isReduceOnly());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void reducingSideOverridesOppositeSignalAndCannotRequestReversal() {
        BaseImbalanceStrategy strategy = strategy();

        DesiredOrders desired = strategy.decide(
                orderBook(1L, 99.9, 100.2, 1.0, 3.0, 1.0, 3.0),
                true,
                0.25,
                100.0,
                new PositionRange(0.25, 0.25),
                null,
                null
        );

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.SELL).getAction());
        assertEquals(BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE, desired.getAsk().getSize(), 1e-9);
        assertTrue(desired.getAsk().isReduceOnly());
    }

    @Test
    void existingProfitableReduceOrderKeepsItsPriceAndQueue() {
        BaseImbalanceStrategy strategy = strategy();
        Order projectedAsk = new Order(
                7L,
                OrderSide.SELL,
                100.3,
                BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE,
                true
        );

        DesiredOrders desired = strategy.decide(
                orderBook(1L, 100.1, 100.4, 1.0, 1.0, 1.0, 1.0),
                true,
                0.4,
                100.0,
                new PositionRange(0.0, 0.4),
                null,
                projectedAsk
        );

        assertEquals(OrderInstruction.Action.KEEP, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void reduceAskRepricesWhenItFallsTooFarBehindBest() {
        BaseImbalanceStrategy strategy = strategy();
        double orderPrice = 101.0;
        double bestAsk = orderPrice
                - BaseImbalanceStrategy.REDUCE_MAX_DISTANCE_TICKS * BaseImbalanceStrategy.TICK_SIZE;
        Order projectedAsk = new Order(
                7L,
                OrderSide.SELL,
                orderPrice,
                BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE,
                true
        );

        DesiredOrders desired = strategy.decide(
                orderBook(1L, bestAsk - 0.1, bestAsk, 1.0, 1.0, 1.0, 1.0),
                true,
                0.4,
                100.0,
                new PositionRange(0.0, 0.4),
                null,
                projectedAsk
        );

        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.SELL).getAction());
        assertEquals(bestAsk, desired.getAsk().getPrice(), 1e-9);
        assertTrue(desired.getAsk().isReduceOnly());
    }

    @Test
    void reduceBidRepricesWhenItFallsTooFarBehindBest() {
        BaseImbalanceStrategy strategy = strategy();
        double orderPrice = 99.0;
        double bestBid = orderPrice
                + BaseImbalanceStrategy.REDUCE_MAX_DISTANCE_TICKS * BaseImbalanceStrategy.TICK_SIZE;
        Order projectedBid = new Order(
                7L,
                OrderSide.BUY,
                orderPrice,
                BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE,
                true
        );

        DesiredOrders desired = strategy.decide(
                orderBook(1L, bestBid, bestBid + 0.1, 1.0, 1.0, 1.0, 1.0),
                true,
                -0.4,
                100.0,
                new PositionRange(-0.4, 0.0),
                projectedBid,
                null
        );

        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(bestBid, desired.getBid().getPrice(), 1e-9);
        assertTrue(desired.getBid().isReduceOnly());
    }

    @Test
    void reduceAskRepricesAfterTimeWithoutImprovement() {
        BaseImbalanceStrategy strategy = strategy();
        double orderPrice = 101.0;
        double bestAsk = orderPrice
                - BaseImbalanceStrategy.REDUCE_MAX_DISTANCE_TICKS
                * BaseImbalanceStrategy.TICK_SIZE / 2.0;
        Order projectedAsk = new Order(
                7L,
                OrderSide.SELL,
                orderPrice,
                BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE,
                true
        );

        DesiredOrders firstDecision = strategy.decide(
                orderBook(1L, bestAsk - 0.1, bestAsk, 1.0, 1.0, 1.0, 1.0),
                true,
                0.4,
                100.0,
                new PositionRange(0.0, 0.4),
                null,
                projectedAsk
        );
        DesiredOrders afterTimeout = strategy.decide(
                orderBook(
                        1L + BaseImbalanceStrategy.REDUCE_MAX_STALE_NANOS,
                        bestAsk - 0.1,
                        bestAsk,
                        1.0,
                        1.0,
                        1.0,
                        1.0
                ),
                true,
                0.4,
                100.0,
                new PositionRange(0.0, 0.4),
                null,
                projectedAsk
        );

        assertEquals(OrderInstruction.Action.KEEP, firstDecision.getInstruction(OrderSide.SELL).getAction());
        assertEquals(OrderInstruction.Action.QUOTE, afterTimeout.getInstruction(OrderSide.SELL).getAction());
        assertEquals(bestAsk, afterTimeout.getAsk().getPrice(), 1e-9);
    }

    @Test
    void bestMovementTowardReduceAskRestartsStaleTimeout() {
        BaseImbalanceStrategy strategy = strategy();
        double orderPrice = 101.0;
        double firstBestAsk = orderPrice
                - BaseImbalanceStrategy.REDUCE_MAX_DISTANCE_TICKS
                * BaseImbalanceStrategy.TICK_SIZE * 0.75;
        double closerBestAsk = orderPrice
                - BaseImbalanceStrategy.REDUCE_MAX_DISTANCE_TICKS
                * BaseImbalanceStrategy.TICK_SIZE * 0.5;
        Order projectedAsk = new Order(
                7L,
                OrderSide.SELL,
                orderPrice,
                BaseImbalanceStrategy.PROFIT_REDUCE_ORDER_SIZE,
                true
        );

        strategy.decide(
                orderBook(1L, firstBestAsk - 0.1, firstBestAsk, 1.0, 1.0, 1.0, 1.0),
                true,
                0.4,
                100.0,
                new PositionRange(0.0, 0.4),
                null,
                projectedAsk
        );
        DesiredOrders desired = strategy.decide(
                orderBook(
                        1L + BaseImbalanceStrategy.REDUCE_MAX_STALE_NANOS,
                        closerBestAsk - 0.1,
                        closerBestAsk,
                        1.0,
                        1.0,
                        1.0,
                        1.0
                ),
                true,
                0.4,
                100.0,
                new PositionRange(0.0, 0.4),
                null,
                projectedAsk
        );

        assertEquals(OrderInstruction.Action.KEEP, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void doesNotRequireAggressionConfirmationByDefault() {
        BaseImbalanceStrategy strategy = strategy();

        DesiredOrders desired = decide(strategy, orderBook(1L, 3.0, 1.0, 3.0, 1.0));

        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void stickyBidKeepsQueueDuringSmallMoveTowardOrder() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 100.0, 101.0, 3.0, 1.0, 3.0, 1.0));
        Order projectedBid = new Order(1L, OrderSide.BUY, 100.0, 1.0);

        DesiredOrders desired = decide(
                strategy,
                orderBook(2L, 99.9, 100.1, 3.0, 1.0, 3.0, 1.0),
                projectedBid,
                null
        );

        assertEquals(OrderInstruction.Action.KEEP, desired.getInstruction(OrderSide.BUY).getAction());
    }

    @Test
    void stickyBidRepricesAfterMaximumDistance() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 100.0, 101.0, 3.0, 1.0, 3.0, 1.0));
        Order projectedBid = new Order(1L, OrderSide.BUY, 100.0, 1.0);

        DesiredOrders desired = decide(
                strategy,
                orderBook(2L, 100.3, 100.4, 3.0, 1.0, 3.0, 1.0),
                projectedBid,
                null
        );

        assertEquals(OrderInstruction.Action.QUOTE, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(100.3, desired.getBid().getPrice(), 1e-9);
    }

    @Test
    void stickyBidRepricesAfterContinuousOffBestTimeout() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 100.0, 101.0, 3.0, 1.0, 3.0, 1.0));
        Order projectedBid = new Order(1L, OrderSide.BUY, 100.0, 1.0);

        DesiredOrders firstMove = decide(
                strategy,
                orderBook(2L, 100.1, 100.2, 3.0, 1.0, 3.0, 1.0),
                projectedBid,
                null
        );
        DesiredOrders afterTimeout = decide(
                strategy,
                orderBook(
                        2L + BaseImbalanceStrategy.STICKY_MAX_OFF_BEST_NANOS,
                        100.1,
                        100.2,
                        3.0,
                        1.0,
                        3.0,
                        1.0
                ),
                projectedBid,
                null
        );

        assertEquals(OrderInstruction.Action.KEEP, firstMove.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.QUOTE, afterTimeout.getInstruction(OrderSide.BUY).getAction());
    }

    @Test
    void stickyBidResetsTimeoutWhenBestReturnsToOrderPrice() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 100.0, 101.0, 3.0, 1.0, 3.0, 1.0));
        Order projectedBid = new Order(1L, OrderSide.BUY, 100.0, 1.0);

        decide(
                strategy,
                orderBook(2L, 100.1, 100.2, 3.0, 1.0, 3.0, 1.0),
                projectedBid,
                null
        );
        decide(
                strategy,
                orderBook(
                        2L + BaseImbalanceStrategy.STICKY_MAX_OFF_BEST_NANOS,
                        100.0,
                        100.1,
                        3.0,
                        1.0,
                        3.0,
                        1.0
                ),
                projectedBid,
                null
        );
        DesiredOrders secondMove = decide(
                strategy,
                orderBook(
                        3L + BaseImbalanceStrategy.STICKY_MAX_OFF_BEST_NANOS,
                        100.1,
                        100.2,
                        3.0,
                        1.0,
                        3.0,
                        1.0
                ),
                projectedBid,
                null
        );

        assertEquals(OrderInstruction.Action.KEEP, secondMove.getInstruction(OrderSide.BUY).getAction());
    }

    @Test
    void stickyBidCanStayNearHardLimitWithoutAddingReplacementRisk() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 100.0, 101.0, 3.0, 1.0, 3.0, 1.0));
        Order projectedBid = new Order(1L, OrderSide.BUY, 100.0, 1.0);

        DesiredOrders desired = strategy.decide(
                orderBook(2L, 100.1, 100.2, 3.0, 1.0, 3.0, 1.0),
                true,
                4.0,
                100.0,
                new PositionRange(4.0, 5.0),
                projectedBid,
                null
        );

        assertEquals(OrderInstruction.Action.KEEP, desired.getInstruction(OrderSide.BUY).getAction());
    }

    @Test
    void removesOldAggressionWhenNextTradeArrives() {
        BaseImbalanceStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 100.0, 10.0, true));
        strategy.onTrade(new TradeEvent(120_000_000_001L, 101.0, 1.0, false));

        DesiredOrders desired = decide(
                strategy,
                orderBook(120_000_000_002L, 1.0, 3.0, 1.0, 3.0)
        );

        assertNull(desired.getBid());
        assertNotNull(desired.getAsk());
    }

    private BaseImbalanceStrategy strategy() {
        return new BaseImbalanceStrategy(1.0, 5.0, 10_000L, 1.2, 5, 20);
    }

    private DesiredOrders decide(BaseImbalanceStrategy strategy, OrderBookEvent orderBook) {
        return decide(strategy, orderBook, null, null);
    }

    private DesiredOrders decide(
            BaseImbalanceStrategy strategy,
            OrderBookEvent orderBook,
            Order projectedBid,
            Order projectedAsk
    ) {
        double minPosition = projectedAsk == null ? 0.0 : -projectedAsk.getRemainingSize();
        double maxPosition = projectedBid == null ? 0.0 : projectedBid.getRemainingSize();
        return strategy.decide(
                orderBook,
                true,
                0.0,
                0.0,
                new PositionRange(minPosition, maxPosition),
                projectedBid,
                projectedAsk
        );
    }

    private OrderBookEvent orderBook(
            long timestampNanos,
            double bidTop5Quantity,
            double askTop5Quantity,
            double bidRemainingQuantity,
            double askRemainingQuantity
    ) {
        return orderBook(
                timestampNanos,
                100.0,
                101.0,
                bidTop5Quantity,
                askTop5Quantity,
                bidRemainingQuantity,
                askRemainingQuantity
        );
    }

    private OrderBookEvent orderBook(
            long timestampNanos,
            double bestBid,
            double bestAsk,
            double bidTop5Quantity,
            double askTop5Quantity,
            double bidRemainingQuantity,
            double askRemainingQuantity
    ) {
        double[] bidPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] bidQuantities = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askQuantities = new double[OrderBookEvent.LEVEL_COUNT];

        for (int level = 0; level < OrderBookEvent.LEVEL_COUNT; level++) {
            bidPrices[level] = bestBid - level;
            askPrices[level] = bestAsk + level;

            if (level < 5) {
                bidQuantities[level] = bidTop5Quantity;
                askQuantities[level] = askTop5Quantity;
            } else {
                bidQuantities[level] = bidRemainingQuantity;
                askQuantities[level] = askRemainingQuantity;
            }
        }

        return new OrderBookEvent(
                timestampNanos,
                bidPrices,
                askPrices,
                bidQuantities,
                askQuantities
        );
    }
}
