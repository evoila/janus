package com.evoila.janus.common.config;

import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Unified proxy configuration properties Consolidates all service configurations (Loki, Thanos,
 * Tempo) into a single structure with shared settings and service-specific URLs and types
 */
@Data
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {

  private Map<String, ServiceConfig> services = new HashMap<>();
  private ProxyConfig config = new ProxyConfig();

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ServiceConfig {
    private String url;
    private String type; // logql, promql, traceql
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ProxyConfig {
    @Builder.Default private EnforcementConfig enforcement = new EnforcementConfig();
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class EnforcementConfig {
    @Builder.Default private boolean enabled = true;
    @Builder.Default private String tenantClaim = "groups";
    @Builder.Default private boolean skipForAdmins = true;
    @Builder.Default private String adminGroup = "admin";
  }
}
