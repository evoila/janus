package com.evoila.janus.app;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration
@ImportRuntimeHints(NativeConfiguration.LoggingRuntimeHints.class)
public class NativeConfiguration {

  @SuppressWarnings("java:S1118") // Spring configuration class needs public constructor
  public NativeConfiguration() {
    // Public constructor for Spring
  }

  static class LoggingRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      // Register reflection hints for logging classes
      hints
          .reflection()
          .registerType(ch.qos.logback.classic.LoggerContext.class)
          .registerType(ch.qos.logback.classic.Logger.class)
          .registerType(ch.qos.logback.core.ConsoleAppender.class)
          .registerType(ch.qos.logback.classic.encoder.PatternLayoutEncoder.class);

      // Register resources for logback configuration
      hints
          .resources()
          .registerPattern("logback-spring.xml")
          .registerPattern("logback.xml")
          .registerPattern("application.yml")
          .registerPattern("META-INF/spring.factories");

      // Register Spring Boot logging converters
      try {
        hints
            .reflection()
            .registerType(Class.forName("org.springframework.boot.logging.logback.ColorConverter"))
            .registerType(
                Class.forName(
                    "org.springframework.boot.logging.logback.WhitespaceThrowableProxyConverter"))
            .registerType(
                Class.forName(
                    "org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter"));
      } catch (ClassNotFoundException _) {
        // Classes not available, skip registration
      }
    }
  }
}
