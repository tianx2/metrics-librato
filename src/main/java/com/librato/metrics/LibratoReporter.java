package com.librato.metrics;

import com.codahale.metrics.*;
import com.codahale.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A reporter for publishing metrics to <a href="http://metrics.librato.com/">Librato Metrics</a>
 */
public class LibratoReporter extends ScheduledReporter implements MetricsLibratoBatch.RateConverter, MetricsLibratoBatch.DurationConverter {
    private static final Logger LOG = LoggerFactory.getLogger(LibratoReporter.class);
    private final DeltaTracker deltaTracker;
    private final String source;
    private final long timeout;
    private final TimeUnit timeoutUnit;
    private final Sanitizer sanitizer;
    private final HttpPoster httpPoster;
    private final String prefix;
    private final String prefixDelimiter;
    private final Pattern sourceRegex;
    protected final MetricRegistry registry;
    protected final Clock clock;
    protected final MetricExpansionConfig expansionConfig;

    /**
     * Private. Use builder instead.
     */
    private LibratoReporter(MetricRegistry registry,
                            String name,
                            MetricFilter filter,
                            TimeUnit rateUnit,
                            TimeUnit durationUnit,
                            Sanitizer customSanitizer,
                            String source,
                            long timeout,
                            TimeUnit timeoutUnit,
                            Clock clock,
                            MetricExpansionConfig expansionConfig,
                            HttpPoster httpPoster,
                            String prefix,
                            String prefixDelimiter,
                            Pattern sourceRegex) {
        super(registry, name, filter, rateUnit, durationUnit);
        this.registry = registry;
        this.sanitizer = customSanitizer;
        this.source = source;
        this.timeout = timeout;
        this.timeoutUnit = timeoutUnit;
        this.clock = clock;
        this.expansionConfig = expansionConfig;
        this.httpPoster = httpPoster;
        this.prefix = prefix;
        this.prefixDelimiter = prefixDelimiter;
        this.sourceRegex = sourceRegex;
        this.deltaTracker = new DeltaTracker(new DeltaMetricSupplier(registry, filter));
    }

    public double convertMetricDuration(double duration) {
        return convertDuration(duration);
    }

    public double convertMetricRate(double rate) {
        return convertRate(rate);
    }

    /**
     * Used to supply metrics to the delta tracker on initialization. Uses the metric name conversion
     * to ensure that the correct names are supplied for the metric.
     */
    class DeltaMetricSupplier implements DeltaTracker.MetricSupplier {
        final MetricRegistry registry;
        final MetricFilter filter;

        DeltaMetricSupplier(MetricRegistry registry, MetricFilter filter) {
            this.registry = registry;
            this.filter = filter;
        }

        public Map<String, Metric> getMetrics() {
            final Map<String, Metric> map = new HashMap<String, Metric>();
            for (Map.Entry<String, Metric> entry : registry.getMetrics().entrySet()) {
                // todo: ensure the name here is what we expect
                final String name = entry.getKey();
                map.put(name, entry.getValue());
            }
            return map;
        }
    }

    /**
     * Starts the reporter polling at the given period.
     *
     * @param period the amount of time between polls
     * @param unit   the unit for {@code period}
     */
    @Override
    public void start(long period, TimeUnit unit) {
        LOG.debug("Reporter starting at fixed rate of every {} {}", period, unit);
        super.start(period, unit);
    }

    @Override
    public void report(SortedMap<String, Gauge> gauges,
                       SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms,
                       SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {
        final long epoch = TimeUnit.MILLISECONDS.toSeconds(clock.getTime());
        final MetricsLibratoBatch batch = new MetricsLibratoBatch(
                LibratoBatch.DEFAULT_BATCH_SIZE,
                sanitizer,
                timeout,
                timeoutUnit,
                expansionConfig,
                httpPoster,
                prefix,
                prefixDelimiter,
                deltaTracker,
                this,
                this,
                sourceRegex);

        for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
            batch.addGauge(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Counter> entry : counters.entrySet()) {
            batch.addCounter(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
            batch.addHistogram(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Meter> entry : meters.entrySet()) {
            batch.addMeter(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Timer> entry : timers.entrySet()) {
            batch.addTimer(entry.getKey(), entry.getValue());
        }
        batch.post(source, epoch);
    }

    /**
     * A builder for the LibratoReporter class that requires things that cannot be inferred and uses
     * sane default values for everything else.
     */
    public static class Builder {
        private final String source;
        private Sanitizer sanitizer = Sanitizer.NO_OP;
        private long timeout = 5;
        private TimeUnit timeoutUnit = TimeUnit.SECONDS;
        private TimeUnit rateUnit = TimeUnit.SECONDS;
        private TimeUnit durationUnit = TimeUnit.MILLISECONDS;
        private String name = "librato-reporter";
        private final MetricRegistry registry;
        private MetricFilter filter = MetricFilter.ALL;
        private Clock clock = Clock.defaultClock();
        private MetricExpansionConfig expansionConfig = MetricExpansionConfig.ALL;
        private HttpPoster httpPoster;
        private String prefix;
        private String prefixDelimiter = ".";
        private Pattern sourceRegex;

        public Builder(MetricRegistry registry, String username, String token, String source) {
            this.registry = registry;
            this.source = source;
            this.httpPoster = NingHttpPoster.newPoster(username, token, "https://metrics-api.librato.com/v1/metrics");
        }

        /**
         * Sets the source regular expression to be applied against metric names to determine dynamic sources.
         *
         * @param sourceRegex the regular expression
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setSourceRegex(Pattern sourceRegex) {
            this.sourceRegex = sourceRegex;
            return this;
        }

        /**
         * Sets the timeout for POSTs to Librato
         *
         * @param timeout the timeout
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setTimeout(long timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the timeout time unit for POSTs to Librato
         *
         * @param timeoutUnit the timeout unit
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setTimeoutUnit(TimeUnit timeoutUnit) {
            this.timeoutUnit = timeoutUnit;
            return this;
        }

        /**
         * Sets the delimiter which will separate the prefix from the metric name. Defaults
         * to "."
         *
         * @param prefixDelimiter the delimiter
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setPrefixDelimiter(String prefixDelimiter) {
            this.prefixDelimiter = prefixDelimiter;
            return this;
        }

        /**
         * Sets a prefix that will be prepended to all metric names
         *
         * @param prefix the prefix
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setPrefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /**
         * Sets the {@link HttpPoster} which will send the payload to Librato
         *
         * @param poster the HttpPoster
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setHttpPoster(HttpPoster poster) {
            this.httpPoster = poster;
            return this;
        }

        /**
         * Sets the time unit to which rates will be converted by the reporter.  The default
         * value is TimeUnit.SECONDS
         *
         * @param rateUnit the rate
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setRateUnit(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Sets the time unit to which durations will be converted by the reporter. The default
         * value is TimeUnit.MILLISECONDS
         *
         * @param durationUnit the time unit
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setDurationUnit(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        /**
         * set the HTTP timeout for a publishing attempt
         *
         * @param timeout     duration to expect a response
         * @param timeoutUnit unit for duration
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setTimeout(long timeout, TimeUnit timeoutUnit) {
            this.timeout = timeout;
            this.timeoutUnit = timeoutUnit;
            return this;
        }

        /**
         * Specify a custom name for this reporter
         *
         * @param name the name to be used
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        /**
         * Use a custom sanitizer. All metric names are run through a sanitizer to ensure validity before being sent
         * along. Librato places some restrictions on the characters allowed in keys, so all keys are ultimately run
         * through APIUtil.lastPassSanitizer. Specifying an additional sanitizer (that runs before lastPassSanitizer)
         * allows the user to customize what they want done about invalid characters and excessively long metric names.
         *
         * @param sanitizer the custom sanitizer to use  (defaults to a noop sanitizer).
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setSanitizer(Sanitizer sanitizer) {
            this.sanitizer = sanitizer;
            return this;
        }

        /**
         * Filter the metrics that this particular reporter publishes
         *
         * @param filter the {@link MetricFilter}
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setFilter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        /**
         * use a custom clock
         *
         * @param clock to be used
         * @return itself
         */
        @SuppressWarnings("unused")
        public Builder setClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Enables control over how the reporter generates 'expanded' metrics from meters and histograms,
         * such as percentiles and rates.
         *
         * @param expansionConfig the configuration
         * @return itself
         * @see {@link ExpandedMetric}
         */
        @SuppressWarnings("unused")
        public Builder setExpansionConfig(MetricExpansionConfig expansionConfig) {
            this.expansionConfig = expansionConfig;
            return this;
        }

        /**
         * Build the LibratoReporter as configured by this Builder
         *
         * @return a fully configured LibratoReporter
         */
        public LibratoReporter build() {
            return new LibratoReporter(
                    registry,
                    name,
                    filter,
                    rateUnit,
                    durationUnit,
                    sanitizer,
                    source,
                    timeout,
                    timeoutUnit,
                    clock,
                    expansionConfig,
                    httpPoster,
                    prefix,
                    prefixDelimiter,
                    sourceRegex);
        }
    }

    /**
     * convenience method for creating a Builder
     */
    public static Builder builder(MetricRegistry metricRegistry, String username, String token, String source) {
        return new Builder(metricRegistry, username, token, source);
    }

    public static enum ExpandedMetric {
        // sampling
        MEDIAN("median"),
        PCT_75("75th"),
        PCT_95("95th"),
        PCT_98("98th"),
        PCT_99("99th"),
        PCT_999("999th"),
        // metered
        COUNT("count"),
        RATE_MEAN("meanRate"),
        RATE_1_MINUTE("1MinuteRate"),
        RATE_5_MINUTE("5MinuteRate"),
        RATE_15_MINUTE("15MinuteRate");

        private final String displayName;

        public String buildMetricName(String metric) {
            return metric + "." + displayName;
        }

        private ExpandedMetric(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * Configures how to report "expanded" metrics derived from meters and histograms (e.g. percentiles,
     * rates, etc). Default is to report everything.
     *
     * @see ExpandedMetric
     */
    public static class MetricExpansionConfig {
        public static MetricExpansionConfig ALL = new MetricExpansionConfig(EnumSet.allOf(ExpandedMetric.class));
        private final Set<ExpandedMetric> enabled;

        public MetricExpansionConfig(Set<ExpandedMetric> enabled) {
            this.enabled = EnumSet.copyOf(enabled);
        }

        public boolean isSet(ExpandedMetric metric) {
            return enabled.contains(metric);
        }
    }

    /**
     * @param builder  a LibratoReporter.Builder
     * @param interval the interval at which the metrics are to be reporter
     * @param unit     the timeunit for interval
     */
    public static void enable(Builder builder, long interval, TimeUnit unit) {
        builder.build().start(interval, unit);
    }
}
