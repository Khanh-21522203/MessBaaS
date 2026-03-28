package com.java_mess.java_mess.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RequestValidatorTest {

    @Test
    void requireNonBlankRejectsNullAndBlankValues() {
        IllegalArgumentException nullError = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireNonBlank(null, "channelId")
        );
        IllegalArgumentException blankError = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireNonBlank("   ", "channelId")
        );

        assertEquals("channelId is required", nullError.getMessage());
        assertEquals("channelId is required", blankError.getMessage());
    }

    @Test
    void requireLongParsesValidValueAndRejectsInvalidInput() {
        assertEquals(123L, RequestValidator.requireLong("123", "pivotId"));

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireLong("not-a-number", "pivotId")
        );
        assertEquals("pivotId must be a valid long", error.getMessage());
    }

    @Test
    void requireIntParsesValidValueAndRejectsInvalidInput() {
        assertEquals(42, RequestValidator.requireInt("42", "limit"));

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireInt("4.2", "limit")
        );
        assertEquals("limit must be a valid int", error.getMessage());
    }

    @Test
    void requireNonNegativeRejectsNegativeValues() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireNonNegative(-1, "nextLimit")
        );

        assertEquals("nextLimit must be non-negative", error.getMessage());
    }
}
