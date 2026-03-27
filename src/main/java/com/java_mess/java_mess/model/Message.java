package com.java_mess.java_mess.model;

import java.io.Serializable;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message implements Serializable {
  private Long id;

  @JsonIgnore()
  private Channel channel;

  @JsonProperty("user")
  private User user;

  private String message;

  private String imgUrl;

  private Boolean isDeleted;

  private Instant createdAt;

  private Instant updatedAt;
}
