ALTER TABLE message
    MODIFY COLUMN imgUrl VARCHAR(2000) NULL;

ALTER TABLE message
    ADD COLUMN clientMessageId VARCHAR(128) NULL AFTER userId;

UPDATE message
SET clientMessageId = CONCAT('legacy-', id)
WHERE clientMessageId IS NULL;

ALTER TABLE message
    MODIFY COLUMN clientMessageId VARCHAR(128) NOT NULL;

CREATE UNIQUE INDEX uq_message_channel_client_message
    ON message(channelId, clientMessageId);
