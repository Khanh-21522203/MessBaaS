package com.java_mess.java_mess.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.java_mess.java_mess.exception.ClientUserIdExistedException;
import com.java_mess.java_mess.model.User;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UserRepository {
    private final DataSource dataSource;

    public Optional<User> findByClientUserId(String clientUserId) {
        String sql = "select id, clientUserId, name, profileImgUrl, createdAt from `user` where clientUserId = ?";
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, clientUserId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapUser(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to find user by clientUserId", exception);
        }
    }

    public User save(User user) {
        Instant createdAt = user.getCreatedAt() != null ? user.getCreatedAt() : Instant.now();
        String id = user.getId() != null ? user.getId() : UUID.randomUUID().toString();

        String sql = """
            insert into `user` (id, clientUserId, name, profileImgUrl, createdAt)
            values (?, ?, ?, ?, ?)
            """;

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, id);
            statement.setString(2, user.getClientUserId());
            statement.setString(3, user.getName());
            statement.setString(4, user.getProfileImgUrl());
            statement.setTimestamp(5, Timestamp.from(createdAt));
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isDuplicateKey(exception)) {
                throw new ClientUserIdExistedException();
            }
            throw new IllegalStateException("Failed to save user", exception);
        }

        user.setId(id);
        user.setCreatedAt(createdAt);
        return user;
    }

    private User mapUser(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("createdAt");
        return User.builder()
            .id(resultSet.getString("id"))
            .clientUserId(resultSet.getString("clientUserId"))
            .name(resultSet.getString("name"))
            .profileImgUrl(resultSet.getString("profileImgUrl"))
            .createdAt(createdAt != null ? createdAt.toInstant() : null)
            .build();
    }

    private boolean isDuplicateKey(SQLException exception) {
        String sqlState = exception.getSQLState();
        return (sqlState != null && sqlState.startsWith("23")) || exception.getErrorCode() == 1062;
    }
}
