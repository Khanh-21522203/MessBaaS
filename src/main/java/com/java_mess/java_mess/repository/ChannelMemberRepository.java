package com.java_mess.java_mess.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.java_mess.java_mess.exception.ChannelMemberExistedException;
import com.java_mess.java_mess.model.User;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChannelMemberRepository {
    private final DataSource dataSource;

    public void addMember(String channelId, String userId) {
        String sql = """
            insert into channelMember (id, channelId, userId, createdAt, updatedAt)
            values (?, ?, ?, ?, ?)
            """;
        Instant now = Instant.now();
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, channelId);
            statement.setString(3, userId);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isDuplicateKey(exception)) {
                throw new ChannelMemberExistedException();
            }
            throw new IllegalStateException("Failed to add channel member", exception);
        }
    }

    public boolean removeMember(String channelId, String userId) {
        String sql = "delete from channelMember where channelId = ? and userId = ?";
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, channelId);
            statement.setString(2, userId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to remove channel member", exception);
        }
    }

    public boolean isMember(String channelId, String userId) {
        String sql = "select 1 from channelMember where channelId = ? and userId = ? limit 1";
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, channelId);
            statement.setString(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to check channel membership", exception);
        }
    }

    public List<User> listMembers(String channelId) {
        String sql = """
            select u.id, u.clientUserId, u.name, u.profileImgUrl, u.createdAt
            from channelMember cm
            join `user` u on u.id = cm.userId
            where cm.channelId = ?
            order by cm.createdAt asc
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, channelId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<User> members = new ArrayList<>();
                while (resultSet.next()) {
                    Timestamp createdAt = resultSet.getTimestamp("createdAt");
                    members.add(User.builder()
                        .id(resultSet.getString("id"))
                        .clientUserId(resultSet.getString("clientUserId"))
                        .name(resultSet.getString("name"))
                        .profileImgUrl(resultSet.getString("profileImgUrl"))
                        .createdAt(createdAt != null ? createdAt.toInstant() : null)
                        .build());
                }
                return members;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to list channel members", exception);
        }
    }

    public List<String> listChannelIdsByUser(String userId) {
        String sql = """
            select channelId
            from channelMember
            where userId = ?
            order by createdAt desc
            """;
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> channelIds = new ArrayList<>();
                while (resultSet.next()) {
                    channelIds.add(resultSet.getString("channelId"));
                }
                return channelIds;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to list user channel memberships", exception);
        }
    }

    private boolean isDuplicateKey(SQLException exception) {
        String sqlState = exception.getSQLState();
        return (sqlState != null && sqlState.startsWith("23")) || exception.getErrorCode() == 1062;
    }
}
