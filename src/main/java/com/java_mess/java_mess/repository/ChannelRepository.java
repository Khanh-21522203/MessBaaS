package com.java_mess.java_mess.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.java_mess.java_mess.exception.ChannelExistedException;
import com.java_mess.java_mess.model.Channel;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChannelRepository {
    private final DataSource dataSource;

    public Optional<Channel> findByClientReferenceId(String clientReferenceId) {
        String sql = "select id, name, clientReferenceId, createdAt from channel where clientReferenceId = ?";
        return findSingle(sql, clientReferenceId);
    }

    public Optional<Channel> findById(String id) {
        String sql = "select id, name, clientReferenceId, createdAt from channel where id = ?";
        return findSingle(sql, id);
    }

    public Channel save(Channel channel) {
        Instant createdAt = channel.getCreatedAt() != null ? channel.getCreatedAt() : Instant.now();
        String id = channel.getId() != null ? channel.getId() : UUID.randomUUID().toString();

        String sql = """
            insert into channel (id, name, clientReferenceId, createdAt)
            values (?, ?, ?, ?)
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, id);
            statement.setString(2, channel.getName());
            statement.setString(3, channel.getClientReferenceId());
            statement.setTimestamp(4, Timestamp.from(createdAt));
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isDuplicateKey(exception)) {
                throw new ChannelExistedException();
            }
            throw new IllegalStateException("Failed to save channel", exception);
        }

        channel.setId(id);
        channel.setCreatedAt(createdAt);
        return channel;
    }

    public List<Channel> listChannels(Instant beforeCreatedAt, int limit) {
        String sql = """
            select id, name, clientReferenceId, createdAt
            from channel
            where (? is null or createdAt < ?)
            order by createdAt desc
            limit ?
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            if (beforeCreatedAt == null) {
                statement.setNull(1, Types.TIMESTAMP);
                statement.setNull(2, Types.TIMESTAMP);
            } else {
                Timestamp timestamp = Timestamp.from(beforeCreatedAt);
                statement.setTimestamp(1, timestamp);
                statement.setTimestamp(2, timestamp);
            }
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Channel> channels = new ArrayList<>();
                while (resultSet.next()) {
                    channels.add(mapChannel(resultSet));
                }
                return channels;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to list channels", exception);
        }
    }

    private Optional<Channel> findSingle(String sql, String value) {
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapChannel(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load channel", exception);
        }
    }

    private Channel mapChannel(ResultSet resultSet) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("createdAt");
        return Channel.builder()
            .id(resultSet.getString("id"))
            .name(resultSet.getString("name"))
            .clientReferenceId(resultSet.getString("clientReferenceId"))
            .createdAt(createdAt != null ? createdAt.toInstant() : null)
            .build();
    }

    private boolean isDuplicateKey(SQLException exception) {
        String sqlState = exception.getSQLState();
        return (sqlState != null && sqlState.startsWith("23")) || exception.getErrorCode() == 1062;
    }
}
