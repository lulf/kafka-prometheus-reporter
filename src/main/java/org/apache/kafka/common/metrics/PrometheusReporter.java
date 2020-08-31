/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import org.apache.kafka.common.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Exports Kafka metrics as prometheus metrics.
 */
public class PrometheusReporter implements MetricsReporter {

    private static final Logger log = LoggerFactory.getLogger(PrometheusReporter.class);

    public static final String METRICS_CONFIG_PREFIX = "metrics.prometheus.";

    public static final String PORT_CONFIG = METRICS_CONFIG_PREFIX + "http.port";
    public static final String SCRAPE_INTERVAL_CONFIG = METRICS_CONFIG_PREFIX + "scrape.interval.seconds";

    public static final Set<String> RECONFIGURABLE_CONFIGS = Collections.singleton(SCRAPE_INTERVAL_CONFIG);

    private final Map<String, KafkaMetric> metricMap = new HashMap<>();
    private final Map<String, Gauge> collectorMap = new HashMap<>();

    private Duration scrapeInterval;
    private final CollectorRegistry collectorRegistry = new CollectorRegistry(true);
    private HTTPServer httpServer;
    private String namespace = "kafka.server";
    private String broker_id = "0";
    private final ScheduledExecutorService scraperPool = Executors.newScheduledThreadPool(1);

    @Override
    public void configure(Map<String, ?> configs) {
        reconfigure(configs);
        try {
            httpServer = new HTTPServer(new InetSocketAddress(Optional.ofNullable(configs.get(PORT_CONFIG)).map(Object::toString).map(Integer::parseInt).orElse(8080)), collectorRegistry, true);
            log.info("Configuring PrometheusReporter on port {}", configs.get(PORT_CONFIG));
            scraperPool.schedule(new MetricScraper(), scrapeInterval.getSeconds(), TimeUnit.SECONDS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Set<String> reconfigurableConfigs() {
        return RECONFIGURABLE_CONFIGS;
    }

    @Override
    public void validateReconfiguration(Map<String, ?> configs) throws ConfigException {
    }

    @Override
    public void reconfigure(Map<String, ?> configs) {
        synchronized (this) {
            this.scrapeInterval = Duration.ofSeconds(Optional.ofNullable(configs.get(SCRAPE_INTERVAL_CONFIG)).map(Object::toString).map(Integer::parseInt).orElse(10));
        }
    }

    @Override
    public void init(List<KafkaMetric> metrics) {
        synchronized (this) {
            collectorMap.clear();
            for (KafkaMetric metric : metrics) {
                if (metricMap.get(metric.metricName().name()) == null) {
                    metricMap.put(metric.metricName().name(), metric);
                    // log.info("Configuring metric {} (configs {})", metric.metricName().name(), metric.config().tags());
                    Gauge collector = kafkaMetricToCollector(metric);
                    collectorMap.put(metric.metricName().name(), collector);
                    collectorRegistry.register(collector);
                }
            }
        }
    }

    private Gauge kafkaMetricToCollector(KafkaMetric kafkaMetric) {
        String help = kafkaMetric.metricName().description();
        if (help == null || help.isBlank()) {
            help = kafkaMetric.metricName().name();
        }
        Gauge.Builder builder = Gauge.build()
                .name(String.format("%s_%s", namespace.replaceAll("\\.", "_"), kafkaMetric.metricName().name().replaceAll("-", "_")))
                .help(help);
        List<String> labels = new ArrayList<>();
        labels.add("broker_id");
        labels.addAll(kafkaMetric.config().tags().keySet().stream().map(s -> s.replaceAll("-", "_")).collect(Collectors.toList()));
        if (labels.size() > 0) {
            builder.labelNames(labels.toArray(new String[0]));
        }
        return builder.create();
    }

    @Override
    public void metricChange(KafkaMetric metric) {
        synchronized (this) {
            Gauge existing = collectorMap.remove(metric.metricName().name());
            if (existing != null) {
                collectorRegistry.unregister(existing);
                Gauge newCollector = kafkaMetricToCollector(metric);
                collectorMap.put(metric.metricName().name(), newCollector);
                metricMap.put(metric.metricName().name(), metric);
                collectorRegistry.register(newCollector);
            }
        }
    }

    @Override
    public void metricRemoval(KafkaMetric metric) {
        synchronized (this) {
            Gauge collector = collectorMap.remove(metric.metricName().name());
            if (collector != null) {
                metricMap.remove(metric.metricName().name());
                collectorRegistry.unregister(collector);
            }
        }
    }

    @Override
    public void close() {
        if (httpServer != null) {
            httpServer.stop();
            scraperPool.shutdown();
        }
    }

    @Override
    public void contextChange(MetricsContext metricsContext) {
        this.namespace = metricsContext.contextLabels().getOrDefault("_namespace", "kafka.server");
        this.broker_id = metricsContext.contextLabels().getOrDefault("kafka.broker.id", "0");
    }

    private class MetricScraper implements Runnable {

        @Override
        public void run() {
            long now = System.currentTimeMillis();
            synchronized (PrometheusReporter.this) {
                try {
                    for (Map.Entry<String, KafkaMetric> metric : PrometheusReporter.this.metricMap.entrySet()) {
                        Gauge gauge = PrometheusReporter.this.collectorMap.get(metric.getKey());
                        List<String> labels = new ArrayList<>();
                        labels.add(broker_id);
                        labels.addAll(metric.getValue().config().tags().keySet().stream().map(s -> s.replaceAll("-", "_")).collect(Collectors.toList()));
                        double value = metric.getValue().measurableValue(now);
                        // log.info("Setting metric value name {} labels {} (configs {}), to value {}", metric.getKey(), labels, metric.getValue().config().tags(), value);
                        gauge.labels(labels.toArray(new String[0])).set(value);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    PrometheusReporter.this.scraperPool.schedule(this, PrometheusReporter.this.scrapeInterval.getSeconds(), TimeUnit.SECONDS);
                }
            }
        }
    }
}
