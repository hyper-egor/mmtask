package ru.egor;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class BacktestReportWriter {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    // Создает самодостаточные CSV и компактный PNG в подпапке класса стратегии.
    public Path write(BacktestResult result, Path resultsDirectory) throws IOException {
        Path strategyDirectory = resultsDirectory.resolve(result.getStrategyName());
        Files.createDirectories(strategyDirectory);

        writeSummaryCsv(result, strategyDirectory.resolve("summary.csv"));
        writeHourlyCsv(result, strategyDirectory.resolve("hourly.csv"));
        writeTimelineChart(result.getHourlyRows(), strategyDirectory.resolve("timeline.png"));
        printConsoleReport(result, strategyDirectory);
        return strategyDirectory;
    }

    private void writeSummaryCsv(BacktestResult result, Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            writer.write("period,strategy,orderSize,hardInventoryLimit,orderLatencyNanos,"
                    + "orderLatencyMillis,maxBookAgeNanos,maxBookAgeSeconds,makerFeeBps,"
                    + "realizedPnlCumulative,unrealizedPnlEnd,grossPnlCumulative,feesPaidCumulative,"
                    + "fundingPnlCumulative,netPnlCumulative,dailyPnl,finalInventory,maxLongInventory,"
                    + "maxShortInventory,averageAbsInventory,maxDrawdown,activatedOrders,fillEvents,"
                    + "fullyFilledOrders,partiallyFilledOrders,buyFillVolume,sellFillVolume,"
                    + "totalFillVolume,averageFillSize,cancelCommands,replacements,"
                    + "averageOrderLifetimeMillis,gapResets,staleSeconds,reduceActivatedOrders,"
                    + "reduceFillEvents,reduceFullyFilledOrders,reducePartiallyFilledOrders,"
                    + "reduceFillVolume,reduceReplacements,averageReduceOrderLifetimeMillis");
            writer.newLine();

            BacktestParameters parameters = result.getParameters();
            for (BacktestSummaryRow row : result.getSummaryRows()) {
                StatisticsSnapshot snapshot = row.getSnapshot();
                writer.write(String.format(Locale.US,
                        "%s,%s,%.8f,%.8f,%d,%.6f,%d,%.6f,%.8f,"
                                + "%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,"
                                + "%d,%d,%d,%d,%.8f,%.8f,%.8f,%.8f,%d,%d,%.6f,%d,%.6f,"
                                + "%d,%d,%d,%d,%.8f,%d,%.6f",
                        row.getPeriod(), result.getStrategyName(), parameters.getOrderSize(),
                        parameters.getHardInventoryLimit(), parameters.getOrderLatencyNanos(),
                        parameters.getOrderLatencyNanos() / 1_000_000.0,
                        parameters.getMaxBookAgeNanos(), parameters.getMaxBookAgeNanos() / 1_000_000_000.0,
                        parameters.getMakerFeeBps(), snapshot.getRealizedPnl(), snapshot.getUnrealizedPnl(),
                        snapshot.getGrossPnl(), snapshot.getFeesPaid(), snapshot.getFundingPnl(),
                        snapshot.getNetPnl(), row.getDailyPnl(), snapshot.getInventory(),
                        row.getMaxLongInventory(), row.getMaxShortInventory(), row.getAverageAbsInventory(),
                        row.getMaxDrawdown(), row.getActivatedOrders(), row.getFillEvents(),
                        row.getFullyFilledOrders(), row.getPartiallyFilledOrders(), row.getBuyFillVolume(),
                        row.getSellFillVolume(), row.getTotalFillVolume(), row.getAverageFillSize(),
                        row.getCancelCommands(), row.getReplacements(), row.getAverageOrderLifetimeMillis(),
                        row.getGapResets(), row.getStaleSeconds(), row.getReduceActivatedOrders(),
                        row.getReduceFillEvents(), row.getReduceFullyFilledOrders(),
                        row.getReducePartiallyFilledOrders(), row.getReduceFillVolume(),
                        row.getReduceReplacements(), row.getAverageReduceOrderLifetimeMillis()));
                writer.newLine();
            }
        }
    }

    private void writeHourlyCsv(BacktestResult result, Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            writer.write("hourEndUtc,strategy,orderSize,hardInventoryLimit,orderLatencyMillis,"
                    + "maxBookAgeSeconds,makerFeeBps,midPrice,inventory,realizedPnl,unrealizedPnl,"
                    + "grossPnl,feesPaid,fundingPnl,netPnl,fillsInHour,tradedVolumeInHour,"
                    + "gapResetsInHour,reduceFillsInHour,reduceVolumeInHour");
            writer.newLine();

            BacktestParameters parameters = result.getParameters();
            for (HourlyStatisticsRow row : result.getHourlyRows()) {
                StatisticsSnapshot snapshot = row.getSnapshot();
                writer.write(String.format(Locale.US,
                        "%s,%s,%.8f,%.8f,%.6f,%.6f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%d,%.8f,%d,%d,%.8f",
                        formatInstant(row.getHourEndNanos()), result.getStrategyName(), parameters.getOrderSize(),
                        parameters.getHardInventoryLimit(), parameters.getOrderLatencyNanos() / 1_000_000.0,
                        parameters.getMaxBookAgeNanos() / 1_000_000_000.0, parameters.getMakerFeeBps(),
                        snapshot.getMidPrice(), snapshot.getInventory(), snapshot.getRealizedPnl(),
                        snapshot.getUnrealizedPnl(), snapshot.getGrossPnl(), snapshot.getFeesPaid(),
                        snapshot.getFundingPnl(), snapshot.getNetPnl(), row.getFillsInHour(),
                        row.getTradedVolumeInHour(), row.getGapResetsInHour(),
                        row.getReduceFillsInHour(), row.getReduceVolumeInHour()));
                writer.newLine();
            }
        }
    }

    // Рисует один небольшой файл с общей временной осью для net PnL и inventory.
    private void writeTimelineChart(List<HourlyStatisticsRow> rows, Path file) throws IOException {
        int width = 1200;
        int height = 700;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 14));

        drawSeries(graphics, rows, 60, 55, width - 90, 260, true, "Net PnL, USD", new Color(31, 119, 180));
        drawSeries(graphics, rows, 60, 380, width - 90, 260, false, "Inventory, ETH", new Color(214, 39, 40));

        graphics.dispose();
        ImageIO.write(image, "png", file.toFile());
    }

    private void drawSeries(Graphics2D graphics, List<HourlyStatisticsRow> rows, int x, int y,
                            int width, int height, boolean pnl, String title, Color color) {
        graphics.setColor(Color.DARK_GRAY);
        graphics.drawString(title, x, y - 15);
        graphics.drawRect(x, y, width, height);
        if (rows.isEmpty()) {
            return;
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (HourlyStatisticsRow row : rows) {
            double value = pnl ? row.getSnapshot().getNetPnl() : row.getSnapshot().getInventory();
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (Double.compare(min, max) == 0) {
            min -= 1.0;
            max += 1.0;
        }

        graphics.setColor(new Color(225, 225, 225));
        for (int grid = 1; grid < 4; grid++) {
            int gridY = y + height * grid / 4;
            graphics.drawLine(x, gridY, x + width, gridY);
        }

        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(2.0f));
        int previousX = x;
        double firstValue = pnl ? rows.get(0).getSnapshot().getNetPnl() : rows.get(0).getSnapshot().getInventory();
        int previousY = scaleY(firstValue, min, max, y, height);
        for (int index = 1; index < rows.size(); index++) {
            int currentX = x + width * index / Math.max(1, rows.size() - 1);
            double value = pnl ? rows.get(index).getSnapshot().getNetPnl() : rows.get(index).getSnapshot().getInventory();
            int currentY = scaleY(value, min, max, y, height);
            graphics.drawLine(previousX, previousY, currentX, currentY);
            previousX = currentX;
            previousY = currentY;
        }

        graphics.setColor(Color.DARK_GRAY);
        graphics.drawString(String.format(Locale.US, "max %.4f", max), x + 5, y + 17);
        graphics.drawString(String.format(Locale.US, "min %.4f", min), x + 5, y + height - 7);
        graphics.drawString(formatInstant(rows.get(0).getHourEndNanos()), x, y + height + 20);
        String lastTime = formatInstant(rows.get(rows.size() - 1).getHourEndNanos());
        graphics.drawString(lastTime, x + width - 170, y + height + 20);
    }

    private int scaleY(double value, double min, double max, int y, int height) {
        double ratio = (value - min) / (max - min);
        return y + height - (int) Math.round(ratio * height);
    }

    private void printConsoleReport(BacktestResult result, Path strategyDirectory) {
        BacktestParameters parameters = result.getParameters();
        BacktestSummaryRow total = result.getSummaryRows().get(result.getSummaryRows().size() - 1);
        StatisticsSnapshot snapshot = total.getSnapshot();

        System.out.printf(Locale.US, "%nСтратегия: %s%n", result.getStrategyName());
        System.out.printf(Locale.US,
                "Параметры: orderSize=%.4f ETH, hardLimit=%.4f ETH, latency=%.3f ms, "
                        + "maxBookAge=%.3f s, makerFee=%.4f bps%n",
                parameters.getOrderSize(), parameters.getHardInventoryLimit(),
                parameters.getOrderLatencyNanos() / 1_000_000.0,
                parameters.getMaxBookAgeNanos() / 1_000_000_000.0, parameters.getMakerFeeBps());
        System.out.printf(Locale.US,
                "Итог: netPnL=%.6f, realized=%.6f, unrealized=%.6f, inventory=%.6f, "
                        + "fills=%d, volume=%.6f, maxDrawdown=%.6f, gaps=%d%n",
                snapshot.getNetPnl(), snapshot.getRealizedPnl(), snapshot.getUnrealizedPnl(),
                snapshot.getInventory(), total.getFillEvents(), total.getTotalFillVolume(),
                total.getMaxDrawdown(), total.getGapResets());
        System.out.printf(Locale.US,
                "Reduce-only: activated=%d, fills=%d, volume=%.6f, replacements=%d, "
                        + "averageLifetime=%.3f ms%n",
                total.getReduceActivatedOrders(), total.getReduceFillEvents(),
                total.getReduceFillVolume(), total.getReduceReplacements(),
                total.getAverageReduceOrderLifetimeMillis());
        System.out.println("Результаты: " + strategyDirectory.toAbsolutePath());
    }

    private static String formatInstant(long timestampNanos) {
        long seconds = Math.floorDiv(timestampNanos, NANOS_PER_SECOND);
        long nanos = Math.floorMod(timestampNanos, NANOS_PER_SECOND);
        return Instant.ofEpochSecond(seconds, nanos).toString();
    }
}
