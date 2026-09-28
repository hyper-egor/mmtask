package ru.egor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class Main {
    private static final Path DATA_DIRECTORY = Path.of("data");

    // Стартовые параметры сценария, а не вывод исследования. Их нужно варьировать в экспериментах.
    private static final long ORDER_LATENCY_MILLIS = 3L;
    private static final long MAX_BOOK_AGE_MILLIS = 1_000L;
    private static final double MAKER_FEE_BPS = 0.0;

    public static void main(String[] args) throws IOException {
        List<Event> eventTape = initializeEventTape();

        BacktestParameters parameters = new BacktestParameters(
                ORDER_LATENCY_MILLIS * 1_000_000L,
                MAX_BOOK_AGE_MILLIS * 1_000_000L,
                MAKER_FEE_BPS
        );
        BacktestRunner backtestRunner = new BacktestRunner(parameters);
        backtestRunner.run(eventTape);
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
