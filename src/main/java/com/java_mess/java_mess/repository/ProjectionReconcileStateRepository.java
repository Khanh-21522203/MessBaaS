package com.java_mess.java_mess.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.OptionalLong;

import javax.sql.DataSource;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProjectionReconcileStateRepository {
    private final DataSource dataSource;

    public OptionalLong findCheckpoint(String scope) {
        String sql = "select checkpoint from projectionReconcileState where scope = ?";
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, scope);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return OptionalLong.empty();
                }
                return OptionalLong.of(resultSet.getLong("checkpoint"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read projection reconciliation checkpoint", exception);
        }
    }

    public void upsertCheckpoint(String scope, long checkpoint) {
        Instant now = Instant.now();
        String sql = """
            insert into projectionReconcileState (scope, checkpoint, updatedAt)
            values (?, ?, ?)
            on duplicate key update checkpoint = values(checkpoint), updatedAt = values(updatedAt)
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, scope);
            statement.setLong(2, Math.max(0L, checkpoint));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to update projection reconciliation checkpoint", exception);
        }
    }
}
