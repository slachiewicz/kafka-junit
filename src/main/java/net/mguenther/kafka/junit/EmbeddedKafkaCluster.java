package net.mguenther.kafka.junit;

import kafka.admin.AdminUtils;
import kafka.admin.RackAwareMode;
import kafka.common.TopicAlreadyMarkedForDeletionException;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.TopicExistsException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class EmbeddedKafkaCluster implements EmbeddedLifecycle, RecordProducer, RecordConsumer, TopicManager, AutoCloseable {

    private final EmbeddedKafkaClusterConfig config;

    private EmbeddedZooKeeper zooKeeper;

    private EmbeddedKafka broker;

    private EmbeddedConnect connect;

    @Override
    public void start() {

        try {

            zooKeeper = new EmbeddedZooKeeper(config.getZooKeeperConfig());
            zooKeeper.start();

            broker = new EmbeddedKafka(config.getKafkaConfig(), zooKeeper.getConnectString());
            broker.start();

            if (config.usesConnect()) {
                connect = new EmbeddedConnect(config.getConnectConfig(), getBrokerList());
                connect.start();
            }

        } catch (Exception e) {
            throw new RuntimeException("Unable to start the embedded Kafka cluster.", e);
        }
    }

    @Override
    public void stop() {

        if (connect != null) {
            connect.stop();
        }

        broker.stop();
        zooKeeper.stop();
    }

    public String getBrokerList() {
        return broker.getBrokerList();
    }

    public static EmbeddedKafkaCluster provisionWith(final EmbeddedKafkaClusterConfig config) {
        return new EmbeddedKafkaCluster(config);
    }

    private Properties effectiveProducerProps(final Properties originalProducerProps) {
        final Properties effectiveProducerProps = new Properties();
        effectiveProducerProps.putAll(originalProducerProps);
        effectiveProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getBrokerList());
        return effectiveProducerProps;
    }

    private Properties effectiveConsumerProps(final Properties originalConsumerProps) {
        final Properties effectiveConsumerProps = new Properties();
        effectiveConsumerProps.putAll(originalConsumerProps);
        effectiveConsumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getBrokerList());
        return effectiveConsumerProps;
    }

    @Override
    public <K, V> List<RecordMetadata> send(final SendKeyValues<K, V> sendRequest) throws ExecutionException, InterruptedException {
        final Producer<K, V> producer = new KafkaProducer<>(effectiveProducerProps(sendRequest.getProducerProps()));
        final List<RecordMetadata> metadata = new ArrayList<>(sendRequest.getRecords().size());
        try {
            for (KeyValue<K, V> record : sendRequest.getRecords()) {
                final ProducerRecord<K, V> producerRecord = new ProducerRecord<>(sendRequest.getTopic(), null, record.getKey(), record.getValue(), record.getHeaders());
                final Future<RecordMetadata> f = producer.send(producerRecord);
                metadata.add(f.get());
            }
        } finally {
            producer.flush();
            producer.close();
        }
        return Collections.unmodifiableList(metadata);
    }

    @Override
    public <K, V> List<RecordMetadata> send(final SendKeyValuesTransactional<K, V> sendRequest) throws ExecutionException, InterruptedException {
        final Producer<K, V> producer = new KafkaProducer<>(effectiveProducerProps(sendRequest.getProducerProps()));
        final List<RecordMetadata> metadata = new ArrayList<>();
        try {
            producer.initTransactions();
            producer.beginTransaction();
            for (String topic : sendRequest.getRecordsPerTopic().keySet()) {
                for (KeyValue<K, V> record : sendRequest.getRecordsPerTopic().get(topic)) {
                    final ProducerRecord<K, V> producerRecord = new ProducerRecord<K, V>(topic, null, record.getKey(), record.getValue(), record.getHeaders());
                    final Future<RecordMetadata> f = producer.send(producerRecord);
                    metadata.add(f.get());
                }
            }
            producer.commitTransaction();
        } catch (ProducerFencedException e) {
            producer.abortTransaction();
            final String message = String.format(
                    "There happens to be another producer that shares the transactional ID '%s'" +
                    "with this producer, but that has a newer epoch assigned to it. This producer " +
                    "has been fenced off, it can no longer write to the log transactionally. Hence, " +
                    "the ongoing transaction is aborted and the producer closed.",
                    sendRequest.getProducerProps().get(ProducerConfig.TRANSACTIONAL_ID_CONFIG));
            throw new RuntimeException(message, e);
        } catch (OutOfOrderSequenceException e) {
            producer.abortTransaction();
            final String message = "This producer has received out-of-band sequence numbers. This is a fatal condition " +
                                   "and thus, the producer is no longer able to log transactionally and reliably. " +
                                   "Hence, the ongoing transaction is aborted and the producer closed.";
            throw new RuntimeException(message, e);
        } finally {
            producer.flush();
            producer.close();
        }
        return Collections.unmodifiableList(metadata);
    }

    @Override
    public <V> List<RecordMetadata> send(final SendValues<V> sendRequest) throws ExecutionException, InterruptedException {
        final Collection<KeyValue<String, V>> records = sendRequest.getValues()
                .stream()
                .map(value -> new KeyValue<>((String) null, value))
                .collect(Collectors.toList());
        final SendKeyValues<String, V> keyValueRequest = SendKeyValues
                .to(sendRequest.getTopic(), records)
                .withAll(sendRequest.getProducerProps())
                .build();
        return send(keyValueRequest);
    }

    @Override
    public <V> List<RecordMetadata> send(final SendValuesTransactional<V> sendRequest) throws ExecutionException, InterruptedException {
        final Map<String, Collection<KeyValue<String, V>>> recordsPerTopic = new HashMap<>();
        for (String topic : sendRequest.getValuesPerTopic().keySet()) {
            final Collection<KeyValue<String, V>> records = sendRequest.getValuesPerTopic().get(topic)
                    .stream()
                    .map(value -> new KeyValue<>((String) null, value))
                    .collect(Collectors.toList());
            recordsPerTopic.put(topic, records);
        }
        final SendKeyValuesTransactional<String, V> keyValueRequest = SendKeyValuesTransactional.inTransaction(recordsPerTopic)
                .withAll(sendRequest.getProducerProps())
                .build();
        return send(keyValueRequest);
    }

    @Override
    public <V> List<V> readValues(final ReadKeyValues<String, V> readRequest) {
        final List<KeyValue<String, V>> kvs = read(readRequest);
        return Collections.unmodifiableList(kvs.stream().map(KeyValue::getValue).collect(Collectors.toList()));
    }

    @Override
    public <K, V> List<KeyValue<K, V>> read(final ReadKeyValues<K, V> readRequest) {
        final List<KeyValue<K, V>> consumedRecords = new ArrayList<>();
        final KafkaConsumer<K, V> consumer = new KafkaConsumer<>(effectiveConsumerProps(readRequest.getConsumerProps()));
        final int pollIntervalMillis = 100;
        final int limit = readRequest.getLimit();
        final Predicate<K> filterOnKeys = readRequest.getFilterOnKeys();
        final Predicate<V> filterOnValues = readRequest.getFilterOnValues();
        consumer.subscribe(Collections.singletonList(readRequest.getTopic()));
        int totalPollTimeMillis = 0;
        while (totalPollTimeMillis < readRequest.getMaxTotalPollTimeMillis() && continueConsuming(consumedRecords.size(), limit)) {
            final ConsumerRecords<K, V> records = consumer.poll(pollIntervalMillis);
            for (ConsumerRecord<K, V> record : records) {
                if (filterOnKeys.test(record.key()) && filterOnValues.test(record.value())) {
                    consumedRecords.add(new KeyValue<>(record.key(), record.value(), record.headers()));
                }
            }
            totalPollTimeMillis += pollIntervalMillis;
        }
        consumer.commitSync();
        consumer.close();
        return Collections.unmodifiableList(consumedRecords);
    }

    private static boolean continueConsuming(final int messagesConsumed, final int maxMessages) {
        return maxMessages <= 0 || messagesConsumed < maxMessages;
    }

    @Override
    public <V> List<V> observeValues(final ObserveKeyValues<String, V> observeRequest) throws InterruptedException {
        final List<V> totalConsumedRecords = new ArrayList<>(observeRequest.getExpected());
        final long startNanos = System.nanoTime();
        while (true) {
            final ReadKeyValues<String, V> readRequest = ReadKeyValues.from(observeRequest.getTopic(), observeRequest.getClazzOfV())
                    .withAll(observeRequest.getConsumerProps())
                    .withLimit(observeRequest.getExpected())
                    .filterOnKeys(observeRequest.getFilterOnKeys())
                    .filterOnValues(observeRequest.getFilterOnValues())
                    .build();
            final List<V> consumedRecords = readValues(readRequest);
            totalConsumedRecords.addAll(consumedRecords);
            if (totalConsumedRecords.size() >= observeRequest.getExpected()) break;
            if (System.nanoTime() > startNanos + TimeUnit.MILLISECONDS.toNanos(observeRequest.getObservationTimeMillis())) {
                final String message = String.format("Expected %s records, but consumed only %s records before ran " +
                                "into timeout (%s ms).",
                        observeRequest.getExpected(),
                        totalConsumedRecords.size(),
                        observeRequest.getObservationTimeMillis());
                throw new AssertionError(message);
            }
            Thread.sleep(Math.min(observeRequest.getObservationTimeMillis(), 100));
        }
        return Collections.unmodifiableList(totalConsumedRecords);
    }

    @Override
    public <K, V> List<KeyValue<K, V>> observe(final ObserveKeyValues<K, V> observeRequest) throws InterruptedException {
        final List<KeyValue<K, V>> totalConsumedRecords = new ArrayList<>(observeRequest.getExpected());
        final long startNanos = System.nanoTime();
        final ReadKeyValues<K, V> readRequest = ReadKeyValues.from(observeRequest.getTopic(), observeRequest.getClazzOfK(), observeRequest.getClazzOfV())
                .withAll(observeRequest.getConsumerProps())
                .withLimit(observeRequest.getExpected())
                .filterOnKeys(observeRequest.getFilterOnKeys())
                .filterOnValues(observeRequest.getFilterOnValues())
                .build();
        while (true) {
            final List<KeyValue<K, V>> consumedRecords = read(readRequest);
            totalConsumedRecords.addAll(consumedRecords);
            if (totalConsumedRecords.size() >= observeRequest.getExpected()) break;
            if (System.nanoTime() > startNanos + TimeUnit.MILLISECONDS.toNanos(observeRequest.getObservationTimeMillis())) {
                final String message = String.format("Expected %s records, but consumed only %s records before ran " +
                                                     "into timeout (%s ms).",
                                                     observeRequest.getExpected(),
                                                     totalConsumedRecords.size(),
                                                     observeRequest.getObservationTimeMillis());
                throw new AssertionError(message);
            }
            Thread.sleep(Math.min(observeRequest.getObservationTimeMillis(), 100));
        }
        return Collections.unmodifiableList(totalConsumedRecords);
    }

    @Override
    public void createTopic(final TopicConfig topicConfig) {
        ZkUtils zkUtils = null;
        try {
            zkUtils = get();
            AdminUtils.createTopic(
                    zkUtils,
                    topicConfig.getTopic(),
                    topicConfig.getNumberOfPartitions(),
                    topicConfig.getNumberOfReplicas(),
                    topicConfig.getProperties(),
                    RackAwareMode.Enforced$.MODULE$);
            log.info("Created topic '{}' with settings {}.", topicConfig.getTopic(), topicConfig);
        } catch (IllegalArgumentException | InvalidTopicException e) {
            throw new RuntimeException("Invalid topic settings.", e);
        } catch (TopicExistsException e) {
            throw new RuntimeException(String.format("The topic '%s' already exists.", topicConfig.getTopic()), e);
        } catch (Exception e) {
            throw new RuntimeException(String.format("Unable to create topic '%s'.", topicConfig.getTopic()), e);
        } finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
    }

    @Override
    public void deleteTopic(final String topic) {
        ZkUtils zkUtils = null;
        try {
            zkUtils = get();
            AdminUtils.deleteTopic(zkUtils, topic);
            log.info("Marked topic '{}' for deletion.", topic);
        } catch (TopicAlreadyMarkedForDeletionException e) {
            throw new RuntimeException(String.format("The topic '%s' has already been marked for deletion.", topic), e);
        } catch (Exception e) {
            throw new RuntimeException(String.format("Unable to delete topic '%s'.", topic), e);
        } finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
    }

    @Override
    public boolean exists(final String topic) {
        ZkUtils zkUtils = null;
        try {
            zkUtils = get();
            return AdminUtils.topicExists(zkUtils, topic);
        } catch (Exception e) {
            throw new RuntimeException(String.format("Unable to query the state of topic '%s'.", topic), e);
        } finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
    }

    private ZkUtils get() {
        final ZkClient zkClient = new ZkClient(
                zooKeeper.getConnectString(),
                10_000,
                8_000,
                ZKStringSerializer$.MODULE$);
        final ZkConnection zkConnection = new ZkConnection(zooKeeper.getConnectString());
        return new ZkUtils(zkClient, zkConnection, false);
    }

    @Override
    public void close() throws Exception {
        stop();
    }
}