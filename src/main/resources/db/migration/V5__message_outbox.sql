CREATE TABLE messageOutbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    messageId BIGINT NOT NULL,
    channelId VARCHAR(36) NOT NULL,
    senderUserId VARCHAR(36) NOT NULL,
    senderClientUserId VARCHAR(255) NOT NULL,
    clientMessageId VARCHAR(128) NOT NULL,
    messageBody TEXT NULL,
    imgUrl VARCHAR(2000) NULL,
    messageCreatedAt TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    attemptCount INT NOT NULL DEFAULT 0,
    nextAttemptAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lockedUntil TIMESTAMP NULL DEFAULT NULL,
    lastError TEXT NULL,
    processedAt TIMESTAMP NULL DEFAULT NULL,
    createdAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_messageOutbox_message FOREIGN KEY (messageId) REFERENCES message(id) ON DELETE CASCADE
);

CREATE INDEX idx_messageOutbox_status_attempt
    ON messageOutbox(status, nextAttemptAt, id);

CREATE INDEX idx_messageOutbox_message
    ON messageOutbox(messageId);
