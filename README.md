# Kafka Prometheus Reporter

This is a plugin that can be added to an Apache Kafka instance and configured to expose a Prometheus HTTP interface on a given port. The metrics reporter API is used to plug into a Kafka instance and convert all Kafka metrics to Prometheus metrics (All using `gauge` metric type).

This simplifies configuring Kafka monitoring using Prometheus, as there is no need to setup the
[JMX exporter](https://github.com/prometheus/jmx_exporter) which require a bit more configuration.

To build the plugin:

```
./gradlew shadowJar
```

Copy the resulting jar in `build/libs/kafka-prometheus-reporter-all.jar` into the Kafka classpath.

Configure Kafka to load the plugin and some plugin properties:

```
metric.reporters=org.apache.kafka.common.metrics.PrometheusReporter
metrics.prometheus.http.port=8080
metrics.prometheus.scrape.interval.seconds=10
```
