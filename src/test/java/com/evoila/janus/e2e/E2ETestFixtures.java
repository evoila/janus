package com.evoila.janus.e2e;

/**
 * Shared test fixtures for E2E tests. Provides standard test tokens, config paths, and test data
 * constants used across all E2E test classes.
 */
public final class E2ETestFixtures {

  private E2ETestFixtures() {
    // Utility class
  }

  // ============ Test Tokens ============
  // These tokens are decoded by MockJwtConfig to extract group membership

  /** Token for order-service-team - has access to demo namespace/services */
  public static final String ORDER_TEAM_TOKEN = "test-token-order-service-team";

  /** Token for my-service-team - has access to observability namespace/services */
  public static final String MY_TEAM_TOKEN = "test-token-my-service-team";

  /** Token for admin - has access to all namespaces/services */
  public static final String ADMIN_TOKEN = "test-token-admin";

  /** Token for user in multiple groups - gets union of order-service-team AND my-service-team */
  public static final String MULTI_GROUP_TOKEN = "test-token-multi-group";

  /**
   * Token for multi-group-user identity (used in permissive config where patterns are pre-combined)
   */
  public static final String MULTI_GROUP_USER_TOKEN = "test-token-multi-group-user";

  /** Token for user with no group memberships - should be denied access */
  public static final String NO_ACCESS_TOKEN = "test-token-no-access";

  /** Token for wildcard-team - tests wildcard pattern matching */
  public static final String WILDCARD_TEAM_TOKEN = "test-token-wildcard-team";

  /** Token for exclude-team - tests include/exclude pattern rules */
  public static final String EXCLUDE_TEAM_TOKEN = "test-token-exclude-team";

  /** Token for regex-team - tests regex pattern matching */
  public static final String REGEX_TEAM_TOKEN = "test-token-regex-team";

  // ============ Config Paths ============
  // Paths to different configuration files for testing various scenarios

  /** Default config - basic exact-match constraints */
  public static final String DEFAULT_CONFIG = "configs/e2e-configmap.yaml";

  /** Strict config - minimal access, exact match only, no wildcards */
  public static final String STRICT_CONFIG = "configs/e2e-strict-configmap.yaml";

  /** Permissive config - broad wildcard access patterns */
  public static final String PERMISSIVE_CONFIG = "configs/e2e-permissive-configmap.yaml";

  /** Exclude config - include/exclude pattern testing */
  public static final String EXCLUDE_CONFIG = "configs/e2e-exclude-configmap.yaml";

  /** Regex config - complex regex patterns */
  public static final String REGEX_CONFIG = "configs/e2e-regex-configmap.yaml";

  // ============ Test Namespaces ============
  // Namespace values used in test data

  public static final String NAMESPACE_DEMO = "demo";
  public static final String NAMESPACE_DEMO_ORDERS = "demo-orders";
  public static final String NAMESPACE_DEMO_PAYMENTS = "demo-payments";
  public static final String NAMESPACE_DEMO_SENSITIVE = "demo-sensitive";
  public static final String NAMESPACE_OBSERVABILITY = "observability";
  public static final String NAMESPACE_PRODUCTION = "production";
  public static final String NAMESPACE_STAGING_ORDERS = "staging-orders";
  public static final String NAMESPACE_ORDER_SERVICE = "order-service";
  public static final String NAMESPACE_PAYMENT_SERVICE = "payment-service";

  // ============ Test Services ============
  // Service names used in test data

  public static final String SERVICE_ORDER = "order-service";
  public static final String SERVICE_PAYMENT = "payment-service";
  public static final String SERVICE_MONITORING = "monitoring";
  public static final String SERVICE_CRITICAL_APP = "critical-app";
  public static final String SERVICE_ORDER_API = "order-api";
  public static final String SERVICE_PAYMENT_API = "payment-api";
  public static final String SERVICE_USER_PROCESSOR = "user-processor";

  // ============ Tempo Service Names ============
  // Service names for Tempo traces (resource.service.name)

  public static final String TEMPO_SERVICE_DEMO_ORDER = "demo-order-service";
  public static final String TEMPO_SERVICE_DEMO_PAYMENT = "demo-payment-service";
  public static final String TEMPO_SERVICE_OBS_PROMETHEUS = "observability-prometheus";
  public static final String TEMPO_SERVICE_OBS_GRAFANA = "observability-grafana";
  public static final String TEMPO_SERVICE_PROD_CRITICAL = "production-critical-app";
  public static final String TEMPO_SERVICE_STAGING_ORDER = "staging-order-service";

  // ============ Helper Methods ============

  /**
   * Gets the absolute path to a config file from classpath.
   *
   * @param configResource the config resource path (e.g., "configs/e2e-configmap.yaml")
   * @return the absolute file path, or the fallback src/test/resources path
   */
  public static String getConfigPath(String configResource) {
    try {
      java.net.URL resource = E2ETestFixtures.class.getClassLoader().getResource(configResource);
      if (resource != null) {
        return new java.io.File(resource.toURI()).getAbsolutePath();
      }
      return "src/test/resources/" + configResource;
    } catch (Exception _) {
      return "src/test/resources/" + configResource;
    }
  }

  /**
   * Gets the absolute path to the default config file.
   *
   * @return the absolute path to e2e-configmap.yaml
   */
  public static String getDefaultConfigPath() {
    return getConfigPath(DEFAULT_CONFIG);
  }

  /**
   * Gets the absolute path to the strict config file.
   *
   * @return the absolute path to e2e-strict-configmap.yaml
   */
  public static String getStrictConfigPath() {
    return getConfigPath(STRICT_CONFIG);
  }

  /**
   * Gets the absolute path to the permissive config file.
   *
   * @return the absolute path to e2e-permissive-configmap.yaml
   */
  public static String getPermissiveConfigPath() {
    return getConfigPath(PERMISSIVE_CONFIG);
  }

  /**
   * Gets the absolute path to the exclude config file.
   *
   * @return the absolute path to e2e-exclude-configmap.yaml
   */
  public static String getExcludeConfigPath() {
    return getConfigPath(EXCLUDE_CONFIG);
  }

  /**
   * Gets the absolute path to the regex config file.
   *
   * @return the absolute path to e2e-regex-configmap.yaml
   */
  public static String getRegexConfigPath() {
    return getConfigPath(REGEX_CONFIG);
  }
}
