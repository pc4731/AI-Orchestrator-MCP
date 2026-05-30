package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code config/messagebus.yml}: which bus backend to use and in-memory tuning. */
@ConfigurationProperties("message-bus")
public record MessageBusProperties(String backend, InMemory inMemory) {

    public record InMemory(int queueCapacity, boolean async) {
    }
}
