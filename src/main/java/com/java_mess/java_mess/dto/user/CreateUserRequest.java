package com.java_mess.java_mess.dto.user;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateUserRequest {
  private String name;
  private String clientUserId;
  private String profileImgUrl;
}
