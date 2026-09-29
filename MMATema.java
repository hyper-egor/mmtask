class MMATema {

    public final Deque<DoubleLongSymbPrice> stat;
    int period;
    long timeframe;
    double totalAmount = 0.0;

    double prevAvg = 0;

    public MMATema(int period, long timeframe) {
        this.timeframe = timeframe;
        this.period = period;
        this.stat = new ArrayDeque<>(100);
    }

    public void addStat(double d, long time) {
        stat.addLast(new DoubleLongSymbPrice(d, time));

        totalAmount += d;

        while (!stat.isEmpty()) {
            DoubleLongSymbPrice first = stat.peekFirst();

            if (time - first.l > timeframe)
            {
                totalAmount = totalAmount - first.d;
                stat.removeFirst(); // O(1)
            } else
                break;
        }
    }


    public double getSum() {
        return totalAmount ;
    }

    public double getAvg()
    {
        if (stat.size() > 0)
            return totalAmount / (double) stat.size();
        return 0.0;
    }
}