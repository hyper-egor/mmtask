package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImbalanceProfitExitStrategyTest {

    @Test
    void strongBuyKeepsBidAndAddsProfitableAskForLong() {
        ImbalanceProfitExitStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 100.0, 1.0, true));

        DesiredOrders desired = decide(
                strategy,
                orderBook(1L, 100.0, 101.0, 3.0, 1.0, 1.0, 10.0),
                0.4,
                100.0
        );

        assertEquals(100.0, desired.getBid().getPrice());
        assertEquals(1.0, desired.getBid().getSize());
        assertEquals(101.0, desired.getAsk().getPrice());
        assertEquals(0.4, desired.getAsk().getSize(), 1e-9);
    }

    @Test
    void strongBuyDoesNotAddAskBelowMinimumProfit() {
        ImbalanceProfitExitStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 100.0, 1.0, true));

        DesiredOrders desired = decide(
                strategy,
                orderBook(1L, 100.0, 100.4, 3.0, 1.0, 3.0, 1.0),
                1.0,
                100.0
        );

        assertNotNull(desired.getBid());
        assertNull(desired.getAsk());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void withoutSignalLongKeepsOnlyProfitableAsk() {
        ImbalanceProfitExitStrategy strategy = strategy();

        DesiredOrders desired = decide(
                strategy,
                orderBook(1L, 100.0, 101.0, 1.0, 1.0, 1.0, 1.0),
                0.25,
                100.0
        );

        assertNull(desired.getBid());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(101.0, desired.getAsk().getPrice());
        assertEquals(0.25, desired.getAsk().getSize(), 1e-9);
    }

    @Test
    void withoutSignalShortKeepsOnlyProfitableBid() {
        ImbalanceProfitExitStrategy strategy = strategy();

        DesiredOrders desired = decide(
                strategy,
                orderBook(1L, 99.0, 100.0, 1.0, 1.0, 1.0, 1.0),
                -0.3,
                100.0
        );

        assertEquals(99.0, desired.getBid().getPrice());
        assertEquals(0.3, desired.getBid().getSize(), 1e-9);
        assertNull(desired.getAsk());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void withoutSignalAndWithoutPositionCancelsBothSides() {
        ImbalanceProfitExitStrategy strategy = strategy();

        DesiredOrders desired = decide(
                strategy,
                orderBook(1L, 100.0, 101.0, 1.0, 1.0, 1.0, 1.0),
                0.0,
                0.0
        );

        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.BUY).getAction());
        assertEquals(OrderInstruction.Action.CANCEL, desired.getInstruction(OrderSide.SELL).getAction());
    }

    @Test
    void oppositeStrongSignalMayCloseLongAtLoss() {
        ImbalanceProfitExitStrategy strategy = strategy();
        strategy.onTrade(new TradeEvent(0L, 101.0, 1.0, false));

        DesiredOrders desired = decide(
                strategy,
                orderBook(1L, 100.0, 101.0, 1.0, 3.0, 1.0, 3.0),
                0.4,
                105.0
        );

        assertNull(desired.getBid());
        assertEquals(101.0, desired.getAsk().getPrice());
        assertEquals(1.0, desired.getAsk().getSize());
    }

    private ImbalanceProfitExitStrategy strategy() {
        return new ImbalanceProfitExitStrategy(1.0, 5.0, 0.5, 10_000L, 1.01, 5, 20);
    }

    private DesiredOrders decide(
            ImbalanceProfitExitStrategy strategy,
            OrderBookEvent orderBook,
            double inventory,
            double averageEntryPrice
    ) {
        return strategy.decide(
                orderBook,
                true,
                inventory,
                averageEntryPrice,
                new PositionRange(inventory, inventory),
                null,
                null
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
            bidPrices[level] = bestBid - level * 0.1;
            askPrices[level] = bestAsk + level * 0.1;
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
