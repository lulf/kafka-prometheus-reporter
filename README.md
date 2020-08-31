# Kafka Prometheus Reporter

This is a plugin that can be added to a Kafka instance and configure to listen to a given port. The
plugin will expose all Kafka metrics on the broker in a Prometheus interface.

This simplifies configuring Kafka monitoring using Prometheus, as there is no need to setup the
JMX exporter configuration.

To build the plugin:

```
./gradlew shadowJar
```

Copy the resulting jar in `build/libs/kafka-prometheus-reporter-all.jar` into the Kafka classpath.

Configure Kafka to load the plugin:

```
metric.reporters=org.apache.kafka.common.metrics.PrometheusReporter
```
