package com.evoila.janus.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// @SpringBootApplication(scanBasePackages = "com.evoila.janus")
@SpringBootApplication()
@EnableConfigurationProperties
public class JanusApplication {

  public static void main(String[] args) {
    SpringApplication.run(JanusApplication.class, args);
  }
}
