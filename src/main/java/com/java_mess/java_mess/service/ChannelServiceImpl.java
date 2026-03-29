package com.java_mess.java_mess.service;

import java.time.Instant;
import java.util.List;

import com.java_mess.java_mess.dto.channel.CreateChannelRequest;
import com.java_mess.java_mess.exception.ChannelExistedException;
import com.java_mess.java_mess.exception.ChannelNotFoundException;
import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.repository.ChannelRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ChannelServiceImpl implements ChannelService {
    private final ChannelRepository channelRepository;

    @Override
    public Channel createChannel(CreateChannelRequest request) {
        log.info("Create channel request={}", request);
        if (channelRepository.findByClientReferenceId(request.getClientReferenceId()).isPresent()) {
            throw new ChannelExistedException();
        }
        Channel channel = Channel.builder()
            .clientReferenceId(request.getClientReferenceId())
            .name(request.getName())
            .createdAt(Instant.now())
            .build();
        Channel savedChannel = channelRepository.save(channel);
        log.info("Saved channel {}", savedChannel.getId());
        return savedChannel;
    }

    @Override
    public Channel getChannelByReferenceId(String clientReferenceId) {
        log.info("Find channel clientReferenceId={}", clientReferenceId);
        return channelRepository.findByClientReferenceId(clientReferenceId)
            .orElseThrow(ChannelNotFoundException::new);
    }

    @Override
    public Channel getChannelById(String id) {
        return channelRepository.findById(id).orElseThrow(ChannelNotFoundException::new);
    }

    @Override
    public List<Channel> listChannels(Instant beforeCreatedAt, int limit) {
        return channelRepository.listChannels(beforeCreatedAt, limit);
    }
}
