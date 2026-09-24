package ru.egor;

import java.util.List;

public final class BacktestRunner {

    // Последовательно проигрывает market tape; обработчики пока оставлены как каркас будущей логики.
    public void run(List<Event> eventTape) {
        long startedAt = System.nanoTime();
        long processedEvents = 0;

        for (Event event : eventTape) {
            if (event.getType() == EventType.TRADE) {
                processTrade((TradeEvent) event);
            } else if (event.getType() == EventType.ORDER_BOOK) {
                processOrderBook((OrderBookEvent) event);
            } else {
                processFundingRate((FundingRateEvent) event);
            }
            processedEvents++;
        }

        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        System.out.printf(
                "Replay завершен: %,d событий за %.3f с%n",
                processedEvents,
                elapsedSeconds
        );
    }

    // Trade позже будет обновлять trade flow и проверять fills уже активных ордеров.
    private void processTrade(TradeEvent event) {
    }

    // Snapshot позже будет обновлять текущее состояние стакана и запускать пересчет котировок.
    private void processOrderBook(OrderBookEvent event) {
    }

    // Funding observation позже будет обновлять текущий фон funding rate.
    private void processFundingRate(FundingRateEvent event) {
    }
}
