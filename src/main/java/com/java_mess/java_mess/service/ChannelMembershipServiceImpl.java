package com.java_mess.java_mess.service;

import java.util.List;

import com.java_mess.java_mess.exception.ChannelAccessDeniedException;
import com.java_mess.java_mess.exception.ChannelMemberNotFoundException;
import com.java_mess.java_mess.exception.ChannelNotFoundException;
import com.java_mess.java_mess.exception.UserNotFoundException;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.repository.ChannelMemberRepository;
import com.java_mess.java_mess.repository.ChannelRepository;
import com.java_mess.java_mess.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChannelMembershipServiceImpl implements ChannelMembershipService {
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final ChannelMemberRepository channelMemberRepository;
    private final ProjectionCacheStore projectionCacheStore;

    @Override
    public void addMember(String channelId, String clientUserId) {
        Channel channel = channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);
        User user = userRepository.findByClientUserId(clientUserId).orElseThrow(UserNotFoundException::new);
        channelMemberRepository.addMember(channel.getId(), user.getId());
        projectionCacheStore.cacheMembership(channel.getId(), user.getId());
    }

    @Override
    public void removeMember(String channelId, String clientUserId) {
        Channel channel = channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);
        User user = userRepository.findByClientUserId(clientUserId).orElseThrow(UserNotFoundException::new);
        boolean removed = channelMemberRepository.removeMember(channel.getId(), user.getId());
        if (!removed) {
            throw new ChannelMemberNotFoundException();
        }
        projectionCacheStore.evictMembership(channel.getId(), user.getId());
    }

    @Override
    public List<User> listMembers(String channelId) {
        Channel channel = channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);
        List<User> members = channelMemberRepository.listMembers(channel.getId());
        for (User member : members) {
            projectionCacheStore.cacheMembership(channel.getId(), member.getId());
        }
        return members;
    }

    @Override
    public void assertMember(String channelId, String clientUserId) {
        Channel channel = channelRepository.findById(channelId).orElseThrow(ChannelNotFoundException::new);
        User user = userRepository.findByClientUserId(clientUserId).orElseThrow(UserNotFoundException::new);
        Boolean cached = projectionCacheStore.isMemberCached(channel.getId(), user.getId());
        if (Boolean.TRUE.equals(cached)) {
            return;
        }

        boolean member = channelMemberRepository.isMember(channel.getId(), user.getId());
        if (member) {
            projectionCacheStore.cacheMembership(channel.getId(), user.getId());
            return;
        }
        if (Boolean.FALSE.equals(cached)) {
            throw new ChannelAccessDeniedException();
        }
        throw new ChannelAccessDeniedException();
    }
}
