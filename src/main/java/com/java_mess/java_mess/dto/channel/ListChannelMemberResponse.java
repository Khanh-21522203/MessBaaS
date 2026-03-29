package com.java_mess.java_mess.dto.channel;

import java.util.List;

import com.java_mess.java_mess.model.User;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ListChannelMemberResponse {
    private List<User> members;
}
