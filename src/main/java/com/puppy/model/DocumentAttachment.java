package com.puppy.model;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Text extracted from a file the user attached to the current conversation.
 *
 * <p>Only the extracted text is kept in memory; the original file is never
 * copied or uploaded anywhere. The text is sent to the local Ollama model as
 * part of the prompt when the user sends a message.</p>
 */
public record DocumentAttachment(
        Path source,
        String text,
        int pageCount,
        boolean truncated
) {

    public DocumentAttachment {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(text, "text");
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount must not be negative");
        }
    }

    public String fileName() {
        Path name = source.getFileName();
        return name == null ? source.toString() : name.toString();
    }

    public String extension() {
        String name = fileName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public int characterCount() {
        return text.length();
    }
}
