ALTER TABLE userReadMessage
    ADD COLUMN lastReadMessageId BIGINT NULL AFTER userId;

UPDATE userReadMessage urm
SET urm.lastReadMessageId = COALESCE(
    (
        SELECT MAX(m.id)
        FROM message m
        WHERE m.channelId = urm.channelId
          AND m.createdAt <= urm.lastReadMessage
    ),
    0
);

ALTER TABLE userReadMessage
    MODIFY COLUMN lastReadMessageId BIGINT NOT NULL;

ALTER TABLE userReadMessage
    DROP COLUMN lastReadMessage;
