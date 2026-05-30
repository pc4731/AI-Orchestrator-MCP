package com.orchestration.memory;

/** Unchecked wrapper for persistence failures in a {@link MemoryStore} implementation. */
public class MemoryStoreException extends RuntimeException {

    public MemoryStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
