package ru.egor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class Main {
    private static final Path DATA_DIRECTORY = Path.of("data");
    private static final Path RESULTS_DIRECTORY = Path.of("results");

    public static void main(String[] args) throws IOException {
        List<Event> eventTape = initializeEventTape();

        BacktestParameters parameters = BacktestParameters.baseline();
        BacktestRunner backtestRunner = new BacktestRunner(parameters);
        BacktestResult result = backtestRunner.run(eventTape);
        new BacktestReportWriter().write(result, RESULTS_DIRECTORY);
    }

    // Явно загружает выбранные дни. Для короткого запуска лишние строки можно закомментировать.
    private static List<Event> initializeEventTape() throws IOException {
        long startedAt = System.nanoTime();
        List<Event> eventTape = new ArrayList<>();
        MarketDataLoader loader = new MarketDataLoader(DATA_DIRECTORY);

        loader.loadDay(LocalDate.of(2026, 3, 19), eventTape);
        loader.loadDay(LocalDate.of(2026, 3, 20), eventTape);
        loader.loadDay(LocalDate.of(2026, 3, 21), eventTape);

        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        System.out.printf(
                "Инициализация завершена: прочитано " + loader.loadCnt + " файлов, получено %,d событий за %.3f с%n",
                eventTape.size(),
                elapsedSeconds
        );
        return eventTape;
    }
}
