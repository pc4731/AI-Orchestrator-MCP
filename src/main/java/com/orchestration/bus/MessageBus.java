package com.orchestration.bus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Async, event-driven inter-agent communication (the <b>Observer pattern</b> at the system level).
 *
 * <p>The v1 implementation is in-memory (Java {@code Flow}/{@code BlockingQueue} or Spring's
 * {@code ApplicationEventPublisher}), but it lives behind this interface so a Redis- or
 * Kafka-backed bus can be swapped in via configuration without any change to the engine or
 * the agents (Strategy at wiring time).
 *
 * <p>Delivery is asynchronous: {@link #publish} returns immediately and handlers run on the bus's
 * own threads. The payload is opaque to the bus.
 */
public interface MessageBus {

    /** Publish to every subscriber of the message's topic. Non-blocking. */
    void publish(Message message);

    /** Register a handler for a topic. The returned {@link Subscription} cancels delivery. */
    Subscription subscribe(Topic topic, Consumer<Message> handler);

    /** Cancel a subscription. Safe to call more than once. */
    void unsubscribe(Subscription subscription);

    /** A routable, immutable event. */
    record Message(
            String id,
            Topic topic,
            String sender,          // an AgentId value, "engine", or "human"
            String correlationId,   // ties the message to a task/project thread for tracing
            Map<String, Object> payload,
            Instant timestamp
    ) {
        public Message {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /** A logical channel; a value type so backends can map it to a queue/topic name. */
    record Topic(String name) {
        public Topic {
            Objects.requireNonNull(name, "topic name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("topic name must not be blank");
            }
        }
    }

    /** Opaque, idempotent cancellation handle. {@link AutoCloseable} so it works in try-with-resources. */
    interface Subscription extends AutoCloseable {
        Topic topic();

        @Override
        void close();
    }
}
