package com.java_mess.java_mess.dto.channel;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChannelRequest {
  private String name;
  private String clientReferenceId;
}
