package com.java_mess.java_mess.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java_mess.java_mess.model.InboxEntry;

class ProjectionCacheStoreTest {

    @Test
    void readCursorIsMonotonicForLocalProjectionCache() {
        ProjectionCacheStore store = new ProjectionCacheStore(new ObjectMapper(), null, 128, true);

        store.setReadCursor("u-1", "ch-1", 120L);
        store.setReadCursor("u-1", "ch-1", 10L);

        assertEquals(120L, store.getReadCursor("u-1", "ch-1").orElseThrow());
    }

    @Test
    void unreadProjectionUpdateSkipsOlderEventVersionAndTracksDrift() {
        ProjectionCacheStore store = new ProjectionCacheStore(new ObjectMapper(), null, 128, true);

        store.setUnreadCountFromProjection("u-1", "ch-1", 8L, 50L);
        store.setUnreadCountFromProjection("u-1", "ch-1", 2L, 40L);

        assertEquals(8L, store.getUnreadCount("u-1", "ch-1").orElseThrow());
        assertEquals(1L, store.runtimeStats().getProjectionDriftDetected());
    }

    @Test
    void inboxEntryDoesNotRegressToOlderMessageVersion() {
        ProjectionCacheStore store = new ProjectionCacheStore(new ObjectMapper(), null, 128, true);

        store.upsertInboxEntry("u-1", InboxEntry.builder()
            .channelId("ch-1")
            .lastMessageId(200L)
            .lastSenderClientUserId("alice")
            .lastPreview("new")
            .unreadCount(10L)
            .updatedAt(Instant.now())
            .build());
        store.upsertInboxEntry("u-1", InboxEntry.builder()
            .channelId("ch-1")
            .lastMessageId(100L)
            .lastSenderClientUserId("bob")
            .lastPreview("old")
            .unreadCount(1L)
            .updatedAt(Instant.now())
            .build());

        List<InboxEntry> entries = store.listInboxEntries("u-1", 10);
        assertEquals(1, entries.size());
        assertEquals(200L, entries.get(0).getLastMessageId());
        assertEquals("new", entries.get(0).getLastPreview());
    }

    @Test
    void localCacheCanBeDisabledForMultiNodeMode() {
        ProjectionCacheStore store = new ProjectionCacheStore(new ObjectMapper(), null, 128, false);

        store.cacheMembership("ch-1", "u-1");
        store.upsertInboxEntry("u-1", InboxEntry.builder().channelId("ch-1").lastMessageId(1L).build());

        assertTrue(store.listInboxEntries("u-1", 10).isEmpty());
        assertTrue(store.isMemberCached("ch-1", "u-1") == null);
    }
}
