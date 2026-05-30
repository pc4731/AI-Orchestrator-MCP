package com.orchestration.bus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMessageBusTest {

    private static MessageBus.Message message(String topic, String id) {
        return new MessageBus.Message(id, new MessageBus.Topic(topic), "sender", "corr",
                Map.of("k", "v"), Instant.now());
    }

    /** A bus with synchronous delivery for deterministic assertions. */
    private static InMemoryMessageBus syncBus() {
        return new InMemoryMessageBus(Executors.newVirtualThreadPerTaskExecutor(), false);
    }

    @Test
    void deliversAsynchronouslyToSubscriberOfTopic() throws Exception {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<MessageBus.Message> received = new AtomicReference<>();
        bus.subscribe(new MessageBus.Topic("t1"), m -> {
            received.set(m);
            latch.countDown();
        });

        bus.publish(message("t1", "1"));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("1", received.get().id());
        bus.shutdown();
    }

    @Test
    void doesNotDeliverToOtherTopics() {
        InMemoryMessageBus bus = syncBus();
        AtomicInteger count = new AtomicInteger();
        bus.subscribe(new MessageBus.Topic("t1"), m -> count.incrementAndGet());

        bus.publish(message("t2", "1"));

        assertEquals(0, count.get());
        bus.shutdown();
    }

    @Test
    void unsubscribeStopsDelivery() {
        InMemoryMessageBus bus = syncBus();
        AtomicInteger count = new AtomicInteger();
        MessageBus.Subscription sub = bus.subscribe(new MessageBus.Topic("t1"), m -> count.incrementAndGet());

        bus.publish(message("t1", "1"));
        sub.close();
        bus.publish(message("t1", "2"));

        assertEquals(1, count.get());
        bus.shutdown();
    }

    @Test
    void subscriberExceptionIsIsolatedFromOthers() {
        InMemoryMessageBus bus = syncBus();
        AtomicInteger delivered = new AtomicInteger();
        bus.subscribe(new MessageBus.Topic("t1"), m -> {
            throw new RuntimeException("boom");
        });
        bus.subscribe(new MessageBus.Topic("t1"), m -> delivered.incrementAndGet());

        bus.publish(message("t1", "1"));

        assertEquals(1, delivered.get());
        assertFalse(false);
        bus.shutdown();
    }
}
