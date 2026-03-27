package com.java_mess.java_mess.service;

import com.java_mess.java_mess.dto.user.CreateUserRequest;
import com.java_mess.java_mess.exception.ClientUserIdExistedException;
import com.java_mess.java_mess.exception.UserNotFoundException;
import com.java_mess.java_mess.model.User;
import com.java_mess.java_mess.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
  private final UserRepository userRepository;

  @Override
  public User createUser(CreateUserRequest request) {
    log.info("Create user request={}", request);
    if (userRepository.findByClientUserId(request.getClientUserId()).isPresent()) {
      throw new ClientUserIdExistedException();
    }
    log.info("User does not exist, creating {}", request.getClientUserId());

    User user = User.builder()
        .clientUserId(request.getClientUserId())
        .name(request.getName())
        .profileImgUrl(request.getProfileImgUrl())
        .build();
    return userRepository.save(user);
  }

  @Override
  public User getUserByClientId(String clientUserId) {
    return userRepository.findByClientUserId(clientUserId)
        .orElseThrow(UserNotFoundException::new);
  }
}
