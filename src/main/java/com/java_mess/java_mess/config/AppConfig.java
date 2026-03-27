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
            .build();
    }

    private static int intProperty(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required config: " + key);
        }
        return value.trim();
    }
}
