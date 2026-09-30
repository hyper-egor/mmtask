package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestRunnerFillTest {

    @Test
    void tradeAtEffectiveTimeDoesNotFillAndQueueUsesLastSnapshot() {
        BacktestRunner runner = runner(10L, 1_000L);
        runner.processEvent(orderBook(100L, 100.0, 5.0, 101.0, 6.0));
        quoteBid(runner, 100L, 3.0);

        runner.processEvent(new TradeEvent(110L, 100.0, 20.0, false));

        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertEquals(0.0, runner.getInventory());

        runner.processEvent(new TradeEvent(111L, 100.0, 2.0, false));

        Order order = runner.getOrderManager().getActiveOrder(OrderSide.BUY);
        assertEquals(3.0, order.getQueueAhead());
        assertEquals(3.0, order.getRemainingSize());
    }

    @Test
    void wrongSideAndPriceDoNotChangeQueue() {
        BacktestRunner runner = activeBidWithQueueFive();

        runner.processEvent(new TradeEvent(112L, 100.0, 2.0, true));
        runner.processEvent(new TradeEvent(113L, 100.1, 2.0, false));

        Order order = runner.getOrderManager().getActiveOrder(OrderSide.BUY);
        assertEquals(5.0, order.getQueueAhead());
        assertEquals(3.0, order.getRemainingSize());
        assertEquals(0.0, runner.getInventory());
    }

    @Test
    void tradeConsumesQueueThenCreatesPartialAndFullFill() {
        BacktestRunner runner = activeBidWithQueueFive();

        runner.processEvent(new TradeEvent(112L, 100.0, 4.0, false));
        assertEquals(1.0, runner.getOrderManager().getActiveOrder(OrderSide.BUY).getQueueAhead());
        assertEquals(0.0, runner.getInventory());

        runner.processEvent(new TradeEvent(113L, 99.9, 3.0, false));
        Order partiallyFilled = runner.getOrderManager().getActiveOrder(OrderSide.BUY);
        assertEquals(1.0, partiallyFilled.getRemainingSize());
        assertEquals(2.0, runner.getInventory());

        // Большой trade-through исполняет только оставшийся объем ордера.
        runner.processEvent(new TradeEvent(114L, 99.0, 20.0, false));
        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertEquals(3.0, runner.getInventory());
        assertEquals(-300.0, runner.getCash());
    }

    @Test
    void pendingCancelStillAllowsOldOrderToFill() {
        BacktestRunner runner = activeBidWithQueueFive();
        Order oldOrder = runner.getOrderManager().getActiveOrder(OrderSide.BUY);
        oldOrder.consumeQueue(5.0);

        runner.getOrderManager().reconcile(DesiredOrders.empty(), 112L);
        runner.processEvent(new TradeEvent(113L, 100.0, 1.0, false));

        assertEquals(1.0, runner.getInventory());
        assertEquals(2.0, runner.getOrderManager().getActiveOrder(OrderSide.BUY).getRemainingSize());
    }

    @Test
    void replacementDoesNotOverlapAndGetsNewQueue() {
        BacktestRunner runner = activeBidWithQueueFive();
        Order oldOrder = runner.getOrderManager().getActiveOrder(OrderSide.BUY);
        oldOrder.consumeQueue(2.0);

        DesiredOrders replacement = new DesiredOrders(
                new DesiredOrder(OrderSide.BUY, 99.0, 4.0),
                null
        );
        runner.getOrderManager().reconcile(replacement, 112L);
        runner.processEvent(orderBook(121L, 99.0, 8.0, 100.0, 7.0));
        runner.processEvent(new FundingRateEvent(123L, 0.0));

        Order newOrder = runner.getOrderManager().getActiveOrder(OrderSide.BUY);
        assertEquals(oldOrder.getId() + 1L, newOrder.getId());
        assertEquals(8.0, newOrder.getQueueAhead());
        assertEquals(4.0, newOrder.getRemainingSize());
    }

    @Test
    void sellQueueIncludesOnlyVolumeAtOrderPrice() {
        BacktestRunner runner = runner(10L, 1_000L);
        runner.processEvent(orderBook(
                100L,
                new double[] {100.0},
                new double[] {101.0, 102.0},
                new double[] {5.0},
                new double[] {6.0, 4.0}
        ));
        DesiredOrders desired = new DesiredOrders(
                null,
                new DesiredOrder(OrderSide.SELL, 102.0, 3.0)
        );
        runner.getOrderManager().reconcile(desired, 100L);

        runner.processEvent(new FundingRateEvent(111L, 0.0));

        Order order = runner.getOrderManager().getActiveOrder(OrderSide.SELL);
        assertEquals(4.0, order.getQueueAhead());

        runner.processEvent(new TradeEvent(112L, 102.0, 3.0, true));
        assertEquals(1.0, order.getQueueAhead());
        assertEquals(0.0, runner.getInventory());

        runner.processEvent(new TradeEvent(113L, 102.0, 2.0, true));
        assertEquals(2.0, runner.getOrderManager().getActiveOrder(OrderSide.SELL).getRemainingSize());
        assertEquals(-1.0, runner.getInventory());
    }

    @Test
    void buyQueueIncludesOnlyVolumeAtOrderPrice() {
        BacktestRunner runner = runner(10L, 1_000L);
        runner.processEvent(orderBook(
                100L,
                new double[] {100.0, 99.0},
                new double[] {101.0},
                new double[] {5.0, 4.0},
                new double[] {6.0}
        ));
        DesiredOrders desired = new DesiredOrders(
                new DesiredOrder(OrderSide.BUY, 99.0, 3.0),
                null
        );
        runner.getOrderManager().reconcile(desired, 100L);

        runner.processEvent(new FundingRateEvent(111L, 0.0));

        Order order = runner.getOrderManager().getActiveOrder(OrderSide.BUY);
        assertEquals(4.0, order.getQueueAhead());

        runner.processEvent(new TradeEvent(112L, 99.0, 3.0, false));
        assertEquals(1.0, order.getQueueAhead());
        assertEquals(0.0, runner.getInventory());

        runner.processEvent(new TradeEvent(113L, 99.0, 2.0, false));
        assertEquals(2.0, runner.getOrderManager().getActiveOrder(OrderSide.BUY).getRemainingSize());
        assertEquals(1.0, runner.getInventory());
    }

    @Test
    void marketableBuyIsRejectedAtActivation() {
        BacktestRunner runner = runner(10L, 1_000L);
        runner.processEvent(orderBook(100L, 100.0, 5.0, 101.0, 6.0));
        DesiredOrders desired = new DesiredOrders(
                new DesiredOrder(OrderSide.BUY, 102.0, 3.0),
                null
        );
        runner.getOrderManager().reconcile(desired, 100L);

        runner.processEvent(new FundingRateEvent(111L, 0.0));

        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertEquals(0, runner.getOrderManager().getPendingCommandCount());
        assertEquals(0.0, runner.getInventory());
        assertEquals(0.0, runner.getCash());
    }

    @Test
    void marketableSellIsRejectedAtActivation() {
        BacktestRunner runner = runner(10L, 1_000L);
        runner.processEvent(orderBook(100L, 100.0, 5.0, 101.0, 6.0));
        DesiredOrders desired = new DesiredOrders(
                null,
                new DesiredOrder(OrderSide.SELL, 99.0, 3.0)
        );
        runner.getOrderManager().reconcile(desired, 100L);

        runner.processEvent(new FundingRateEvent(111L, 0.0));

        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.SELL));
        assertEquals(0, runner.getOrderManager().getPendingCommandCount());
        assertEquals(0.0, runner.getInventory());
        assertEquals(0.0, runner.getCash());
    }

    @Test
    void snapshotDoesNotFillOrder() {
        BacktestRunner runner = activeBidWithQueueFive();

        runner.processEvent(orderBook(112L, 99.0, 1.0, 100.0, 1.0));

        assertEquals(0.0, runner.getInventory());
        assertEquals(3.0, runner.getOrderManager().getActiveOrder(OrderSide.BUY).getRemainingSize());
    }

    @Test
    void gapResetKeepsPortfolioAndFirstSnapshotOnlyRestartsLatency() {
        BacktestRunner runner = activeBidWithQueueFive();
        runner.getOrderManager().getActiveOrder(OrderSide.BUY).consumeQueue(5.0);
        runner.processEvent(new TradeEvent(112L, 100.0, 1.0, false));
        assertEquals(1.0, runner.getInventory());

        runner.processEvent(new TradeEvent(1_101L, 100.0, 20.0, false));

        assertTrue(runner.isStaleBook());
        assertEquals(1, runner.getGapResetCount());
        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertEquals(0, runner.getOrderManager().getPendingCommandCount());
        assertEquals(1.0, runner.getInventory());
        assertEquals(-100.0, runner.getCash());

        DesiredOrders desired = new DesiredOrders(
                new DesiredOrder(OrderSide.BUY, 90.0, 3.0),
                null
        );
        runner.processEvent(orderBook(1_102L, 90.0, 4.0, 91.0, 4.0));
        runner.getOrderManager().reconcile(desired, 1_102L);

        assertFalse(runner.isStaleBook());
        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertEquals(1, runner.getOrderManager().getPendingCommandCount());
        assertEquals(1.0, runner.getInventory());

        runner.processEvent(new TradeEvent(1_112L, 90.0, 20.0, false));
        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertEquals(1.0, runner.getInventory());
    }

    @Test
    void reduceOnlyBuyFillCannotCrossShortPositionThroughZero() {
        BacktestRunner runner = runner(10L, 1_000L);
        runner.getPortfolio().applyFill(
                new Fill(1L, 1L, OrderSide.SELL, 100.0, 0.3),
                0.0
        );
        runner.processEvent(orderBook(100L, 100.0, 0.0, 101.0, 0.0));
        runner.getOrderManager().reconcile(
                new DesiredOrders(
                        new DesiredOrder(OrderSide.BUY, 100.0, 1.0, true),
                        null
                ),
                100L
        );
        runner.processEvent(new FundingRateEvent(111L, 0.0));

        runner.processEvent(new TradeEvent(112L, 100.0, 2.0, false));
        runner.processEvent(new TradeEvent(113L, 100.0, 2.0, false));

        assertEquals(0.0, runner.getInventory(), 1e-9);
        assertEquals(0.7, runner.getOrderManager().getActiveOrder(OrderSide.BUY).getRemainingSize(), 1e-9);
    }

    @Test
    void reduceOnlySellFillCannotCrossLongPositionThroughZero() {
        BacktestRunner runner = runner(10L, 1_000L);
        runner.getPortfolio().applyFill(
                new Fill(1L, 1L, OrderSide.BUY, 100.0, 0.25),
                0.0
        );
        runner.processEvent(orderBook(100L, 100.0, 0.0, 101.0, 0.0));
        runner.getOrderManager().reconcile(
                new DesiredOrders(
                        null,
                        new DesiredOrder(OrderSide.SELL, 101.0, 1.0, true)
                ),
                100L
        );
        runner.processEvent(new FundingRateEvent(111L, 0.0));

        runner.processEvent(new TradeEvent(112L, 101.0, 2.0, true));
        runner.processEvent(new TradeEvent(113L, 101.0, 2.0, true));

        assertEquals(0.0, runner.getInventory(), 1e-9);
        assertEquals(0.75, runner.getOrderManager().getActiveOrder(OrderSide.SELL).getRemainingSize(), 1e-9);
    }

    private BacktestRunner activeBidWithQueueFive() {
        BacktestRunner runner = runner(10L, 1_000L);
        runner.processEvent(orderBook(100L, 100.0, 5.0, 101.0, 6.0));
        quoteBid(runner, 100L, 3.0);
        runner.processEvent(new FundingRateEvent(111L, 0.0));
        return runner;
    }

    private void quoteBid(BacktestRunner runner, long decisionTime, double size) {
        DesiredOrders desired = new DesiredOrders(
                new DesiredOrder(OrderSide.BUY, 100.0, size),
                null
        );
        runner.getOrderManager().reconcile(desired, decisionTime);
    }

    private BacktestRunner runner(long latencyNanos, long maxBookAgeNanos) {
        BacktestRunner runner = new BacktestRunner(
                new BacktestParameters(1.0, 100.0, latencyNanos, maxBookAgeNanos, 0.0),
                new ProjectedOrdersStrategy()
        );
        return runner;
    }

    // Сохраняет уже спроецированные тестом ордера и не добавляет собственную торговую логику.
    private static final class ProjectedOrdersStrategy implements MarketMakingStrategy {
        @Override
        public DesiredOrders decide(
                OrderBookEvent orderBook,
                boolean bookFresh,
                double inventory,
                double averageEntryPrice,
                PositionRange positionRange,
                Order projectedBid,
                Order projectedAsk
        ) {
            return new DesiredOrders(toDesiredOrder(projectedBid), toDesiredOrder(projectedAsk));
        }

        private DesiredOrder toDesiredOrder(Order order) {
            if (order == null) {
                return null;
            }
            return new DesiredOrder(
                    order.getSide(),
                    order.getPrice(),
                    order.getOriginalSize(),
                    order.isReduceOnly()
            );
        }
    }

    private OrderBookEvent orderBook(
            long timestamp,
            double bidPrice,
            double bidQuantity,
            double askPrice,
            double askQuantity
    ) {
        double[] bidPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] bidQuantities = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askQuantities = new double[OrderBookEvent.LEVEL_COUNT];
        bidPrices[0] = bidPrice;
        askPrices[0] = askPrice;
        bidQuantities[0] = bidQuantity;
        askQuantities[0] = askQuantity;
        return new OrderBookEvent(timestamp, bidPrices, askPrices, bidQuantities, askQuantities);
    }

    private OrderBookEvent orderBook(
            long timestamp,
            double[] visibleBidPrices,
            double[] visibleAskPrices,
            double[] visibleBidQuantities,
            double[] visibleAskQuantities
    ) {
        double[] bidPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] bidQuantities = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askQuantities = new double[OrderBookEvent.LEVEL_COUNT];

        for (int levelIndex = 0; levelIndex < visibleBidPrices.length; levelIndex++) {
            bidPrices[levelIndex] = visibleBidPrices[levelIndex];
            bidQuantities[levelIndex] = visibleBidQuantities[levelIndex];
        }
        for (int levelIndex = 0; levelIndex < visibleAskPrices.length; levelIndex++) {
            askPrices[levelIndex] = visibleAskPrices[levelIndex];
            askQuantities[levelIndex] = visibleAskQuantities[levelIndex];
        }

        return new OrderBookEvent(timestamp, bidPrices, askPrices, bidQuantities, askQuantities);
    }
}
