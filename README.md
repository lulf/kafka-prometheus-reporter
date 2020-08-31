# Kafka Prometheus Reporter

This is a plugin that can be added to a Kafka instance and configure to listen to a given port. The
plugin will expose all Kafka metrics on the broker in a Prometheus interface.

This simplifies configuring Kafka monitoring using Prometheus, as there is no need to setup the
JMX exporter configuration.
