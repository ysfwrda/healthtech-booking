package com.healthtech.appointment.outbox;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// @DirtiesContext: this class's OutboxRelay/OutboxPruningJob schedulers keep running on
// background threads after the class's static containers are torn down unless the context
// itself is closed, otherwise they spam "connection refused" against dead containers for the
// rest of the suite and burn CPU that other test classes need.
//
// The relay's own fixed delay is pushed out to an hour: these tests invoke relayBatch()
// directly to get deterministic control over claiming, and the live background scheduler
// would otherwise race those direct calls as an uncontrolled third claimant.
@SpringBootTest(properties = "outbox.relay.fixed-delay-ms=3600000")
@Testcontainers
@DirtiesContext
class OutboxRelayIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    @ServiceConnection
    static ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.0")
            .withStartupTimeout(Duration.ofMinutes(3));

    // OutboxKafkaConfig builds its ProducerFactory from the literal "spring.kafka.bootstrap-servers"
    // property via @Value, bypassing the KafkaConnectionDetails bean that @ServiceConnection relies on.
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    OutboxRepository outboxRepository;
    @Autowired
    OutboxRelay outboxRelay;
    @Autowired
    PlatformTransactionManager transactionManager;

    private Consumer<String, String> testConsumer(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private OutboxMessage pendingRow(String topic, String correlationId) {
        return OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID().toString())
                .topic(topic)
                .payload("{\"hello\":\"world\"}")
                .correlationId(correlationId)
                .build();
    }

    @Test
    void relay_publishesCorrelationIdFromStoredColumn_notFreshlyGenerated() {
        String topic = "relay-correlation-test";
        String storedCorrelationId = "stored-correlation-" + UUID.randomUUID();
        OutboxMessage row = outboxRepository.save(pendingRow(topic, storedCorrelationId));

        try (Consumer<String, String> consumer = testConsumer(topic)) {
            outboxRelay.relayBatch();

            ConsumerRecord<String, String> record = Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> firstMatching(consumer, row.getAggregateId()),
                            java.util.Objects::nonNull);

            Header header = record.headers().lastHeader("X-Correlation-Id");
            assertThat(header).isNotNull();
            assertThat(new String(header.value(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo(storedCorrelationId);
        }

        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void relay_skipsHeader_whenCorrelationIdColumnIsNull() {
        String topic = "relay-no-correlation-test";
        OutboxMessage row = outboxRepository.save(pendingRow(topic, null));

        try (Consumer<String, String> consumer = testConsumer(topic)) {
            outboxRelay.relayBatch();

            ConsumerRecord<String, String> record = Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> firstMatching(consumer, row.getAggregateId()),
                            java.util.Objects::nonNull);

            assertThat(record.headers().lastHeader("X-Correlation-Id")).isNull();
        }
    }

    // Seeds N unpublished rows and invokes the relay's poll method directly from two threads via
    // an ExecutorService. A single Spring-scheduled instance can never race itself, so this is the
    // only way to exercise FOR UPDATE SKIP LOCKED: every row must be published exactly once and the
    // topic's record count must equal N, proving concurrent relays claim disjoint batches.
    @Test
    void relay_concurrentInvocations_publishEachRowExactlyOnce() throws Exception {
        String topic = "relay-concurrency-test";
        int rowCount = 40;
        List<OutboxMessage> seeded = IntStream.range(0, rowCount)
                .mapToObj(i -> outboxRepository.save(pendingRow(topic, null)))
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Consumer<String, String> consumer = testConsumer(topic)) {
            // Prime partition assignment before producing, so the poll loop below sees records
            // from the beginning rather than missing ones produced before the first assignment.
            consumer.poll(Duration.ofMillis(500));

            List<java.util.concurrent.Future<?>> futures = List.of(
                    executor.submit(outboxRelay::relayBatch),
                    executor.submit(outboxRelay::relayBatch));
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            // Polled from this thread in a plain loop rather than via Awaitility: KafkaConsumer
            // is not thread-safe, and Awaitility evaluates its condition on its own background
            // thread, which risks a second poll() call overlapping the test thread's under load.
            Map<String, Integer> countsByKey = new HashMap<>();
            int total = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (total < rowCount && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> record : records) {
                    countsByKey.merge(record.key(), 1, Integer::sum);
                    total++;
                }
            }

            assertThat(total).isEqualTo(rowCount);
            assertThat(countsByKey).hasSize(rowCount);
            assertThat(countsByKey.values()).allMatch(count -> count == 1);
        } finally {
            executor.shutdownNow();
        }

        List<OutboxMessage> refreshed = outboxRepository.findAllById(seeded.stream().map(OutboxMessage::getId).toList());
        assertThat(refreshed).hasSize(rowCount);
        assertThat(refreshed).allMatch(row -> row.getPublishedAt() != null);
    }

    // The Kafka-backed concurrency test above proves exactly-once delivery, but that outcome
    // would hold even with plain FOR UPDATE (no SKIP LOCKED): Postgres re-checks a blocked row's
    // WHERE clause once the holder commits, so a waiter never double-processes an
    // already-published row either way - it just blocks first. The actual thing SKIP LOCKED
    // buys is not blocking on a row someone else already holds, which this test verifies
    // directly: one thread holds the claim transaction open, and a second thread's claimBatch()
    // must return immediately with a disjoint (here: empty) result instead of waiting for the
    // first to release its locks.
    @Test
    void claimBatch_doesNotBlockOnRowsLockedByAnotherTransaction() throws Exception {
        String topic = "relay-lock-contention-test";
        int rowCount = 10;
        IntStream.range(0, rowCount).forEach(i -> outboxRepository.save(pendingRow(topic, null)));

        CountDownLatch holderHasClaimed = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> holderFuture = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        int claimed = outboxRepository.claimBatch(rowCount).size();
                        holderHasClaimed.countDown();
                        try {
                            releaseHolder.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return claimed;
                    }));

            assertThat(holderHasClaimed.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            Future<Integer> waiterFuture = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> outboxRepository.claimBatch(rowCount).size()));
            int waiterClaimed = waiterFuture.get(2, TimeUnit.SECONDS);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            // The holder sleeps for up to 10s while holding its locks; a waiter that blocked on
            // them would take that long too, so completing in well under a second proves it
            // skipped rather than waited.
            assertThat(elapsedMs).isLessThan(1000);
            assertThat(waiterClaimed).isZero();

            releaseHolder.countDown();
            assertThat(holderFuture.get(10, TimeUnit.SECONDS)).isEqualTo(rowCount);
        } finally {
            executor.shutdownNow();
        }
    }

    private ConsumerRecord<String, String> firstMatching(Consumer<String, String> consumer, String expectedKey) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
        for (ConsumerRecord<String, String> record : records) {
            if (expectedKey.equals(record.key())) {
                return record;
            }
        }
        return null;
    }
}
