package com.java_mess.java_mess.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

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

    @Test
    void requireNonNegativeLongRejectsNegativeValues() {
        RequestValidator.requireNonNegative(0L, "lastReadMessageId");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireNonNegative(-1L, "lastReadMessageId")
        );

        assertEquals("lastReadMessageId must be non-negative", error.getMessage());
    }

    @Test
    void requireAtLeastOneNonBlankAcceptsEitherFieldAndRejectsBothBlank() {
        RequestValidator.requireAtLeastOneNonBlank("message", "message", null, "imgUrl");
        RequestValidator.requireAtLeastOneNonBlank(null, "message", "https://img", "imgUrl");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireAtLeastOneNonBlank("   ", "message", "", "imgUrl")
        );
        assertEquals("At least one of message or imgUrl is required", error.getMessage());
    }

    @Test
    void requireMaxLengthRejectsOversizedValues() {
        RequestValidator.requireMaxLength("abcd", 4, "clientMessageId");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireMaxLength("abcde", 4, "clientMessageId")
        );
        assertEquals("clientMessageId must be at most 4 characters", error.getMessage());
    }

    @Test
    void requirePositiveRejectsZeroAndNegativeValues() {
        RequestValidator.requirePositive(1, "limit");
        IllegalArgumentException zero = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requirePositive(0, "limit")
        );
        IllegalArgumentException negative = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requirePositive(-1, "limit")
        );
        assertEquals("limit must be positive", zero.getMessage());
        assertEquals("limit must be positive", negative.getMessage());
    }

    @Test
    void requireInstantParsesIsoValueAndRejectsInvalidInput() {
        assertEquals(Instant.parse("2026-03-29T10:20:30Z"), RequestValidator.requireInstant("2026-03-29T10:20:30Z", "beforeCreatedAt"));
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RequestValidator.requireInstant("29-03-2026", "beforeCreatedAt")
        );
        assertEquals("beforeCreatedAt must be a valid ISO-8601 instant", error.getMessage());
    }
}
