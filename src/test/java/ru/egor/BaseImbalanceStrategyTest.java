package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaseImbalanceStrategyTest {

    @Test
    void allowsOnlyBidWhenAllThreeSignalsAreBidDominant() {
        BaseImbalanceStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 100.0, 1.0, true));

        DesiredOrders desired = decide(strategy, orderBook(1L, 3.0, 1.0, 3.0, 1.0));

        assertNotNull(desired.getBid());
        assertNull(desired.getAsk());
        assertEquals(100.0, desired.getBid().getPrice());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void allowsOnlyAskWhenAllThreeSignalsAreAskDominant() {
        BaseImbalanceStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 101.0, 1.0, false));

        DesiredOrders desired = decide(strategy, orderBook(1L, 1.0, 3.0, 1.0, 3.0));

        assertNull(desired.getBid());
        assertNotNull(desired.getAsk());
        assertEquals(101.0, desired.getAsk().getPrice());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
    }

    @Test
    void cancelsBothSidesWhenSignalsConflict() {
        BaseImbalanceStrategy strategy = strategy();
        decide(strategy, orderBook(1L, 20.0, 1.0, 20.0, 1.0));

        DesiredOrders desired = decide(strategy, orderBook(2L, 1.0, 2.0, 1.0, 20.0));

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
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
    void keepsBaselineHardInventoryLimit() {
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
        assertNull(desired.getAsk());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void cancelsOrdersWhenAggressionDoesNotConfirmStrongBookSignal() {
        BaseImbalanceStrategy strategy = strategy();

        DesiredOrders desired = decide(strategy, orderBook(1L, 3.0, 1.0, 3.0, 1.0));

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
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
        return strategy.decide(
                orderBook,
                true,
                0.0,
                0.0,
                new PositionRange(0.0, 0.0),
                null,
                null
        );
    }

    private OrderBookEvent orderBook(
            long timestampNanos,
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
            bidPrices[level] = 100.0 - level;
            askPrices[level] = 101.0 + level;

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
