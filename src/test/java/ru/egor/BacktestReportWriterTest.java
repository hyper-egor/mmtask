package ru.egor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestReportWriterTest {

    @Test
    void writesReportsInsideStrategyDirectory(@TempDir Path temporaryDirectory) throws Exception {
        Portfolio portfolio = new Portfolio();
        StatisticsSnapshot snapshot = new StatisticsSnapshot(portfolio, 100.0);
        BacktestSummaryRow summary = new BacktestSummaryRow(
                "TOTAL", snapshot, 0.0, 0.0, 0.0, 0.0, 0.0,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0,
                0.0, 0.0, 0L, 0L, 0L, 0.0, 0.0, 0L, 0.0
        );
        HourlyStatisticsRow hourly = new HourlyStatisticsRow(
                1_773_883_200_000_000_000L, snapshot, 0L, 0.0, 0L, 0.0, 0L
        );
        BacktestResult result = new BacktestResult(
                "TestStrategy",
                BacktestParameters.baseline(),
                List.of(summary),
                List.of(hourly)
        );

        Path output = new BacktestReportWriter().write(result, temporaryDirectory);

        assertTrue(Files.isRegularFile(output.resolve("summary.csv")));
        assertTrue(Files.isRegularFile(output.resolve("hourly.csv")));
        assertTrue(Files.isRegularFile(output.resolve("timeline.png")));
        List<String> summaryLines = Files.readAllLines(output.resolve("summary.csv"));
        List<String> hourlyLines = Files.readAllLines(output.resolve("hourly.csv"));
        assertTrue(summaryLines.get(0).contains("makerFeeBps"));
        assertTrue(summaryLines.get(0).contains("reduceFillVolume"));
        assertTrue(hourlyLines.get(0).contains("reduceFillsInHour"));
        assertEquals(summaryLines.get(0).split(",").length, summaryLines.get(1).split(",").length);
        assertEquals(hourlyLines.get(0).split(",").length, hourlyLines.get(1).split(",").length);
    }
}
