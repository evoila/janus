package com.evoila.janus.common.config;

import java.util.Arrays;
import lombok.Getter;

/**
 * Enumeration of supported observability services in the Janus proxy. Replaces hardcoded string
 * constants for better type safety and maintainability.
 */
@Getter
public enum ServiceType {
  LOKI("loki", "/loki/api"),
  THANOS("thanos", "/thanos/api"),
  TEMPO("tempo", ""); // NO /api prefix for Tempo!

  /** -- GETTER -- Get the service name as used in configuration and endpoints */
  private final String serviceName;

  /** -- GETTER -- Get the API prefix for this service */
  private final String apiPrefix;

  ServiceType(String serviceName, String apiPrefix) {
    this.serviceName = serviceName;
    this.apiPrefix = apiPrefix;
  }

  /**
   * Parse service type from string name (case-insensitive)
   *
   * @param serviceName the service name to parse
   * @return the corresponding ServiceType
   * @throws IllegalArgumentException if the service name is not recognized
   */
  public static ServiceType fromString(String serviceName) {
    if (serviceName == null) {
      throw new IllegalArgumentException("Service name cannot be null");
    }

    return Arrays.stream(values())
        .filter(service -> service.serviceName.equalsIgnoreCase(serviceName.trim()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown service: " + serviceName));
  }

  @Override
  public String toString() {
    return serviceName;
  }
}
