package com.java_mess.java_mess.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.java_mess.java_mess.model.Channel;
import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.User;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MessageRepository {
    private static final String MESSAGE_SELECT = """
        select
            m.id as message_id,
            m.channelId as message_channelId,
            m.message as message_body,
            m.imgUrl as message_imgUrl,
            m.isDeleted as message_isDeleted,
            m.createdAt as message_createdAt,
            m.updatedAt as message_updatedAt,
            u.id as user_id,
            u.clientUserId as user_clientUserId,
            u.name as user_name,
            u.profileImgUrl as user_profileImgUrl,
            u.createdAt as user_createdAt
        from message m
        join `user` u on u.id = m.userId
        """;

    private final DataSource dataSource;

    public Message save(Message message) {
        Instant createdAt = message.getCreatedAt() != null ? message.getCreatedAt() : Instant.now();
        Instant updatedAt = message.getUpdatedAt() != null ? message.getUpdatedAt() : createdAt;

        String sql = """
            insert into message (channelId, userId, message, imgUrl, isDeleted, createdAt, updatedAt)
            values (?, ?, ?, ?, ?, ?, ?)
            """;

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
        ) {
            statement.setString(1, message.getChannel().getId());
            statement.setString(2, message.getUser().getId());
            statement.setString(3, message.getMessage());
            statement.setString(4, message.getImgUrl());
            statement.setBoolean(5, Boolean.TRUE.equals(message.getIsDeleted()));
            statement.setTimestamp(6, Timestamp.from(createdAt));
            statement.setTimestamp(7, Timestamp.from(updatedAt));
            statement.executeUpdate();
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new IllegalStateException("Failed to create message id");
                }
                message.setId(generatedKeys.getLong(1));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to save message", exception);
        }

        message.setCreatedAt(createdAt);
        message.setUpdatedAt(updatedAt);
        return message;
    }

    public List<Message> findLatestMessages(String channelId, int limit) {
        String sql = MESSAGE_SELECT + " where m.channelId = ? order by m.id desc limit ?";
        return queryMessages(sql, statement -> {
            statement.setString(1, channelId);
            statement.setInt(2, limit);
        });
    }

    public List<Message> listMessagesBeforeId(long id, String channelId, int limit) {
        String sql = MESSAGE_SELECT + " where m.id < ? and m.channelId = ? order by m.id desc limit ?";
        return queryMessages(sql, statement -> {
            statement.setLong(1, id);
            statement.setString(2, channelId);
            statement.setInt(3, limit);
        });
    }

    public List<Message> listMessagesAfterId(long id, String channelId, int limit) {
        String sql = MESSAGE_SELECT + " where m.id > ? and m.channelId = ? order by m.id asc limit ?";
        return queryMessages(sql, statement -> {
            statement.setLong(1, id);
            statement.setString(2, channelId);
            statement.setInt(3, limit);
        });
    }

    private List<Message> queryMessages(String sql, StatementBinder binder) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Message> messages = new ArrayList<>();
                while (resultSet.next()) {
                    messages.add(mapMessage(resultSet));
                }
                return messages;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query messages", exception);
        }
    }

    private Message mapMessage(ResultSet resultSet) throws SQLException {
        Timestamp userCreatedAt = resultSet.getTimestamp("user_createdAt");
        Timestamp messageCreatedAt = resultSet.getTimestamp("message_createdAt");
        Timestamp messageUpdatedAt = resultSet.getTimestamp("message_updatedAt");

        User user = User.builder()
            .id(resultSet.getString("user_id"))
            .clientUserId(resultSet.getString("user_clientUserId"))
            .name(resultSet.getString("user_name"))
            .profileImgUrl(resultSet.getString("user_profileImgUrl"))
            .createdAt(userCreatedAt != null ? userCreatedAt.toInstant() : null)
            .build();

        Channel channel = Channel.builder()
            .id(resultSet.getString("message_channelId"))
            .build();

        return Message.builder()
            .id(resultSet.getLong("message_id"))
            .channel(channel)
            .user(user)
            .message(resultSet.getString("message_body"))
            .imgUrl(resultSet.getString("message_imgUrl"))
            .isDeleted(resultSet.getBoolean("message_isDeleted"))
            .createdAt(messageCreatedAt != null ? messageCreatedAt.toInstant() : null)
            .updatedAt(messageUpdatedAt != null ? messageUpdatedAt.toInstant() : null)
            .build();
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
