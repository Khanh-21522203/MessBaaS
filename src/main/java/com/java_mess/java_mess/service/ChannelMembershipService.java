package com.java_mess.java_mess.service;

import java.util.List;

import com.java_mess.java_mess.model.User;

public interface ChannelMembershipService {
    void addMember(String channelId, String clientUserId);

    void removeMember(String channelId, String clientUserId);

    List<User> listMembers(String channelId);

    void assertMember(String channelId, String clientUserId);
}
