package com.java_mess.java_mess.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;

class ChannelMessageHotStoreTest {

    @Test
    void latestReturnsNewestMessagesInDescendingOrderWithinChannelLimit() {
        ChannelMessageHotStore store = new ChannelMessageHotStore(3);

        store.append(message(1L, "ch-1", "hello-1"));
        store.append(message(2L, "ch-1", "hello-2"));
        store.append(message(3L, "ch-1", "hello-3"));
        store.append(message(4L, "ch-1", "hello-4"));

        List<Message> latest = store.latest("ch-1", 10);

        assertEquals(3, latest.size());
        assertEquals(4L, latest.get(0).getId());
        assertEquals(3L, latest.get(1).getId());
        assertEquals(2L, latest.get(2).getId());
    }

    @Test
    void beforeAndAfterApplyPivotOrderingAndRequestedLimit() {
        ChannelMessageHotStore store = new ChannelMessageHotStore(10);

        for (long id = 1; id <= 6; id++) {
            store.append(message(id, "ch-1", "m-" + id));
        }

        List<Message> before = store.before("ch-1", 5L, 2);
        List<Message> after = store.after("ch-1", 3L, 2);

        assertEquals(List.of(4L, 3L), before.stream().map(Message::getId).toList());
        assertEquals(List.of(4L, 5L), after.stream().map(Message::getId).toList());
    }

    @Test
    void latestReturnsDefensiveCopiesSoCallersCannotMutateStoreState() {
        ChannelMessageHotStore store = new ChannelMessageHotStore(5);
        store.append(message(100L, "ch-1", "original-body"));

        List<Message> firstRead = store.latest("ch-1", 1);
        Message mutated = firstRead.get(0);
        mutated.setMessage("mutated-body");
        mutated.getUser().setName("mutated-user");
        mutated.getChannel().setName("mutated-channel");

        List<Message> secondRead = store.latest("ch-1", 1);

        assertEquals("original-body", secondRead.get(0).getMessage());
        assertEquals("user-100", secondRead.get(0).getUser().getName());
        assertEquals("channel-ch-1", secondRead.get(0).getChannel().getName());
    }

    @Test
    void nonPositiveLimitReturnsEmptyResults() {
        ChannelMessageHotStore store = new ChannelMessageHotStore(2);
        store.append(message(1L, "ch-1", "hello"));

        assertTrue(store.latest("ch-1", 0).isEmpty());
        assertTrue(store.before("ch-1", 2L, -1).isEmpty());
        assertTrue(store.after("ch-1", 0L, 0).isEmpty());
    }

    private Message message(long id, String channelId, String body) {
        return Message.builder()
            .id(id)
            .channel(Channel.builder().id(channelId).name("channel-" + channelId).build())
            .user(User.builder().id("u-" + id).clientUserId("cuid-" + id).name("user-" + id).build())
            .message(body)
            .isDeleted(false)
            .build();
    }
}
