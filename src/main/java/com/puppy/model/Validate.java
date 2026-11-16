package com.puppy.model;

/** Shared validation for the immutable model records. */
final class Validate {

    private Validate() {
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
