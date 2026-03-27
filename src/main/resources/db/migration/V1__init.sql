CREATE TABLE user (
    id VARCHAR(36) PRIMARY KEY,
    clientUserId VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    profileImgUrl TEXT,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_user_clientUserId UNIQUE (clientUserId)
);

CREATE TABLE channel (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    clientReferenceId VARCHAR(255) NOT NULL,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_channel_clientReferenceId UNIQUE (clientReferenceId)
);

CREATE TABLE message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channelId VARCHAR(36) NOT NULL,
    userId VARCHAR(36) NOT NULL,
    message TEXT NOT NULL,
    imgUrl VARCHAR(2000) NOT NULL,
    isDeleted TINYINT(1) NOT NULL,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_message_channel FOREIGN KEY (channelId) REFERENCES channel(id) ON DELETE CASCADE,
    CONSTRAINT fk_message_user FOREIGN KEY (userId) REFERENCES user(id) ON DELETE CASCADE
);

CREATE INDEX idx_message_channel_id_id ON message(channelId, id);

CREATE TABLE userReadMessage (
    id VARCHAR(36) PRIMARY KEY,
    channelId VARCHAR(36) NOT NULL,
    userId VARCHAR(36) NOT NULL,
    lastReadMessage TIMESTAMP NOT NULL,
    createdAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_userReadMessage_channel FOREIGN KEY (channelId) REFERENCES channel(id) ON DELETE CASCADE,
    CONSTRAINT fk_userReadMessage_user FOREIGN KEY (userId) REFERENCES user(id) ON DELETE CASCADE,
    CONSTRAINT unique_channel_user UNIQUE (channelId, userId)
);
