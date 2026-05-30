package com.orchestration.bus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * In-memory {@link MessageBus} (Observer pattern). Delivery is asynchronous by default: each
 * published message is dispatched to subscribers on a virtual-thread executor, so a slow handler
 * never blocks the publisher or other subscribers.
 *
 * <p>This is the v1 backend; the engine and agents depend only on {@link MessageBus}, so a Redis/
 * Kafka implementation can replace it without code changes. A misbehaving subscriber's exception is
 * isolated (logged-and-swallowed) so it cannot break delivery to others.
 */
public class InMemoryMessageBus implements MessageBus {

    private final Map<Topic, List<InMemorySubscription>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final boolean async;

    public InMemoryMessageBus() {
        this(Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    public InMemoryMessageBus(ExecutorService executor, boolean async) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.async = async;
    }

    @Override
    public void publish(Message message) {
        Objects.requireNonNull(message, "message");
        List<InMemorySubscription> subs = subscribers.get(message.topic());
        if (subs == null) {
            return;
        }
        for (InMemorySubscription sub : subs) {
            if (async) {
                executor.submit(() -> deliver(sub, message));
            } else {
                deliver(sub, message);
            }
        }
    }

    @Override
    public Subscription subscribe(Topic topic, Consumer<Message> handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        InMemorySubscription sub = new InMemorySubscription(topic, handler);
        subscribers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(sub);
        return sub;
    }

    @Override
    public void unsubscribe(Subscription subscription) {
        if (subscription == null) {
            return;
        }
        List<InMemorySubscription> subs = subscribers.get(subscription.topic());
        if (subs != null) {
            subs.removeIf(s -> s == subscription);
        }
    }

    /** Stop the delivery executor. Not part of the {@link MessageBus} contract. */
    public void shutdown() {
        executor.shutdown();
    }

    private void deliver(InMemorySubscription sub, Message message) {
        try {
            sub.handler.accept(message);
        } catch (RuntimeException e) {
            // Isolate subscriber failures so one bad handler cannot break delivery to others.
            System.getLogger(InMemoryMessageBus.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "Subscriber failed handling message " + message.id() + " on " + message.topic(), e);
        }
    }

    private final class InMemorySubscription implements Subscription {
        private final Topic topic;
        private final Consumer<Message> handler;

        private InMemorySubscription(Topic topic, Consumer<Message> handler) {
            this.topic = topic;
            this.handler = handler;
        }

        @Override
        public Topic topic() {
            return topic;
        }

        @Override
        public void close() {
            unsubscribe(this);
        }
    }
}
