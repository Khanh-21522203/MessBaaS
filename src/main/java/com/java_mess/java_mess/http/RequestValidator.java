package com.java_mess.java_mess.http;

import java.time.Instant;
import java.time.format.DateTimeParseException;

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
        requireNonNegative((long) value, fieldName);
    }

    public static void requireNonNegative(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
    }

    public static void requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    public static void requireMaxLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength + " characters");
        }
    }

    public static void requireAtLeastOneNonBlank(
        String primaryValue,
        String primaryFieldName,
        String secondaryValue,
        String secondaryFieldName
    ) {
        boolean primaryPresent = primaryValue != null && !primaryValue.isBlank();
        boolean secondaryPresent = secondaryValue != null && !secondaryValue.isBlank();
        if (!primaryPresent && !secondaryPresent) {
            throw new IllegalArgumentException(
                "At least one of " + primaryFieldName + " or " + secondaryFieldName + " is required"
            );
        }
    }

    public static Instant requireInstant(String value, String fieldName) {
        requireNonBlank(value, fieldName);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(fieldName + " must be a valid ISO-8601 instant", exception);
        }
    }
}
