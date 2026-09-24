package ru.egor;

public abstract class Event implements Comparable<Event> {
    private final long timestampNanos;
    private final EventType type;

    protected Event(long timestampNanos, EventType type) {
        this.timestampNanos = timestampNanos;
        this.type = type;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public EventType getType() {
        return type;
    }

    // Сортирует события по времени, а при совпадении ставит trade перед стаканом и funding.
    @Override
    public int compareTo(Event other) {
        int timeComparison = Long.compare(timestampNanos, other.timestampNanos);
        if (timeComparison != 0) {
            return timeComparison;
        }

        return Integer.compare(
                type.getOrderAtSameTimestamp(),
                other.type.getOrderAtSameTimestamp()
        );
    }
}
