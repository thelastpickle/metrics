package com.codahale.metrics.graphite;

import com.codahale.metrics.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

/**
 * A reporter which publishes metric values to a Graphite server.
 *
 * @see <a href="http://graphite.wikidot.com/">Graphite - Scalable Realtime Graphing</a>
 */
public class GraphiteReporter extends ScheduledReporter {
    /**
     * Returns a new {@link Builder} for {@link GraphiteReporter}.
     *
     * @param registry the registry to report
     * @return a {@link Builder} instance for a {@link GraphiteReporter}
     */
    public static Builder forRegistry(MetricRegistry registry) {
        return new Builder(registry);
    }

    /**
     * A builder for {@link GraphiteReporter} instances. Defaults to not using a prefix, using the
     * default clock, converting rates to events/second, converting durations to milliseconds, and
     * not filtering metrics.
     */
    public static class Builder {
        private final MetricRegistry registry;
        private Clock clock;
        private String prefix;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        private MetricFilter filter;

        private Builder(MetricRegistry registry) {
            this.registry = registry;
            this.clock = Clock.defaultClock();
            this.prefix = null;
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.filter = MetricFilter.ALL;
        }

        /**
         * Use the given {@link Clock} instance for the time.
         *
         * @param clock a {@link Clock} instance
         * @return {@code this}
         */
        public Builder withClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Prefix all metric names with the given string.
         *
         * @param prefix the prefix for all metric names
         * @return {@code this}
         */
        public Builder prefixedWith(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /**
         * Convert rates to the given time unit.
         *
         * @param rateUnit a unit of time
         * @return {@code this}
         */
        public Builder convertRatesTo(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Convert durations to the given time unit.
         *
         * @param durationUnit a unit of time
         * @return {@code this}
         */
        public Builder convertDurationsTo(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        /**
         * Only report metrics which match the given filter.
         *
         * @param filter a {@link MetricFilter}
         * @return {@code this}
         */
        public Builder filter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Builds a {@link GraphiteReporter} with the given properties, sending metrics using the
         * given {@link GraphiteSender}.
         *
         * Present for binary compatibility
         *
         * @param graphite a {@link Graphite}
         * @return a {@link GraphiteReporter}
         */
        public GraphiteReporter build(Graphite graphite) {
            return build((GraphiteSender) graphite);
        }

        /**
         * Builds a {@link GraphiteReporter} with the given properties, sending metrics using the
         * given {@link GraphiteSender}.
         *
         * @param graphite a {@link GraphiteSender}
         * @return a {@link GraphiteReporter}
         */
        public GraphiteReporter build(GraphiteSender graphite) {
            return new GraphiteReporter(registry,
                                        graphite,
                                        clock,
                                        prefix,
                                        rateUnit,
                                        durationUnit,
                                        filter);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphiteReporter.class);

    private final GraphiteSender graphite;
    private final Clock clock;
    private final String prefix;
    private final MetricFilter filter;

    private GraphiteReporter(MetricRegistry registry,
                             GraphiteSender graphite,
                             Clock clock,
                             String prefix,
                             TimeUnit rateUnit,
                             TimeUnit durationUnit,
                             MetricFilter filter) {
        super(registry, "graphite-reporter", filter, rateUnit, durationUnit);
        this.graphite = graphite;
        this.clock = clock;
        this.prefix = prefix;
        this.filter = filter;
    }

    @Override
    public void report(SortedMap<String, Gauge> gauges,
                       SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms,
                       SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {
        final long timestamp = clock.getTime() / 1000;

        // oh it'd be lovely to use Java 7 here
        try {
            if (!graphite.isConnected()) {
    	          graphite.connect();
            }

            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                reportGauge(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                reportCounter(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                reportHistogram(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                reportMetered(entry.getKey(), entry.getValue(), timestamp);
            }

            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                reportTimer(entry.getKey(), entry.getValue(), timestamp);
            }

            graphite.flush();
        } catch (IOException e) {
            LOGGER.warn("Unable to report to Graphite", graphite, e);
            try {
                graphite.close();
            } catch (IOException e1) {
                LOGGER.warn("Error closing Graphite", graphite, e1);
            }
        }
    }

    @Override
    public void stop() {
        try {
            super.stop();
        } finally {
            try {
                graphite.close();
            } catch (IOException e) {
                LOGGER.debug("Error disconnecting from Graphite", graphite, e);
            }
        }
    }

    private void reportTimer(String name, Timer timer, long timestamp) throws IOException {
        final Snapshot snapshot = timer.getSnapshot();

        if (filter.matches(name, timer, "max")) {
            send(prefix(name, "max"), convertDuration(snapshot.getMax()), timestamp);
        }
        if (filter.matches(name, timer, "mean")) {
            send(prefix(name, "mean"), convertDuration(snapshot.getMean()), timestamp);
        }
        if (filter.matches(name, timer, "min")) {
            send(prefix(name, "min"), convertDuration(snapshot.getMin()), timestamp);
        }
        if (filter.matches(name, timer, "stddev")) {
            send(prefix(name, "stddev"),
                convertDuration(snapshot.getStdDev()),
                    timestamp);
        }
        if (filter.matches(name, timer, "p50")) {
            send(prefix(name, "p50"),
                convertDuration(snapshot.getMedian()),
                    timestamp);
        }
        if (filter.matches(name, timer, "p75")) {
            send(prefix(name, "p75"),
                convertDuration(snapshot.get75thPercentile()),
                    timestamp);
        }
        if (filter.matches(name, timer, "p95")) {
            send(prefix(name, "p95"),
                convertDuration(snapshot.get95thPercentile()),
                    timestamp);
        }
        if (filter.matches(name, timer, "p98")) {
            send(prefix(name, "p98"),
                convertDuration(snapshot.get98thPercentile()),
                    timestamp);
        }
        if (filter.matches(name, timer, "p99")) {
            send(prefix(name, "p99"),
                convertDuration(snapshot.get99thPercentile()),
                    timestamp);
        }
        if (filter.matches(name, timer, "p999")) {
            send(prefix(name, "p999"),
                convertDuration(snapshot.get999thPercentile()),
                    timestamp);
        }

        reportMetered(name, timer, timestamp);
    }

    private void reportMetered(String name, Metered meter, long timestamp) throws IOException {
        if (filter.matches(name, meter, "count")) {
            graphite.send(prefix(name, "count"), format(meter.getCount()), timestamp);
        }
        if (filter.matches(name, meter, "m1_rate")) {
            send(prefix(name, "m1_rate"),
                convertRate(meter.getOneMinuteRate()),
                    timestamp);
        }
        if (filter.matches(name, meter, "m5_rate")) {
            send(prefix(name, "m5_rate"),
                convertRate(meter.getFiveMinuteRate()),
                    timestamp);
        }
        if (filter.matches(name, meter, "m15_rate")) {
            send(prefix(name, "m15_rate"),
                convertRate(meter.getFifteenMinuteRate()),
                    timestamp);
        }
        if (filter.matches(name, meter, "mean_rate")) {
            send(prefix(name, "mean_rate"),
                convertRate(meter.getMeanRate()),
                    timestamp);
        }
    }

    private void reportHistogram(String name, Histogram histogram, long timestamp) throws IOException {
        final Snapshot snapshot = histogram.getSnapshot();
        if (filter.matches(name, histogram, "count")) {
            graphite.send(prefix(name, "count"), format(histogram.getCount()), timestamp);
        }
        if (filter.matches(name, histogram, "max")) {
            graphite.send(prefix(name, "max"), format(snapshot.getMax()), timestamp);
        }
        if (filter.matches(name, histogram, "mean")) {
            send(prefix(name, "mean"), snapshot.getMean(), timestamp);
        }
        if (filter.matches(name, histogram, "min")) {
            graphite.send(prefix(name, "min"), format(snapshot.getMin()), timestamp);
        }
        if (filter.matches(name, histogram, "stddev")) {
            send(prefix(name, "stddev"), snapshot.getStdDev(), timestamp);
        }
        if (filter.matches(name, histogram, "p50")) {
            send(prefix(name, "p50"), snapshot.getMedian(), timestamp);
        }
        if (filter.matches(name, histogram, "p75")) {
            send(prefix(name, "p75"), snapshot.get75thPercentile(), timestamp);
        }
        if (filter.matches(name, histogram, "p95")) {
            send(prefix(name, "p95"), snapshot.get95thPercentile(), timestamp);
        }
        if (filter.matches(name, histogram, "p98")) {
            send(prefix(name, "p98"), snapshot.get98thPercentile(), timestamp);
        }
        if (filter.matches(name, histogram, "p99")) {
            send(prefix(name, "p99"), snapshot.get99thPercentile(), timestamp);
        }
        if (filter.matches(name, histogram, "p999")) {
            send(prefix(name, "p999"), snapshot.get999thPercentile(), timestamp);
        }
    }

    private void reportCounter(String name, Counter counter, long timestamp) throws IOException {
        graphite.send(prefix(name, "count"), format(counter.getCount()), timestamp);
    }

    private void reportGauge(String name, Gauge gauge, long timestamp) throws IOException {
        final String value = format(gauge.getValue());
        if (value != null) {
            graphite.send(prefix(name), value, timestamp);
        }
    }

    private void send(String metric, double value, long time) throws IOException {
        if (Double.isNaN(value)) {
            return;
        }
        graphite.send(metric, format(value), time);
    }

    private String format(Object o) {
        if (o instanceof Float) {
            return format(((Float) o).doubleValue());
        } else if (o instanceof Double) {
            final double doubleValue = ((Double) o).doubleValue();
            if (Double.isNaN(doubleValue)) {
                return null;
            }
            return format(doubleValue);
        } else if (o instanceof Byte) {
            return format(((Byte) o).longValue());
        } else if (o instanceof Short) {
            return format(((Short) o).longValue());
        } else if (o instanceof Integer) {
            return format(((Integer) o).longValue());
        } else if (o instanceof Long) {
            return format(((Long) o).longValue());
        }
        return null;
    }

    private String prefix(String... components) {
        return MetricRegistry.name(prefix, components);
    }

    private String format(long n) {
        return Long.toString(n);
    }

    private String format(double v) {
        // the Carbon plaintext format is pretty underspecified, but it seems like it just wants
        // US-formatted digits
        return String.format(Locale.US, "%2.2f", v);
    }
}
