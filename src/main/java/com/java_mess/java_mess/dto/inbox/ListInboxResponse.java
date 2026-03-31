package com.java_mess.java_mess.dto.inbox;

import java.util.List;

import com.java_mess.java_mess.model.InboxEntry;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ListInboxResponse {
    private List<InboxEntry> conversations;
}
