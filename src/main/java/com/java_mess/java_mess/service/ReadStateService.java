package com.java_mess.java_mess.service;

public interface ReadStateService {
    long updateReadCursor(String channelId, String clientUserId, long lastReadMessageId);

    ReadStateSnapshot getUnreadCount(String channelId, String clientUserId);
}
