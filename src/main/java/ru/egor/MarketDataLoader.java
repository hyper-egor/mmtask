package ru.egor;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MarketDataLoader {
    private static final String ORDER_BOOK_DIRECTORY = "orderbook";
    private static final String TRADES_DIRECTORY = "trades";
    private static final String FUNDINGS_DIRECTORY = "fundings";
    private static final String KRYO_DIRECTORY = "kryo";
    private static final String KRYO_EXTENSION = ".kryo";
    private static final String[] BID_PRICE_COLUMNS = levelColumns("bid_price_");
    private static final String[] ASK_PRICE_COLUMNS = levelColumns("ask_price_");
    private static final String[] BID_QUANTITY_COLUMNS = levelColumns("bid_qty_");
    private static final String[] ASK_QUANTITY_COLUMNS = levelColumns("ask_qty_");

    private final java.nio.file.Path dataDirectory;
    private final Configuration hadoopConfiguration;

    public int loadCnt = 0;

    public MarketDataLoader(java.nio.file.Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.hadoopConfiguration = new Configuration();
    }

    // Читает три файла одного дня и добавляет их события в общий хронологический tape.
    public void loadDay(LocalDate date, List<Event> eventTape) throws IOException {
        java.nio.file.Path kryoFile = kryoFilePath(date);
        if (hasKryoData(kryoFile)) {
            readDayFromKryo(kryoFile, eventTape);
            loadCnt++;
            return;
        }

        List<OrderBookEvent> orderBooks = readOrderBooks(filePath(ORDER_BOOK_DIRECTORY, date));
        List<TradeEvent> trades = readAndAggregateTrades(filePath(TRADES_DIRECTORY, date));
        List<FundingRateEvent> fundingRates = readFundingRates(filePath(FUNDINGS_DIRECTORY, date));
        loadCnt += 3;

        List<Event> dayEvents = new ArrayList<>(orderBooks.size() + trades.size() + fundingRates.size());
        mergeDayEvents(orderBooks, trades, fundingRates, dayEvents);
        writeDayToKryo(kryoFile, dayEvents);
        eventTape.addAll(dayEvents);
    }

    // Читает полные L2 snapshots. Каждый snapshot содержит ровно 20 уровней с каждой стороны.
    private List<OrderBookEvent> readOrderBooks(java.nio.file.Path file) throws IOException {
        checkFileExists(file);
        List<OrderBookEvent> events = new ArrayList<>();

        try (ParquetReader<Group> reader = createReader(file)) {
            Group row;
            while ((row = reader.read()) != null) {
                double[] bidPrices = new double[OrderBookEvent.LEVEL_COUNT];
                double[] askPrices = new double[OrderBookEvent.LEVEL_COUNT];
                double[] bidQuantities = new double[OrderBookEvent.LEVEL_COUNT];
                double[] askQuantities = new double[OrderBookEvent.LEVEL_COUNT];

                // В Parquet уровни называются от 1 до 20, в массивах Java используются индексы от 0 до 19.
                for (int levelIndex = 0; levelIndex < OrderBookEvent.LEVEL_COUNT; levelIndex++) {
                    bidPrices[levelIndex] = row.getDouble(BID_PRICE_COLUMNS[levelIndex], 0);
                    askPrices[levelIndex] = row.getDouble(ASK_PRICE_COLUMNS[levelIndex], 0);
                    bidQuantities[levelIndex] = row.getDouble(BID_QUANTITY_COLUMNS[levelIndex], 0);
                    askQuantities[levelIndex] = row.getDouble(ASK_QUANTITY_COLUMNS[levelIndex], 0);
                }

                events.add(new OrderBookEvent(
                        row.getLong("datetime", 0),
                        bidPrices,
                        askPrices,
                        bidQuantities,
                        askQuantities
                ));
            }
        }

        return events;
    }

    // Агрегирует только trades с одинаковыми timestamp, ценой и стороной агрессора.
    private List<TradeEvent> readAndAggregateTrades(java.nio.file.Path file) throws IOException {
        checkFileExists(file);
        List<TradeEvent> events = new ArrayList<>();
        long currentTimestamp = Long.MIN_VALUE;
        Map<TradeKey, Double> sizesAtTimestamp = new LinkedHashMap<>();

        try (ParquetReader<Group> reader = createReader(file)) {
            Group row;
            while ((row = reader.read()) != null) {
                long timestamp = row.getLong("datetime", 0);

                // При переходе к следующему времени сохраняем агрегаты предыдущего timestamp.
                if (currentTimestamp != Long.MIN_VALUE && timestamp != currentTimestamp) {
                    addAggregatedTrades(currentTimestamp, sizesAtTimestamp, events);
                    sizesAtTimestamp.clear();
                }

                currentTimestamp = timestamp;
                double price = row.getDouble("price", 0);
                boolean makerAsk = row.getLong("is_maker_ask", 0) == 1L;
                TradeKey key = new TradeKey(price, makerAsk);
                double newSize = sizesAtTimestamp.getOrDefault(key, 0.0) + row.getDouble("size", 0);
                sizesAtTimestamp.put(key, newSize);
            }
        }

        if (currentTimestamp != Long.MIN_VALUE) {
            addAggregatedTrades(currentTimestamp, sizesAtTimestamp, events);
        }

        return events;
    }

    // Сохраняет все observations funding без фильтрации и без интерпретации как денежного списания.
    private List<FundingRateEvent> readFundingRates(java.nio.file.Path file) throws IOException {
        checkFileExists(file);
        List<FundingRateEvent> events = new ArrayList<>();

        try (ParquetReader<Group> reader = createReader(file)) {
            Group row;
            while ((row = reader.read()) != null) {
                events.add(new FundingRateEvent(
                        row.getLong("datetime", 0),
                        row.getDouble("funding_rate", 0)
                ));
            }
        }

        return events;
    }

    // Объединяет три уже отсортированных потока без повторной сортировки миллионов событий.
    private void mergeDayEvents(
            List<OrderBookEvent> orderBooks,
            List<TradeEvent> trades,
            List<FundingRateEvent> fundingRates,
            List<Event> eventTape
    ) {
        int orderBookIndex = 0;
        int tradeIndex = 0;
        int fundingIndex = 0;

        while (orderBookIndex < orderBooks.size()
                || tradeIndex < trades.size()
                || fundingIndex < fundingRates.size()) {
            Event nextEvent = null;

            if (tradeIndex < trades.size()) {
                nextEvent = trades.get(tradeIndex);
            }
            if (orderBookIndex < orderBooks.size()
                    && isEarlier(orderBooks.get(orderBookIndex), nextEvent)) {
                nextEvent = orderBooks.get(orderBookIndex);
            }
            if (fundingIndex < fundingRates.size()
                    && isEarlier(fundingRates.get(fundingIndex), nextEvent)) {
                nextEvent = fundingRates.get(fundingIndex);
            }

            eventTape.add(nextEvent);
            if (nextEvent.getType() == EventType.TRADE) {
                tradeIndex++;
            } else if (nextEvent.getType() == EventType.ORDER_BOOK) {
                orderBookIndex++;
            } else {
                fundingIndex++;
            }
        }
    }

    // Переносит накопленные размеры trades в отдельные события, сохраняя порядок первого появления ключей.
    private void addAggregatedTrades(
            long timestamp,
            Map<TradeKey, Double> sizesAtTimestamp,
            List<TradeEvent> events
    ) {
        for (Map.Entry<TradeKey, Double> entry : sizesAtTimestamp.entrySet()) {
            TradeKey key = entry.getKey();
            events.add(new TradeEvent(timestamp, key.price, entry.getValue(), key.makerAsk));
        }
    }

    private boolean isEarlier(Event candidate, Event current) {
        return current == null || candidate.compareTo(current) < 0;
    }

    private ParquetReader<Group> createReader(java.nio.file.Path file) throws IOException {
        Path hadoopPath = new Path(file.toUri());
        return ParquetReader.builder(new GroupReadSupport(), hadoopPath)
                .withConf(hadoopConfiguration)
                .build();
    }

    // Kryo-кеш хранит уже объединенный дневной tape, поэтому повторная загрузка не читает parquet.
    private void readDayFromKryo(java.nio.file.Path file, List<Event> eventTape) throws IOException {
        Kryo kryo = createKryo();
        try (Input input = new Input(new BufferedInputStream(Files.newInputStream(file)))) {
            int eventCount = input.readInt(true);
            for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
                eventTape.add((Event) kryo.readClassAndObject(input));
            }
        }
    }

    // Сохраняет дневной tape во временный файл и затем заменяет кеш, чтобы не оставить битый kryo при сбое.
    private void writeDayToKryo(java.nio.file.Path file, List<Event> events) throws IOException {
        Files.createDirectories(file.getParent());
        java.nio.file.Path temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        Kryo kryo = createKryo();

        try (Output output = new Output(new BufferedOutputStream(Files.newOutputStream(temporaryFile)))) {
            output.writeInt(events.size(), true);
            for (Event event : events) {
                kryo.writeClassAndObject(output, event);
            }
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temporaryFile);
            throw error;
        }

        Files.move(
                temporaryFile,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
        );
    }

    // Явные сериализаторы не требуют no-arg конструкторов и не зависят от private/final полей.
    private Kryo createKryo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(true);
        kryo.setReferences(false);
        kryo.register(TradeEvent.class, new TradeEventSerializer());
        kryo.register(OrderBookEvent.class, new OrderBookEventSerializer());
        kryo.register(FundingRateEvent.class, new FundingRateEventSerializer());
        return kryo;
    }

    private static void writeOrderBook(Output output, OrderBookEvent orderBook) {
        for (int levelIndex = 0; levelIndex < OrderBookEvent.LEVEL_COUNT; levelIndex++) {
            output.writeDouble(orderBook.getBidPrice(levelIndex));
        }
        for (int levelIndex = 0; levelIndex < OrderBookEvent.LEVEL_COUNT; levelIndex++) {
            output.writeDouble(orderBook.getAskPrice(levelIndex));
        }
        for (int levelIndex = 0; levelIndex < OrderBookEvent.LEVEL_COUNT; levelIndex++) {
            output.writeDouble(orderBook.getBidQuantity(levelIndex));
        }
        for (int levelIndex = 0; levelIndex < OrderBookEvent.LEVEL_COUNT; levelIndex++) {
            output.writeDouble(orderBook.getAskQuantity(levelIndex));
        }
    }

    private static double[] readLevels(Input input) {
        double[] levels = new double[OrderBookEvent.LEVEL_COUNT];
        for (int levelIndex = 0; levelIndex < OrderBookEvent.LEVEL_COUNT; levelIndex++) {
            levels[levelIndex] = input.readDouble();
        }
        return levels;
    }

    private boolean hasKryoData(java.nio.file.Path file) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) > 0;
    }

    private java.nio.file.Path filePath(String subdirectory, LocalDate date) {
        return dataDirectory.resolve(subdirectory).resolve(date + ".parquet");
    }

    private java.nio.file.Path kryoFilePath(LocalDate date) {
        return dataDirectory.resolve(KRYO_DIRECTORY).resolve(date + KRYO_EXTENSION);
    }

    private void checkFileExists(java.nio.file.Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Не найден файл с рыночными данными: " + file.toAbsolutePath());
        }
    }

    private static String[] levelColumns(String prefix) {
        String[] columns = new String[OrderBookEvent.LEVEL_COUNT];
        for (int levelIndex = 0; levelIndex < OrderBookEvent.LEVEL_COUNT; levelIndex++) {
            columns[levelIndex] = prefix + (levelIndex + 1);
        }
        return columns;
    }

    private static final class TradeKey {
        private final double price;
        private final boolean makerAsk;

        private TradeKey(double price, boolean makerAsk) {
            this.price = price;
            this.makerAsk = makerAsk;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TradeKey otherKey)) {
                return false;
            }
            return Double.compare(price, otherKey.price) == 0 && makerAsk == otherKey.makerAsk;
        }

        @Override
        public int hashCode() {
            int result = Double.hashCode(price);
            return 31 * result + Boolean.hashCode(makerAsk);
        }
    }

    private static final class TradeEventSerializer extends Serializer<TradeEvent> {
        @Override
        public void write(Kryo kryo, Output output, TradeEvent event) {
            output.writeLong(event.getTimestampNanos(), false);
            output.writeDouble(event.getPrice());
            output.writeDouble(event.getSize());
            output.writeBoolean(event.isMakerAsk());
        }

        @Override
        public TradeEvent read(Kryo kryo, Input input, Class<? extends TradeEvent> type) {
            return new TradeEvent(
                    input.readLong(false),
                    input.readDouble(),
                    input.readDouble(),
                    input.readBoolean()
            );
        }
    }

    private static final class OrderBookEventSerializer extends Serializer<OrderBookEvent> {
        @Override
        public void write(Kryo kryo, Output output, OrderBookEvent event) {
            output.writeLong(event.getTimestampNanos(), false);
            writeOrderBook(output, event);
        }

        @Override
        public OrderBookEvent read(Kryo kryo, Input input, Class<? extends OrderBookEvent> type) {
            return new OrderBookEvent(
                    input.readLong(false),
                    readLevels(input),
                    readLevels(input),
                    readLevels(input),
                    readLevels(input)
            );
        }
    }

    private static final class FundingRateEventSerializer extends Serializer<FundingRateEvent> {
        @Override
        public void write(Kryo kryo, Output output, FundingRateEvent event) {
            output.writeLong(event.getTimestampNanos(), false);
            output.writeDouble(event.getFundingRate());
        }

        @Override
        public FundingRateEvent read(Kryo kryo, Input input, Class<? extends FundingRateEvent> type) {
            return new FundingRateEvent(input.readLong(false), input.readDouble());
        }
    }
}
