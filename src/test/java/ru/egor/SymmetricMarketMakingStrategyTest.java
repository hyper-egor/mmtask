package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SymmetricMarketMakingStrategyTest {
    private final SymmetricMarketMakingStrategy strategy = new SymmetricMarketMakingStrategy(1.0, 5.0);

    @Test
    void quotesFixedSizeAtBestBidAndAsk() {
        DesiredOrders desired = decide(orderBook(100.0, 101.0), true, new PositionRange(0.0, 0.0), null, null);

        assertEquals(100.0, desired.getBid().getPrice());
        assertEquals(1.0, desired.getBid().getSize());
        assertEquals(101.0, desired.getAsk().getPrice());
        assertEquals(1.0, desired.getAsk().getSize());
    }

    @Test
    void doesNotQuoteWithoutFreshValidBook() {
        DesiredOrders withoutBook = decide(null, false, new PositionRange(0.0, 0.0), null, null);
        DesiredOrders staleBook = decide(orderBook(100.0, 101.0), false, new PositionRange(0.0, 0.0), null, null);
        DesiredOrders crossedBook = decide(orderBook(101.0, 100.0), true, new PositionRange(0.0, 0.0), null, null);

        assertNull(withoutBook.getBid());
        assertNull(withoutBook.getAsk());
        assertNull(staleBook.getBid());
        assertNull(staleBook.getAsk());
        assertNull(crossedBook.getBid());
        assertNull(crossedBook.getAsk());
    }

    @Test
    void allowsFullOrderExactlyUpToHardLimit() {
        DesiredOrders desired = decide(orderBook(100.0, 101.0), true, new PositionRange(4.0, 4.0), null, null);

        assertEquals(1.0, desired.getBid().getSize());
        assertEquals(1.0, desired.getAsk().getSize());
    }

    @Test
    void disablesDangerousSideInsteadOfReducingOrderSize() {
        DesiredOrders nearLongLimit = decide(
                orderBook(100.0, 101.0),
                true,
                new PositionRange(4.5, 4.5),
                null,
                null
        );
        DesiredOrders nearShortLimit = decide(
                orderBook(100.0, 101.0),
                true,
                new PositionRange(-4.5, -4.5),
                null,
                null
        );

        assertNull(nearLongLimit.getBid());
        assertEquals(1.0, nearLongLimit.getAsk().getSize());
        assertEquals(1.0, nearShortLimit.getBid().getSize());
        assertNull(nearShortLimit.getAsk());
    }

    @Test
    void keepsMatchingProjectedOrderWithoutCountingItTwice() {
        Order projectedBid = new Order(1L, OrderSide.BUY, 100.0, 1.0);
        projectedBid.applyFill(0.6);

        DesiredOrders desired = decide(
                orderBook(100.0, 101.0),
                true,
                new PositionRange(0.0, 5.0),
                projectedBid,
                null
        );

        assertEquals(1.0, desired.getBid().getSize());
        assertEquals(0.4, projectedBid.getRemainingSize(), 1e-9);
    }

    @Test
    void rejectsUnsafeReplacementUntilOldRiskDisappears() {
        Order oldBid = new Order(1L, OrderSide.BUY, 100.0, 1.0);

        DesiredOrders whileOldBidCanFill = decide(
                orderBook(99.0, 100.0),
                true,
                new PositionRange(3.0, 5.0),
                oldBid,
                null
        );
        DesiredOrders afterCancel = decide(
                orderBook(99.0, 100.0),
                true,
                new PositionRange(3.0, 4.0),
                null,
                null
        );

        assertNull(whileOldBidCanFill.getBid());
        assertEquals(1.0, afterCancel.getBid().getSize());
    }

    private DesiredOrders decide(
            OrderBookEvent orderBook,
            boolean bookFresh,
            PositionRange positionRange,
            Order projectedBid,
            Order projectedAsk
    ) {
        return strategy.decide(
                orderBook,
                bookFresh,
                0.0,
                positionRange,
                projectedBid,
                projectedAsk
        );
    }

    private OrderBookEvent orderBook(double bestBid, double bestAsk) {
        double[] bidPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askPrices = new double[OrderBookEvent.LEVEL_COUNT];
        double[] bidQuantities = new double[OrderBookEvent.LEVEL_COUNT];
        double[] askQuantities = new double[OrderBookEvent.LEVEL_COUNT];
        bidPrices[0] = bestBid;
        askPrices[0] = bestAsk;
        bidQuantities[0] = 5.0;
        askQuantities[0] = 5.0;
        return new OrderBookEvent(1L, bidPrices, askPrices, bidQuantities, askQuantities);
    }
}
