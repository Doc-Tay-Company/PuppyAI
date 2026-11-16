package com.puppy.document;

/** A user-facing problem while reading an attached document. */
public class DocumentExtractionException extends Exception {

    public DocumentExtractionException(String message) {
        super(message);
    }

    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
