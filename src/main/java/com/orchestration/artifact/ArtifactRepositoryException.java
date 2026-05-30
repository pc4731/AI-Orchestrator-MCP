package com.orchestration.artifact;

/** Unchecked wrapper for failures in an {@link ArtifactRepository} implementation. */
public class ArtifactRepositoryException extends RuntimeException {

    public ArtifactRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
