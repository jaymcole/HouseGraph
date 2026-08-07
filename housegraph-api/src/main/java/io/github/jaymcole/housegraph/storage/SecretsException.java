package io.github.jaymcole.housegraph.storage;

/** Raised when the secret store can't be read or written — e.g. a corrupt/tampered file or a missing algorithm. */
public class SecretsException extends RuntimeException {

    public SecretsException(String message) {
        super(message);
    }

    public SecretsException(String message, Throwable cause) {
        super(message, cause);
    }
}
