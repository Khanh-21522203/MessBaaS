package com.java_mess.java_mess.config;

import java.util.Properties;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AppConfig {
    int port;
    int bossThreads;
    int workerThreads;
    int businessThreads;
    String dbUrl;
    String dbUsername;
    String dbPassword;
    String dbDriverClassName;
    int hotBufferPerChannel;
    String deploymentMode;
    boolean localProjectionCacheEnabled;
    boolean redisEnabled;
    String redisHost;
    int redisPort;
    int redisTimeoutMillis;
    int projectionPollMillis;
    int projectionBatchSize;
    int projectionMaxAttempts;
    int projectionBaseBackoffMillis;
    int projectionLeaseMillis;
    int projectionReconcileBatchSize;
    int projectionReconcileIntervalSeconds;
    int inboxDefaultLimit;
    int inboxMaxLimit;

    public static AppConfig from(Properties properties) {
        return AppConfig.builder()
            .port(intProperty(properties, "server.port", 8082))
            .bossThreads(intProperty(properties, "server.bossThreads", 1))
            .workerThreads(intProperty(properties, "server.workerThreads", 0))
            .businessThreads(intProperty(properties, "server.businessThreads", 0))
            .dbUrl(requiredProperty(properties, "db.url"))
            .dbUsername(requiredProperty(properties, "db.username"))
            .dbPassword(requiredProperty(properties, "db.password"))
            .dbDriverClassName(requiredProperty(properties, "db.driverClassName"))
            .hotBufferPerChannel(intProperty(properties, "message.hotBufferPerChannel", 2048))
            .deploymentMode(stringProperty(properties, "deployment.mode", "single-node"))
            .localProjectionCacheEnabled(booleanProperty(properties, "cache.localProjection.enabled", true))
            .redisEnabled(booleanProperty(properties, "redis.enabled", false))
            .redisHost(stringProperty(properties, "redis.host", "localhost"))
            .redisPort(intProperty(properties, "redis.port", 6379))
            .redisTimeoutMillis(intProperty(properties, "redis.timeoutMillis", 2_000))
            .projectionPollMillis(intProperty(properties, "projection.pollMillis", 200))
            .projectionBatchSize(intProperty(properties, "projection.batchSize", 100))
            .projectionMaxAttempts(intProperty(properties, "projection.maxAttempts", 10))
            .projectionBaseBackoffMillis(intProperty(properties, "projection.baseBackoffMillis", 200))
            .projectionLeaseMillis(intProperty(properties, "projection.leaseMillis", 5_000))
            .projectionReconcileBatchSize(intProperty(properties, "projection.reconcileBatchSize", 500))
            .projectionReconcileIntervalSeconds(intProperty(properties, "projection.reconcileIntervalSeconds", 300))
            .inboxDefaultLimit(intProperty(properties, "inbox.defaultLimit", 50))
            .inboxMaxLimit(intProperty(properties, "inbox.maxLimit", 200))
            .build();
    }

    private static int intProperty(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static boolean booleanProperty(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private static String stringProperty(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required config: " + key);
        }
        return value.trim();
    }
}
