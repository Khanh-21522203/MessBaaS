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
            throw new IllegalStateException("Failed to save channel", exception);
        }

        channel.setId(id);
        channel.setCreatedAt(createdAt);
        return channel;
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
}
