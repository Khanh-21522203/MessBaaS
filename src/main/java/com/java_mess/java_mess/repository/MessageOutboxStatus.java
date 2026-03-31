package com.java_mess.java_mess.repository;

public enum MessageOutboxStatus {
    PENDING,
    IN_PROGRESS,
    RETRY,
    DONE,
    DEAD_LETTER
}
