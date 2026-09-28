package ru.egor;

import java.util.List;

public final class BacktestResult {
    private final String strategyName;
    private final BacktestParameters parameters;
    private final List<BacktestSummaryRow> summaryRows;
    private final List<HourlyStatisticsRow> hourlyRows;

    public BacktestResult(
            String strategyName,
            BacktestParameters parameters,
            List<BacktestSummaryRow> summaryRows,
            List<HourlyStatisticsRow> hourlyRows
    ) {
        this.strategyName = strategyName;
        this.parameters = parameters;
        this.summaryRows = List.copyOf(summaryRows);
        this.hourlyRows = List.copyOf(hourlyRows);
    }

    public String getStrategyName() { return strategyName; }
    public BacktestParameters getParameters() { return parameters; }
    public List<BacktestSummaryRow> getSummaryRows() { return summaryRows; }
    public List<HourlyStatisticsRow> getHourlyRows() { return hourlyRows; }
}
