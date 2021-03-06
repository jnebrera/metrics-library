package io.wizzie.metrics;

import com.codahale.metrics.*;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jvm.*;

import io.wizzie.metrics.listeners.MetricListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static io.wizzie.metrics.MetricsConstant.*;

public class MetricsManager extends Thread {
    private static final Logger log = LoggerFactory.getLogger(MetricsManager.class);
    public MetricRegistry registry = new MetricRegistry();
    public List<MetricListener> listeners = new ArrayList<>();
    public Set<String> registredMetrics = new HashSet<>();
    public String app_id;

    public Map<String, Object> config;
    public Long interval;
    public AtomicBoolean running = new AtomicBoolean(true);

    AtomicBoolean verboseMode = new AtomicBoolean();

    public MetricsManager(Map<String, Object> config) {
        if ((Boolean) config.getOrDefault(METRIC_ENABLE, false)) {
            this.config = config;
            interval = ConversionUtils.toLong(config.getOrDefault(METRIC_INTERVAL, 60000L));
            app_id = (String) config.get(APPLICATION_ID);
            verboseMode.set((Boolean) config.getOrDefault(METRIC_VERBOSE_MODE, false));
            List<String> listenersClass = (List<String>) config.getOrDefault(METRIC_LISTENERS, Collections.singletonList("io.wizzie.metrics.listeners.ConsoleMetricListener"));
            if (listenersClass != null) {
                for (String listenerClassName : listenersClass) {
                    try {
                        Class listenerClass = Class.forName(listenerClassName);
                        MetricListener metricListener = (MetricListener) listenerClass.newInstance();
                        metricListener.init(new HashMap<>(config));
                        listeners.add(metricListener);
                    } catch (ClassNotFoundException e) {
                        log.error("Couldn't find the class associated with the metric listener {}", listenerClassName);
                    } catch (InstantiationException | IllegalAccessException e) {
                        log.error("Couldn't create the instance associated with the metric listener " + listenerClassName, e);
                    }
                }
            }

            registerJVMMetrics();
            log.info("Start MetricsManager with listeners {}",
                    listeners.stream()
                            .map(MetricListener::name).collect(Collectors.toList()));

            if (listeners.isEmpty()) {
                log.warn("Stop MetricsManager because doesn't have listeners!!");
                running.set(false);
            }
        } else {
            running.set(false);
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            sendAllMetrics();
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void registerJVMMetrics() {
        registerAll("gc", new GarbageCollectorMetricSet());
        registerAll("buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));
        registerAll("memory", new MemoryUsageGaugeSet());
        registerAll("threads", new CachedThreadStatesGaugeSet(10, TimeUnit.SECONDS));
    }

    private void registerAll(String prefix, MetricSet metricSet) {
        for (Map.Entry<String, Metric> entry : metricSet.getMetrics().entrySet()) {
            if (entry.getValue() instanceof MetricSet) {
                registerAll(prefix + "." + entry.getKey(), (MetricSet) entry.getValue());
            } else {
                registry.register(prefix + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    public void registerMetric(String metricName, Metric metric) {
        if (running.get()) {
            if (!registredMetrics.contains(metricName)) {
                registry.register(metricName, metric);
                registredMetrics.add(metricName);
            } else {
                log.warn("The metric with name [{}] is duplicated!", metric);
            }
        } else {
            log.warn("You try to register metric but system is disabled!!");
        }
    }

    public void removeMetric(String metricName) {
        if (running.get()) {
            if (registredMetrics.contains(metricName)) {
                registry.remove(metricName);
                registredMetrics.remove(metricName);
            } else {
                log.warn("Try to delete unregister metric [{}]", metricName);
            }
        } else {
            log.warn("You try to remove metric but system is disabled!!");
        }
    }

    public void clean() {
        registredMetrics.forEach(metric -> registry.remove(metric));
        registredMetrics.clear();
    }

    private void sendGaugeMetric(String metricName) {
        Gauge selectedGauge = registry.getGauges().get(metricName);
        listeners.forEach(listener -> listener.updateMetric(metricName, selectedGauge.getValue()));
    }

    private void sendMeterMetric(String metricName) {
        Meter selectedMeter = registry.getMeters().get(metricName);
        listeners.forEach(listener -> listener.updateMetric(String.format("%s-mean-rate", metricName), selectedMeter.getMeanRate()));

        if(verboseMode.get()) {
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-count", metricName), selectedMeter.getCount()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-1-minute-rate", metricName), selectedMeter.getOneMinuteRate()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-5-minute-rate", metricName), selectedMeter.getFiveMinuteRate()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-15-minute-rate", metricName), selectedMeter.getFifteenMinuteRate()));
        }
    }

    private void sendHistogramMetric(String metricName) {
        Histogram selectedHistogram = registry.getHistograms().get(metricName);
        listeners.forEach(listener -> listener.updateMetric(String.format("%s-count", metricName), selectedHistogram.getCount()));

        if(verboseMode.get()) {
            Snapshot histogramSnapshot = selectedHistogram.getSnapshot();

            listeners.forEach(listener -> listener.updateMetric(String.format("%s-max-value", metricName), histogramSnapshot.getMax()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-min-value", metricName), histogramSnapshot.getMin()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-mean-value", metricName), histogramSnapshot.getMean()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-median-value", metricName), histogramSnapshot.getMedian()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-standard-deviation-value", metricName), histogramSnapshot.getStdDev()));
        }
    }

    private void sendCounterMetric(String metricName) {
        Counter selectedCounter = registry.getCounters().get(metricName);
        listeners.forEach(listener -> listener.updateMetric(metricName, selectedCounter.getCount()));
    }

    private void sendTimerMetric(String metricName) {
        Timer selectedTimer = registry.getTimers().get(metricName);

        listeners.forEach(listener -> listener.updateMetric(String.format("%s-count", metricName), selectedTimer.getCount()));

        if(verboseMode.get()) {
            Snapshot timerSnapshot = selectedTimer.getSnapshot();

            listeners.forEach(listener -> listener.updateMetric(String.format("%s-max-value", metricName), timerSnapshot.getMax()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-min-value", metricName), timerSnapshot.getMin()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-mean-value", metricName), timerSnapshot.getMean()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-median-value", metricName), timerSnapshot.getMedian()));
            listeners.forEach(listener -> listener.updateMetric(String.format("%s-standard-deviation-value", metricName), timerSnapshot.getStdDev()));
        }
    }

    private void sendAllMetrics() {
        registry.getGauges().keySet().forEach(this::sendGaugeMetric);
        registry.getMeters().keySet().forEach(this::sendMeterMetric);
        registry.getHistograms().keySet().forEach(this::sendHistogramMetric);
        registry.getCounters().keySet().forEach(this::sendCounterMetric);
        registry.getTimers().keySet().forEach(this::sendTimerMetric);
    }

    @Override
    public void interrupt() {
        running.set(false);
        listeners.forEach(MetricListener::close);
        log.info("Stop MetricsManager");
    }
}
