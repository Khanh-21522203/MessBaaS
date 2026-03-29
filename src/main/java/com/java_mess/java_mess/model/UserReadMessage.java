package com.java_mess.java_mess.model;

import java.io.Serializable;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserReadMessage implements Serializable {
  private String id;

  @JsonProperty("channel")
  private Channel channel;

  @JsonProperty("user")
  private User user;

  private Long lastReadMessageId;

  private Instant createdAt;

  private Instant updatedAt;
}
