package ru.egor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioTest {
    private static final double EPSILON = 1e-9;

    @Test
    void opensLongAndShortPositions() {
        Portfolio longPortfolio = new Portfolio();
        longPortfolio.applyFill(fill(OrderSide.BUY, 100.0, 2.0), 0.0);

        assertEquals(2.0, longPortfolio.getInventory());
        assertEquals(100.0, longPortfolio.getAverageEntryPrice());
        assertEquals(0.0, longPortfolio.getRealizedPnl());
        assertEquals(-200.0, longPortfolio.getTradingCash());

        Portfolio shortPortfolio = new Portfolio();
        shortPortfolio.applyFill(fill(OrderSide.SELL, 101.0, 3.0), 0.0);

        assertEquals(-3.0, shortPortfolio.getInventory());
        assertEquals(101.0, shortPortfolio.getAverageEntryPrice());
        assertEquals(0.0, shortPortfolio.getRealizedPnl());
        assertEquals(303.0, shortPortfolio.getTradingCash());
    }

    @Test
    void addsToPositionUsingWeightedAveragePrice() {
        Portfolio portfolio = new Portfolio();
        portfolio.applyFill(fill(OrderSide.BUY, 100.0, 2.0), 0.0);
        portfolio.applyFill(fill(OrderSide.BUY, 110.0, 1.0), 0.0);

        assertEquals(3.0, portfolio.getInventory());
        assertEquals(310.0 / 3.0, portfolio.getAverageEntryPrice(), EPSILON);
        assertEquals(0.0, portfolio.getRealizedPnl());
        assertEquals(-310.0, portfolio.getTradingCash());
    }

    @Test
    void partiallyAndFullyClosesLongPosition() {
        Portfolio portfolio = new Portfolio();
        portfolio.applyFill(fill(OrderSide.BUY, 100.0, 3.0), 0.0);
        portfolio.applyFill(fill(OrderSide.SELL, 110.0, 1.0), 0.0);

        assertEquals(2.0, portfolio.getInventory());
        assertEquals(100.0, portfolio.getAverageEntryPrice());
        assertEquals(10.0, portfolio.getRealizedPnl());

        portfolio.applyFill(fill(OrderSide.SELL, 90.0, 2.0), 0.0);

        assertEquals(0.0, portfolio.getInventory());
        assertEquals(0.0, portfolio.getAverageEntryPrice());
        assertEquals(-10.0, portfolio.getRealizedPnl());
        assertEquals(-10.0, portfolio.getTradingCash());
    }

    @Test
    void partiallyAndFullyClosesShortPosition() {
        Portfolio portfolio = new Portfolio();
        portfolio.applyFill(fill(OrderSide.SELL, 100.0, 3.0), 0.0);
        portfolio.applyFill(fill(OrderSide.BUY, 90.0, 1.0), 0.0);

        assertEquals(-2.0, portfolio.getInventory());
        assertEquals(100.0, portfolio.getAverageEntryPrice());
        assertEquals(10.0, portfolio.getRealizedPnl());

        portfolio.applyFill(fill(OrderSide.BUY, 110.0, 2.0), 0.0);

        assertEquals(0.0, portfolio.getInventory());
        assertEquals(0.0, portfolio.getAverageEntryPrice());
        assertEquals(-10.0, portfolio.getRealizedPnl());
        assertEquals(-10.0, portfolio.getTradingCash());
    }

    @Test
    void reversesLongToShortAtFillPrice() {
        Portfolio portfolio = new Portfolio();
        portfolio.applyFill(fill(OrderSide.BUY, 100.0, 2.0), 0.0);
        portfolio.applyFill(fill(OrderSide.SELL, 110.0, 3.0), 0.0);

        assertEquals(-1.0, portfolio.getInventory());
        assertEquals(110.0, portfolio.getAverageEntryPrice());
        assertEquals(20.0, portfolio.getRealizedPnl());
        assertEquals(130.0, portfolio.getTradingCash());
    }

    @Test
    void reversesShortToLongAtFillPrice() {
        Portfolio portfolio = new Portfolio();
        portfolio.applyFill(fill(OrderSide.SELL, 100.0, 2.0), 0.0);
        portfolio.applyFill(fill(OrderSide.BUY, 90.0, 3.0), 0.0);

        assertEquals(1.0, portfolio.getInventory());
        assertEquals(90.0, portfolio.getAverageEntryPrice());
        assertEquals(20.0, portfolio.getRealizedPnl());
        assertEquals(-70.0, portfolio.getTradingCash());
    }

    @Test
    void calculatesRealizedUnrealizedGrossAndNetPnl() {
        Portfolio portfolio = new Portfolio();
        portfolio.applyFill(fill(OrderSide.BUY, 100.0, 2.0), 2.0);
        portfolio.applyFill(fill(OrderSide.SELL, 110.0, 3.0), 2.0);

        assertEquals(20.0, portfolio.getRealizedPnl());
        assertEquals(10.0, portfolio.calculateUnrealizedPnl(100.0));
        assertEquals(30.0, portfolio.calculateGrossPnl(100.0));
        assertEquals(0.106, portfolio.getFeesPaid(), EPSILON);
        assertEquals(0.0, portfolio.getFundingPnl());
        assertEquals(29.894, portfolio.calculateNetPnl(100.0), EPSILON);
    }

    @Test
    void supportsPositiveZeroAndNegativeMakerFee() {
        Portfolio positiveFee = new Portfolio();
        positiveFee.applyFill(fill(OrderSide.BUY, 100.0, 2.0), 1.0);
        assertEquals(0.02, positiveFee.getFeesPaid(), EPSILON);

        Portfolio zeroFee = new Portfolio();
        zeroFee.applyFill(fill(OrderSide.BUY, 100.0, 2.0), 0.0);
        assertEquals(0.0, zeroFee.getFeesPaid());

        Portfolio rebate = new Portfolio();
        rebate.applyFill(fill(OrderSide.BUY, 100.0, 2.0), -1.0);
        assertEquals(-0.02, rebate.getFeesPaid(), EPSILON);
    }

    @Test
    void accountingDecompositionEqualsCashLedger() {
        Portfolio portfolio = new Portfolio();
        portfolio.applyFill(fill(OrderSide.BUY, 100.0, 2.0), 2.0);
        portfolio.applyFill(fill(OrderSide.BUY, 104.0, 1.0), 2.0);
        portfolio.applyFill(fill(OrderSide.SELL, 110.0, 4.0), 2.0);

        double midPrice = 105.0;

        assertEquals(
                portfolio.calculateNetPnl(midPrice),
                portfolio.calculateNetEquity(midPrice),
                EPSILON
        );
    }

    private Fill fill(OrderSide side, double price, double size) {
        return new Fill(1L, 1L, side, price, size);
    }
}
