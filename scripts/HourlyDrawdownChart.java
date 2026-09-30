import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class HourlyDrawdownChart {
    private static final Path INPUT = Path.of("results/BaseImbalanceStrategy/hourly.csv");
    private static final Path OUTPUT = Path.of("results/BaseImbalanceStrategy/график просадки.png");

    // Строит почасовую просадку без внешних библиотек: достаточно Java 21.
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(INPUT);
        String[] header = lines.getFirst().split(",");
        int timeColumn = findColumn(header, "hourEndUtc");
        int pnlColumn = findColumn(header, "netPnl");

        List<Instant> times = new ArrayList<>();
        List<Double> drawdowns = new ArrayList<>();
        double runningPeak = 0.0;
        double maximumDrawdown = 0.0;

        for (int rowIndex = 1; rowIndex < lines.size(); rowIndex++) {
            String[] row = lines.get(rowIndex).split(",");
            double netPnl = Double.parseDouble(row[pnlColumn]);
            runningPeak = Math.max(runningPeak, netPnl);
            double drawdown = runningPeak - netPnl;

            times.add(Instant.parse(row[timeColumn]));
            drawdowns.add(drawdown);
            maximumDrawdown = Math.max(maximumDrawdown, drawdown);
        }

        drawChart(times, drawdowns, maximumDrawdown);
        System.out.printf("График сохранен: %s%n", OUTPUT);
        System.out.printf("Максимальная почасовая просадка: %.2f USD%n", maximumDrawdown);
    }

    private static int findColumn(String[] header, String name) {
        for (int index = 0; index < header.length; index++) {
            if (header[index].equals(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException("В CSV нет столбца " + name);
    }

    // Рисует нулевую просадку сверху, поэтому более глубокая просадка идет вниз.
    private static void drawChart(
            List<Instant> times,
            List<Double> drawdowns,
            double maximumDrawdown
    ) throws Exception {
        int width = 1600;
        int height = 800;
        int left = 115;
        int right = 45;
        int top = 80;
        int bottom = 95;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;
        double yMaximum = Math.ceil(maximumDrawdown / 20.0) * 20.0;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);

        drawGridAndLabels(graphics, times, left, top, plotWidth, plotHeight, yMaximum);

        Path2D area = new Path2D.Double();
        Path2D line = new Path2D.Double();
        for (int index = 0; index < drawdowns.size(); index++) {
            double x = left + index * (double) plotWidth / (drawdowns.size() - 1);
            double y = top + drawdowns.get(index) / yMaximum * plotHeight;
            if (index == 0) {
                line.moveTo(x, y);
                area.moveTo(x, top);
                area.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        area.lineTo(left + plotWidth, top);
        area.closePath();

        graphics.setColor(new Color(217, 83, 79, 45));
        graphics.fill(area);
        graphics.setColor(new Color(217, 83, 79));
        graphics.setStroke(new BasicStroke(3.0f));
        graphics.draw(line);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        graphics.setColor(new Color(150, 30, 30));
        graphics.drawString(
                "Maximum hourly drawdown: %.2f USD".formatted(maximumDrawdown),
                width - 390,
                top + 32
        );

        graphics.dispose();
        ImageIO.write(image, "png", OUTPUT.toFile());
    }

    private static void drawGridAndLabels(
            Graphics2D graphics,
            List<Instant> times,
            int left,
            int top,
            int plotWidth,
            int plotHeight,
            double yMaximum
    ) {
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
        for (int tick = 0; tick <= 6; tick++) {
            int y = top + tick * plotHeight / 6;
            double value = tick * yMaximum / 6.0;
            graphics.setColor(new Color(210, 210, 210));
            graphics.setStroke(new BasicStroke(1.0f));
            graphics.drawLine(left, y, left + plotWidth, y);
            graphics.setColor(Color.DARK_GRAY);
            graphics.drawString("%.0f".formatted(value), left - 55, y + 6);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd HH").withZone(ZoneOffset.UTC);
        for (int tick = 0; tick <= 6; tick++) {
            int index = tick * (times.size() - 1) / 6;
            int x = left + tick * plotWidth / 6;
            graphics.setColor(new Color(220, 220, 220));
            graphics.drawLine(x, top, x, top + plotHeight);
            graphics.setColor(Color.DARK_GRAY);
            graphics.drawString(formatter.format(times.get(index)), x - 38, top + plotHeight + 35);
        }

        graphics.setColor(Color.DARK_GRAY);
        graphics.setStroke(new BasicStroke(1.5f));
        graphics.drawRect(left, top, plotWidth, plotHeight);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
        graphics.drawString("Date/Time (UTC)", left + plotWidth / 2 - 70, top + plotHeight + 75);
        graphics.rotate(-Math.PI / 2);
        graphics.drawString("Drawdown (USD)", -(top + plotHeight / 2 + 70), 38);
        graphics.rotate(Math.PI / 2);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
        graphics.drawString("Hourly Drawdown (19–21 March 2026)", widthCenter(graphics, 1600, "Hourly Drawdown (19–21 March 2026)"), 45);
    }

    private static int widthCenter(Graphics2D graphics, int width, String text) {
        return (width - graphics.getFontMetrics().stringWidth(text)) / 2;
    }
}
