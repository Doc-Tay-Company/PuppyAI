package com.puppy.model;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Text extracted from a file the user attached to the current conversation.
 *
 * <p>History persists extracted text and filename metadata, not original files
 * or absolute paths. Text is sent to the selected local or cloud model when
 * used as request context. Restored attachments do not require a source file.</p>
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
