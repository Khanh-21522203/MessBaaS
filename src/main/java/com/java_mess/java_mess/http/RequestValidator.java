package com.java_mess.java_mess.http;

public final class RequestValidator {
    private RequestValidator() {
    }

    public static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    public static long requireLong(String value, String fieldName) {
        requireNonBlank(value, fieldName);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must be a valid long", exception);
        }
    }

    public static int requireInt(String value, String fieldName) {
        requireNonBlank(value, fieldName);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must be a valid int", exception);
        }
    }

    public static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
    }
}
