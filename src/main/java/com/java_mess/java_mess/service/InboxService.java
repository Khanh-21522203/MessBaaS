package com.java_mess.java_mess.service;

import java.util.List;

import com.java_mess.java_mess.model.InboxEntry;

public interface InboxService {
    List<InboxEntry> listInbox(String clientUserId, int limit);

    InboxRuntimeStats runtimeStats();
}
