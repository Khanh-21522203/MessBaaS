package com.java_mess.java_mess.service;

import com.java_mess.java_mess.model.MessageOutboxEvent;

public interface ProjectionProcessor {
    void process(MessageOutboxEvent event);

    default void processReplay(MessageOutboxEvent event) {
        process(event);
    }
}
