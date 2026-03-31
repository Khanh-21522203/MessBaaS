package com.java_mess.java_mess.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.java_mess.java_mess.model.Message;
import com.java_mess.java_mess.model.MessageOutboxEvent;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MessageOutboxRepository {
    private final DataSource dataSource;

    public void insertOutboxEvent(Connection connection, Message message) throws SQLException {
        Instant now = Instant.now();
        String sql = """
            insert into messageOutbox (
                messageId,
                channelId,
                senderUserId,
                senderClientUserId,
                clientMessageId,
                messageBody,
                imgUrl,
                messageCreatedAt,
                status,
                attemptCount,
                nextAttemptAt,
                createdAt,
                updatedAt
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, message.getId());
            statement.setString(2, message.getChannel().getId());
            statement.setString(3, message.getUser().getId());
            statement.setString(4, message.getUser().getClientUserId());
            statement.setString(5, message.getClientMessageId());
            statement.setString(6, message.getMessage());
            statement.setString(7, message.getImgUrl());
            statement.setTimestamp(8, Timestamp.from(message.getCreatedAt() == null ? now : message.getCreatedAt()));
            statement.setString(9, MessageOutboxStatus.PENDING.name());
            statement.setInt(10, 0);
            statement.setTimestamp(11, Timestamp.from(now));
            statement.setTimestamp(12, Timestamp.from(now));
            statement.setTimestamp(13, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    public List<MessageOutboxEvent> claimBatch(int batchSize, int leaseMillis) {
        if (batchSize <= 0) {
            return List.of();
        }
        Instant now = Instant.now();
        Instant leaseUntil = now.plusMillis(Math.max(100, leaseMillis));
        String selectSql = """
            select
                id,
                messageId,
                channelId,
                senderUserId,
                senderClientUserId,
                clientMessageId,
                messageBody,
                imgUrl,
                messageCreatedAt,
                status,
                attemptCount,
                nextAttemptAt
            from messageOutbox
            where status in (?, ?)
              and nextAttemptAt <= ?
              and (lockedUntil is null or lockedUntil < ?)
            order by id asc
            limit ?
            for update skip locked
            """;
        String updateSql = """
            update messageOutbox
            set status = ?, attemptCount = attemptCount + 1, lockedUntil = ?, updatedAt = ?
            where id = ?
            """;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<MessageOutboxEvent> claimed = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                    select.setString(1, MessageOutboxStatus.PENDING.name());
                    select.setString(2, MessageOutboxStatus.RETRY.name());
                    select.setTimestamp(3, Timestamp.from(now));
                    select.setTimestamp(4, Timestamp.from(now));
                    select.setInt(5, batchSize);
                    try (ResultSet resultSet = select.executeQuery()) {
                        while (resultSet.next()) {
                            claimed.add(mapEvent(resultSet));
                        }
                    }
                }

                if (claimed.isEmpty()) {
                    connection.commit();
                    return List.of();
                }

                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    for (MessageOutboxEvent event : claimed) {
                        update.setString(1, MessageOutboxStatus.IN_PROGRESS.name());
                        update.setTimestamp(2, Timestamp.from(leaseUntil));
                        update.setTimestamp(3, Timestamp.from(now));
                        update.setLong(4, event.getId());
                        update.addBatch();
                        event.setStatus(MessageOutboxStatus.IN_PROGRESS);
                        event.setAttemptCount(event.getAttemptCount() + 1);
                    }
                    update.executeBatch();
                }

                connection.commit();
                return claimed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to claim message outbox batch", exception);
        }
    }

    public void markDone(long eventId) {
        updateTerminalStatus(eventId, MessageOutboxStatus.DONE, null, null);
    }

    public void markRetry(long eventId, int delayMillis, String error) {
        Instant now = Instant.now();
        Instant nextAttemptAt = now.plusMillis(Math.max(100, delayMillis));
        String sql = """
            update messageOutbox
            set status = ?, nextAttemptAt = ?, lockedUntil = null, lastError = ?, updatedAt = ?
            where id = ?
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, MessageOutboxStatus.RETRY.name());
            statement.setTimestamp(2, Timestamp.from(nextAttemptAt));
            statement.setString(3, trimError(error));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setLong(5, eventId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to mark outbox retry event", exception);
        }
    }

    public void markDeadLetter(long eventId, String error) {
        updateTerminalStatus(eventId, MessageOutboxStatus.DEAD_LETTER, trimError(error), Instant.now());
    }

    public long countByStatus(MessageOutboxStatus status) {
        String sql = "select count(*) as total from messageOutbox where status = ?";
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, status.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong("total");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to count outbox status", exception);
        }
    }

    public long countPendingBacklog() {
        String sql = """
            select count(*) as total
            from messageOutbox
            where status in (?, ?, ?)
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, MessageOutboxStatus.PENDING.name());
            statement.setString(2, MessageOutboxStatus.RETRY.name());
            statement.setString(3, MessageOutboxStatus.IN_PROGRESS.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong("total");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to count outbox backlog", exception);
        }
    }

    private void updateTerminalStatus(long eventId, MessageOutboxStatus status, String error, Instant processedAt) {
        Instant now = Instant.now();
        String sql = """
            update messageOutbox
            set status = ?, lockedUntil = null, processedAt = ?, lastError = ?, updatedAt = ?
            where id = ?
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            if (processedAt == null) {
                statement.setTimestamp(2, Timestamp.from(now));
            } else {
                statement.setTimestamp(2, Timestamp.from(processedAt));
            }
            statement.setString(1, status.name());
            statement.setString(3, error);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setLong(5, eventId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to update outbox status", exception);
        }
    }

    private MessageOutboxEvent mapEvent(ResultSet resultSet) throws SQLException {
        Timestamp messageCreatedAt = resultSet.getTimestamp("messageCreatedAt");
        Timestamp nextAttemptAt = resultSet.getTimestamp("nextAttemptAt");
        return MessageOutboxEvent.builder()
            .id(resultSet.getLong("id"))
            .messageId(resultSet.getLong("messageId"))
            .channelId(resultSet.getString("channelId"))
            .senderUserId(resultSet.getString("senderUserId"))
            .senderClientUserId(resultSet.getString("senderClientUserId"))
            .clientMessageId(resultSet.getString("clientMessageId"))
            .messageBody(resultSet.getString("messageBody"))
            .imgUrl(resultSet.getString("imgUrl"))
            .messageCreatedAt(messageCreatedAt != null ? messageCreatedAt.toInstant() : null)
            .status(MessageOutboxStatus.valueOf(resultSet.getString("status")))
            .attemptCount(resultSet.getInt("attemptCount"))
            .nextAttemptAt(nextAttemptAt != null ? nextAttemptAt.toInstant() : null)
            .build();
    }

    private String trimError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 10_000 ? error.substring(0, 10_000) : error;
    }
}
