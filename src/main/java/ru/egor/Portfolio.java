package ru.egor;

public final class Portfolio {
    private double inventory;
    private double averageEntryPrice;
    private double realizedPnl;
    private double feesPaid;
    private double fundingPnl;
    private double tradingCash;

    // Применяет maker fill к позиции, realized PnL, комиссиям и контрольному cash ledger.
    // Позиция учитывается по средней цене, а funding в baseline отдельно остается нулевым.
    public void applyFill(Fill fill, double makerFeeBps) {
        if (!Double.isFinite(makerFeeBps)) {
            throw new IllegalArgumentException("Maker fee должна быть конечным числом");
        }

        double fillSize = fill.getSize();
        double fillPrice = fill.getPrice();
        double signedFillSize = fill.getSide() == OrderSide.BUY ? fillSize : -fillSize;
        double notional = fillPrice * fillSize;

        // Cash ledger отражает только покупки и продажи. Комиссия вычитается отдельно в net PnL.
        tradingCash -= signedFillSize * fillPrice;
        feesPaid += notional * makerFeeBps / 10_000.0;

        if (inventory == 0.0 || Math.signum(inventory) == Math.signum(signedFillSize)) {
            addToPosition(signedFillSize, fillPrice);
        } else {
            closeOrReversePosition(signedFillSize, fillPrice);
        }
    }

    // Добавляет объем в ту же сторону и пересчитывает среднюю цену всей позиции.
    private void addToPosition(double signedFillSize, double fillPrice) {
        double oldAbsoluteInventory = Math.abs(inventory);
        double fillSize = Math.abs(signedFillSize);
        double newAbsoluteInventory = oldAbsoluteInventory + fillSize;

        averageEntryPrice = (
                averageEntryPrice * oldAbsoluteInventory + fillPrice * fillSize
        ) / newAbsoluteInventory;
        inventory += signedFillSize;
    }

    // Противоположный fill сначала закрывает старую позицию, а избыток открывает новую.
    private void closeOrReversePosition(double signedFillSize, double fillPrice) {
        double oldAbsoluteInventory = Math.abs(inventory);
        double fillSize = Math.abs(signedFillSize);
        double closedSize = Math.min(oldAbsoluteInventory, fillSize);

        if (inventory > 0.0) {
            realizedPnl += (fillPrice - averageEntryPrice) * closedSize;
        } else {
            realizedPnl += (averageEntryPrice - fillPrice) * closedSize;
        }

        int sizeComparison = Double.compare(fillSize, oldAbsoluteInventory);
        if (sizeComparison < 0) {
            inventory += signedFillSize;
            return;
        }
        if (sizeComparison == 0) {
            inventory = 0.0;
            averageEntryPrice = 0.0;
            return;
        }

        double newSize = fillSize - oldAbsoluteInventory;
        inventory = signedFillSize > 0.0 ? newSize : -newSize;
        averageEntryPrice = fillPrice;
    }

    // Переоценивает открытую позицию по последнему известному mid без фиктивного закрытия.
    public double calculateUnrealizedPnl(double midPrice) {
        validateMidPrice(midPrice);
        return inventory * (midPrice - averageEntryPrice);
    }

    // Gross PnL объединяет уже зафиксированный и еще нереализованный результат.
    public double calculateGrossPnl(double midPrice) {
        return realizedPnl + calculateUnrealizedPnl(midPrice);
    }

    // Net PnL вычитает комиссии и добавляет funding; в S0 funding всегда равен нулю.
    public double calculateNetPnl(double midPrice) {
        return calculateGrossPnl(midPrice) - feesPaid + fundingPnl;
    }

    // Независимо проверяет PnL через денежный ledger и текущую стоимость позиции.
    public double calculateNetEquity(double midPrice) {
        validateMidPrice(midPrice);
        return tradingCash + inventory * midPrice - feesPaid + fundingPnl;
    }

    private void validateMidPrice(double midPrice) {
        if (!Double.isFinite(midPrice) || midPrice <= 0.0) {
            throw new IllegalArgumentException("Mid price должна быть конечной и положительной");
        }
    }

    public double getInventory() {
        return inventory;
    }

    public double getAverageEntryPrice() {
        return averageEntryPrice;
    }

    public double getRealizedPnl() {
        return realizedPnl;
    }

    public double getFeesPaid() {
        return feesPaid;
    }

    public double getFundingPnl() {
        return fundingPnl;
    }

    public double getTradingCash() {
        return tradingCash;
    }
}
