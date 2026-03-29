package com.java_mess.java_mess.service;

import java.time.Instant;
import java.util.List;

import com.java_mess.java_mess.dto.channel.CreateChannelRequest;
import com.java_mess.java_mess.model.Channel;

public interface ChannelService {
    Channel createChannel(CreateChannelRequest request);

    Channel getChannelByReferenceId(String clientReferenceId);

    Channel getChannelById(String id);

    List<Channel> listChannels(Instant beforeCreatedAt, int limit);
}
