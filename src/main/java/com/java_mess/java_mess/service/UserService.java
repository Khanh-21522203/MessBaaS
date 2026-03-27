package com.java_mess.java_mess.service;

import com.java_mess.java_mess.dto.user.CreateUserRequest;
import com.java_mess.java_mess.model.User;

public interface UserService {
  User createUser(CreateUserRequest request);

  User getUserByClientId(String clientUserId);
}
