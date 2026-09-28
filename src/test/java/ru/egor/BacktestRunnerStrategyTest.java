package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BacktestRunnerStrategyTest {

    @Test
    void freshBookCreatesBidAndAskWithoutDuplicateCommands() {
        BacktestRunner runner = runner();

        runner.processEvent(orderBook(100L, 100.0, 101.0));

        assertEquals(2, runner.getOrderManager().getPendingCommandCount());
        assertEquals(100.0, runner.getOrderManager().getProjectedOrder(OrderSide.BUY).getPrice());
        assertEquals(101.0, runner.getOrderManager().getProjectedOrder(OrderSide.SELL).getPrice());

        runner.processEvent(new FundingRateEvent(105L, 0.01));

        assertEquals(2, runner.getOrderManager().getPendingCommandCount());
    }

    @Test
    void bestPriceChangeCreatesCancelAndPlaceOnBothSides() {
        BacktestRunner runner = runner();
        runner.processEvent(orderBook(100L, 100.0, 101.0));
        runner.processEvent(new FundingRateEvent(111L, 0.0));
        long oldBidId = runner.getOrderManager().getActiveOrder(OrderSide.BUY).getId();
        long oldAskId = runner.getOrderManager().getActiveOrder(OrderSide.SELL).getId();

        runner.processEvent(orderBook(112L, 99.0, 100.0));

        assertEquals(4, runner.getOrderManager().getPendingCommandCount());
        Order newBid = runner.getOrderManager().getProjectedOrder(OrderSide.BUY);
        Order newAsk = runner.getOrderManager().getProjectedOrder(OrderSide.SELL);
        assertEquals(99.0, newBid.getPrice());
        assertEquals(100.0, newAsk.getPrice());
        assertNotEquals(oldBidId, newBid.getId());
        assertNotEquals(oldAskId, newAsk.getId());
    }

    @Test
    void partialFillDoesNotReplenishOrder() {
        BacktestRunner runner = runner();
        runner.processEvent(orderBookWithQueue(100L, 100.0, 101.0, 0.0, 0.0));
        runner.processEvent(new FundingRateEvent(111L, 0.0));
        long bidId = runner.getOrderManager().getActiveOrder(OrderSide.BUY).getId();

        runner.processEvent(new TradeEvent(112L, 100.0, 0.4, false));

        Order bid = runner.getOrderManager().getActiveOrder(OrderSide.BUY);
        assertEquals(bidId, bid.getId());
        assertEquals(0.6, bid.getRemainingSize(), 1e-9);
        assertEquals(0, runner.getOrderManager().getPendingCommandCount());
    }

    @Test
    void inventoryAtLongLimitLeavesOnlySafeAsk() {
        BacktestRunner runner = runner();
        runner.getPortfolio().applyFill(new Fill(1L, 1L, OrderSide.BUY, 100.0, 5.0), 0.0);

        runner.processEvent(orderBook(100L, 100.0, 101.0));

        assertNull(runner.getOrderManager().getProjectedOrder(OrderSide.BUY));
        assertNotNull(runner.getOrderManager().getProjectedOrder(OrderSide.SELL));
        assertEquals(1.0, runner.getOrderManager().getProjectedOrder(OrderSide.SELL).getOriginalSize());
    }

    @Test
    void unsafeReplacementCancelsOldBidBeforePlacingNewOne() {
        BacktestRunner runner = runner();
        runner.getPortfolio().applyFill(new Fill(1L, 1L, OrderSide.BUY, 100.0, 4.0), 0.0);
        runner.processEvent(orderBook(100L, 100.0, 101.0));
        runner.processEvent(new FundingRateEvent(111L, 0.0));

        runner.processEvent(orderBook(112L, 99.0, 102.0));

        assertNull(runner.getOrderManager().getProjectedOrder(OrderSide.BUY));
        assertEquals(3, runner.getOrderManager().getPendingCommandCount());

        runner.processEvent(new FundingRateEvent(123L, 0.0));

        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertEquals(99.0, runner.getOrderManager().getProjectedOrder(OrderSide.BUY).getPrice());
        assertEquals(1, runner.getOrderManager().getPendingCommandCount());
    }

    @Test
    void gapRemovesOrdersAndFreshSnapshotStartsNormalLatencyAgain() {
        BacktestRunner runner = runner();
        runner.processEvent(orderBook(100L, 100.0, 101.0));

        runner.processEvent(new FundingRateEvent(1_101L, 0.0));

        assertEquals(0, runner.getOrderManager().getPendingCommandCount());
        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.SELL));

        runner.processEvent(orderBook(1_102L, 90.0, 91.0));

        assertEquals(2, runner.getOrderManager().getPendingCommandCount());
        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.BUY));
        assertNull(runner.getOrderManager().getActiveOrder(OrderSide.SELL));
    }

    private BacktestRunner runner() {
        BacktestParameters parameters = new BacktestParameters(1.0, 5.0, 10L, 1_000L, 0.0);
        return new BacktestRunner(parameters);
    }

    private OrderBookEvent orderBook(long timestamp, double bestBid, double bestAsk) {
        return orderBookWithQueue(timestamp, bestBid, bestAsk, 5.0, 5.0);
    }

    private OrderBookEvent orderBookWithQueue(
            long timestamp,
            double bestBid,
            double bestAsk,
            double bidQuantity,
            double askQuantity
    ) {
        double[] bidPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] bidQuantities = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askQuantities = new double[OrderBookEvent.LEVEL_COUNT];
        bidPrices[0] = bestBid;
        askPrices[0] = bestAsk;
        bidQuantities[0] = bidQuantity;
        askQuantities[0] = askQuantity;
        return new OrderBookEvent(timestamp, bidPrices, askPrices, bidQuantities, askQuantities);
    }
}
