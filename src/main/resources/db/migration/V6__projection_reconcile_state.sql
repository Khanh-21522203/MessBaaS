CREATE TABLE projectionReconcileState (
    scope VARCHAR(64) PRIMARY KEY,
    checkpoint BIGINT NOT NULL,
    updatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO projectionReconcileState (scope, checkpoint)
VALUES ('messageProjection', 0)
ON DUPLICATE KEY UPDATE checkpoint = checkpoint;
