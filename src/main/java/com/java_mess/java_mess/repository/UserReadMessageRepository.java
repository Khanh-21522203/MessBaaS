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

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UserReadMessageRepository {
    private final DataSource dataSource;

    public long upsertReadCursor(String channelId, String userId, long lastReadMessageId) {
        long clampedCursor = clampReadCursor(channelId, lastReadMessageId);
        Instant now = Instant.now();
        String sql = """
            insert into userReadMessage (id, channelId, userId, lastReadMessageId, createdAt, updatedAt)
            values (?, ?, ?, ?, ?, ?)
            on duplicate key update
                lastReadMessageId = if(values(lastReadMessageId) > lastReadMessageId, values(lastReadMessageId), lastReadMessageId),
                updatedAt = values(updatedAt)
            """;

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, channelId);
            statement.setString(3, userId);
            statement.setLong(4, clampedCursor);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(now));
            statement.executeUpdate();
            return findReadCursor(channelId, userId).orElse(clampedCursor);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to upsert read cursor", exception);
        }
    }

    public Optional<Long> findReadCursor(String channelId, String userId) {
        String sql = "select lastReadMessageId from userReadMessage where channelId = ? and userId = ?";
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, channelId);
            statement.setString(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                long cursor = resultSet.getLong("lastReadMessageId");
                return resultSet.wasNull() ? Optional.empty() : Optional.of(cursor);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load read cursor", exception);
        }
    }

    public long countUnreadMessages(String channelId, long lastReadMessageId) {
        String sql = """
            select count(*) as unread_count
            from message
            where channelId = ?
              and id > ?
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, channelId);
            statement.setLong(2, lastReadMessageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong("unread_count");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to count unread messages", exception);
        }
    }

    public long countAllMessages(String channelId) {
        String sql = "select count(*) as message_count from message where channelId = ?";
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, channelId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong("message_count");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to count channel messages", exception);
        }
    }

    private long clampReadCursor(String channelId, long requestedCursor) {
        long normalizedCursor = Math.max(0L, requestedCursor);
        long latestMessageId = findLatestMessageId(channelId);
        if (latestMessageId <= 0L) {
            return 0L;
        }
        return Math.min(normalizedCursor, latestMessageId);
    }

    private long findLatestMessageId(String channelId) {
        String sql = "select coalesce(max(id), 0) as latest_message_id from message where channelId = ?";
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, channelId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong("latest_message_id");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load latest channel message id", exception);
        }
    }
}
