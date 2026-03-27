package com.java_mess.java_mess.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppConfigLoader {
    private AppConfigLoader() {
    }

    public static AppConfig load() {
        Properties properties = new Properties();
        try (InputStream inputStream = AppConfigLoader.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (inputStream == null) {
                throw new IllegalStateException("application.properties not found on classpath");
            }
            properties.load(inputStream);
            return AppConfig.from(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load application properties", exception);
        }
    }
}
