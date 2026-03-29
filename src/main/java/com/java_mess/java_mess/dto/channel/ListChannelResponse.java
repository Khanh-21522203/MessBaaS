package com.java_mess.java_mess.dto.channel;

import java.util.List;

import com.java_mess.java_mess.model.Channel;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ListChannelResponse {
    private List<Channel> channels;
}
