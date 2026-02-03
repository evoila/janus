package com.evoila.janus.common.labelstore;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.base.BaseUnitTest;
import com.evoila.janus.security.config.OAuthToken;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test for ConfigMapLabelStore business logic Tests admin access, group-based
 * constraints, tenant headers, and header merging
 */
class ConfigMapLabelStoreTest extends BaseUnitTest {

  private OAuthToken adminToken;
  private OAuthToken orderServiceTeamToken;
  private OAuthToken myServiceTeamToken;
  private OAuthToken seeAllToken;
  private OAuthToken multiGroupToken;

  @Override
  protected void setupCommonMocks() {
    super.setupCommonMocks();

    // Create test tokens with realistic groups from the config
    adminToken = createToken("admin-user", Arrays.asList("admin"));
    orderServiceTeamToken = createToken("order-service-user", Arrays.asList("order-service-team"));
    myServiceTeamToken = createToken("my-service-user", Arrays.asList("my-service-team"));
    seeAllToken = createToken("see-all-user", Arrays.asList("see-all"));
    multiGroupToken =
        createToken("multi-group-user", Arrays.asList("order-service-team", "my-service-team"));
  }

  private OAuthToken createToken(String username, List<String> groups) {
    OAuthToken token = new OAuthToken();
    token.setPreferredUsername(username);
    token.setGroups(groups);
    return token;
  }

  @Test
  void testInitializeWithNonConfigMapType() {
    // Should log info and not load ConfigMap
    org.springframework.test.util.ReflectionTestUtils.setField(
        configMapLabelStore, "labelStoreType", "config");
    configMapLabelStore.initialize();

    assertFalse(
        configMapLabelStore.isInitialized(), "Should not be initialized with non-configmap type");
  }

  @Test
  void testStopWatching() {
    setupConfigMapLabelStoreWithRealConfig();

    // Should not throw when stopping the watcher
    assertDoesNotThrow(() -> configMapLabelStore.stopWatching(), "stopWatching should not throw");
  }

  @Test
  void testInitializeWithNullPath() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        configMapLabelStore, "configMapPath", null);
    org.springframework.test.util.ReflectionTestUtils.setField(
        configMapLabelStore, "labelStoreType", "configmap");

    // Should not throw, just log warning
    assertDoesNotThrow(
        () -> configMapLabelStore.initialize(), "Initialize with null path should not throw");
  }

  @Test
  void testInitializeWithNonExistentPath() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        configMapLabelStore, "configMapPath", "/nonexistent/path/config.yaml");
    org.springframework.test.util.ReflectionTestUtils.setField(
        configMapLabelStore, "labelStoreType", "configmap");

    // Should not throw, just log warning
    assertDoesNotThrow(
        () -> configMapLabelStore.initialize(),
        "Initialize with non-existent path should not throw");
  }

  @Test
  void testReloadConfigMapIgnoresUnmodifiedFile() throws Exception {
    String configContent =
        """
            thanos:
              user-label-constraints:
                dev-team:
                  labels:
                    - "*"
                  namespace:
                    - demo
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);

    // Second initialize should not reload because file hasn't changed
    configMapLabelStore.initialize();

    assertEquals(1, configMapLabelStore.getConstraintCount());
  }

  @Test
  void testHasAdminConfigurationWithAdminInConfig() throws Exception {
    String configContent =
        """
            admin:
              labels:
                - "*"
            thanos:
              user-label-constraints:
                admin:
                  labels:
                    - "*"
                  namespace:
                    - "*"
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);

    // Admin user should have cluster-wide access via admin configuration
    assertTrue(
        configMapLabelStore.hasClusterWideAccess(adminToken),
        "Admin should have cluster-wide access");
  }

  @Test
  void testEmptyYamlConfig() throws Exception {
    // YAML that parses to null
    String configContent = "---\n";

    File configFile = createTestConfigFile(configContent);

    // Should handle gracefully
    assertDoesNotThrow(() -> setupConfigMapLabelStore(configFile), "Empty YAML should not throw");
  }

  @Test
  void testAdminAccess() {
    setupConfigMapLabelStoreWithRealConfig();

    // Admin user should have cluster-wide access
    assertTrue(
        configMapLabelStore.hasClusterWideAccess(adminToken),
        "Admin user should have cluster-wide access");

    // Regular users should not have cluster-wide access
    assertFalse(
        configMapLabelStore.hasClusterWideAccess(orderServiceTeamToken),
        "Order service team user should not have cluster-wide access");
    assertFalse(
        configMapLabelStore.hasClusterWideAccess(myServiceTeamToken),
        "My service team user should not have cluster-wide access");
  }

  @Test
  void testAdminTenantHeaders() {
    setupConfigMapLabelStoreWithRealConfig();

    Map<String, String> headers = configMapLabelStore.getTenantHeaders(adminToken, "loki");
    assertNotNull(headers, "Admin tenant headers should not be null");
    assertEquals(2, headers.size(), "Admin should have 2 tenant headers");
    assertEquals(
        "tenant1", headers.get("X-Scope-OrgID"), "Admin should have X-Scope-OrgID=tenant1");
    assertEquals(
        "tenant3", headers.get("X-Scope-OrgID2"), "Admin should have X-Scope-OrgID2=tenant3");
  }

  @Test
  void testThanosServiceConstraints() {
    setupConfigMapLabelStoreWithRealConfig();

    // Test order-service-team constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(orderServiceTeamToken, "thanos");
    assertNotNull(constraints, "Thanos constraints should not be null");
    assertTrue(constraints.containsKey("labels"), "Should have labels constraint");
    assertTrue(constraints.containsKey("namespace"), "Should have namespace constraint");

    Set<String> labels = constraints.get("labels");
    Set<String> namespaces = constraints.get("namespace");
    assertTrue(labels.contains("*"), "Should have all labels access");
    assertTrue(namespaces.contains("demo"), "Should have demo namespace access");
    assertEquals(1, namespaces.size(), "Should have exactly 1 namespace");

    // Test my-service-team constraints
    constraints = configMapLabelStore.getServiceLabelConstraints(myServiceTeamToken, "thanos");
    assertNotNull(constraints, "Thanos constraints should not be null");
    namespaces = constraints.get("namespace");
    assertTrue(namespaces.contains("observability"), "Should have observability namespace access");
  }

  @Test
  void testLokiServiceConstraints() {
    setupConfigMapLabelStoreWithRealConfig();

    // Test order-service-team constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(orderServiceTeamToken, "loki");
    assertNotNull(constraints, "Loki constraints should not be null");
    assertTrue(constraints.containsKey("labels"), "Should have labels constraint");
    assertTrue(
        constraints.containsKey("k8s_namespace_name"), "Should have k8s_namespace_name constraint");
    assertTrue(constraints.containsKey("service_name"), "Should have service_name constraint");

    Set<String> namespaces = constraints.get("k8s_namespace_name");
    Set<String> services = constraints.get("service_name");
    assertTrue(namespaces.contains("demo"), "Should have demo namespace access");
    assertTrue(services.contains("*order-service"), "Should have order-service access");
  }

  @Test
  void testTempoServiceConstraints() {
    setupConfigMapLabelStoreWithRealConfig();

    // Test order-service-team constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(orderServiceTeamToken, "tempo");
    assertNotNull(constraints, "Tempo constraints should not be null");
    assertTrue(constraints.containsKey("labels"), "Should have labels constraint");
    assertTrue(
        constraints.containsKey("resource.service.name"),
        "Should have resource.service.name constraint");

    Set<String> services = constraints.get("resource.service.name");
    assertTrue(services.contains("*"), "Should have all service access");
  }

  @Test
  void testGroupBasedTenantHeaders() {
    setupConfigMapLabelStoreWithRealConfig();

    // Test order-service-team headers for different services
    Map<String, String> headers =
        configMapLabelStore.getTenantHeaders(orderServiceTeamToken, "thanos");
    assertNotNull(headers, "Order service team tenant headers should not be null");
    assertEquals("tenant1", headers.get("X-Scope-OrgID"), "Should have tenant1 for thanos");

    headers = configMapLabelStore.getTenantHeaders(orderServiceTeamToken, "loki");
    assertNotNull(headers, "Order service team tenant headers should not be null");
    assertEquals("tenant1", headers.get("X-Scope-OrgID"), "Should have tenant1 for loki");

    headers = configMapLabelStore.getTenantHeaders(orderServiceTeamToken, "tempo");
    assertNotNull(headers, "Order service team tenant headers should not be null");
    assertEquals("tenant1", headers.get("X-Scope-OrgID"), "Should have tenant1 for tempo");

    // Test my-service-team headers
    headers = configMapLabelStore.getTenantHeaders(myServiceTeamToken, "thanos");
    assertEquals("tenant2", headers.get("X-Scope-OrgID"), "Should have tenant2 for thanos");

    headers = configMapLabelStore.getTenantHeaders(myServiceTeamToken, "loki");
    assertEquals("tenant1", headers.get("X-Scope-OrgID"), "Should have tenant1 for loki");

    headers = configMapLabelStore.getTenantHeaders(myServiceTeamToken, "tempo");
    assertEquals("tenant2", headers.get("X-Scope-OrgID"), "Should have tenant2 for tempo");
  }

  @Test
  void testHeaderMergingForMultiGroupUser() {
    setupConfigMapLabelStoreWithRealConfig();

    // User belongs to both order-service-team and my-service-team
    // order-service-team: X-Scope-OrgID=tenant1
    // my-service-team: X-Scope-OrgID=tenant2
    // Should be combined as: X-Scope-OrgID=tenant1|tenant2

    Map<String, String> headers = configMapLabelStore.getTenantHeaders(multiGroupToken, "thanos");
    assertNotNull(headers, "Multi-group tenant headers should not be null");
    assertEquals(
        "tenant1|tenant2",
        headers.get("X-Scope-OrgID"),
        "Should combine different tenant values with pipe separator");

    // For loki, both groups have same tenant value (tenant1), so no merging needed
    headers = configMapLabelStore.getTenantHeaders(multiGroupToken, "loki");
    assertEquals(
        "tenant1",
        headers.get("X-Scope-OrgID"),
        "Should keep single value when both groups have same tenant");
  }

  @Test
  void testMultipleHeaderEntriesForSingleGroup() throws Exception {
    // Test that multiple header entries for the same header name in a single group
    // are combined with pipe separator
    String configContent =
        """
            loki:
              tenant-header-constraints:
                platform-engineers:
                  header:
                    - X-Scope-OrgID: mgmt
                    - X-Scope-OrgID: tenant-a
              user-label-constraints:
                platform-engineers:
                  labels:
                    - "*"
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);

    OAuthToken platformToken = createToken("platform-user", List.of("platform-engineers"));
    Map<String, String> headers = configMapLabelStore.getTenantHeaders(platformToken, "loki");

    assertNotNull(headers, "Platform engineers headers should not be null");
    assertEquals(
        "mgmt|tenant-a",
        headers.get("X-Scope-OrgID"),
        "Should combine multiple X-Scope-OrgID entries with pipe separator");
  }

  @Test
  void testLabelAccess() {
    setupConfigMapLabelStoreWithRealConfig();

    // All groups have "*" for labels, so they should have access to any label
    assertTrue(
        configMapLabelStore.hasLabelAccess(orderServiceTeamToken, "any-label"),
        "Order service team should have access to any label");
    assertTrue(
        configMapLabelStore.hasLabelAccess(myServiceTeamToken, "any-label"),
        "My service team should have access to any label");
    assertTrue(
        configMapLabelStore.hasLabelAccess(seeAllToken, "any-label"),
        "See all user should have access to any label");
  }

  @Test
  void testAdminLabelAccess() {
    setupConfigMapLabelStoreWithRealConfig();

    // Admin should have access to all labels
    assertTrue(
        configMapLabelStore.hasLabelAccess(adminToken, "any-label"),
        "Admin should have access to any label");
    assertTrue(
        configMapLabelStore.hasLabelAccess(adminToken, "service"),
        "Admin should have access to service label");
  }

  @Test
  void testGetAllowedLabels() {
    setupConfigMapLabelStoreWithRealConfig();

    // All groups have "*" for labels
    Set<String> allowedLabels = configMapLabelStore.getAllowedLabels(orderServiceTeamToken);
    assertTrue(allowedLabels.contains("*"), "Should have access to all labels");

    allowedLabels = configMapLabelStore.getAllowedLabels(myServiceTeamToken);
    assertTrue(allowedLabels.contains("*"), "Should have access to all labels");

    allowedLabels = configMapLabelStore.getAllowedLabels(seeAllToken);
    assertTrue(allowedLabels.contains("*"), "Should have access to all labels");
  }

  @Test
  void testNoServiceConfiguration() {
    setupConfigMapLabelStoreWithRealConfig();

    // Should throw exception for unknown service
    assertThrows(
        SecurityException.class,
        () -> {
          configMapLabelStore.getServiceLabelConstraints(orderServiceTeamToken, "unknown-service");
        },
        "Should throw SecurityException for unknown service");
  }

  @Test
  void testServiceAwareLabelStore() {
    setupConfigMapLabelStoreWithRealConfig();

    ServiceAwareLabelStore thanosStore = new ServiceAwareLabelStore(configMapLabelStore);

    Map<String, Set<String>> constraints =
        thanosStore.getServiceLabelConstraints(orderServiceTeamToken, "thanos");
    assertNotNull(constraints, "Service-aware store should return constraints");
    assertTrue(constraints.containsKey("labels"), "Should have labels constraint");
    assertTrue(constraints.containsKey("namespace"), "Should have namespace constraint");
  }

  @Test
  void testNoTenantHeadersForUnknownService() {
    setupConfigMapLabelStoreWithRealConfig();

    // Service without tenant header configuration should return empty map
    Map<String, String> headers =
        configMapLabelStore.getTenantHeaders(orderServiceTeamToken, "unknown-service");
    assertNotNull(headers, "Headers should not be null");
    assertTrue(headers.isEmpty(), "Should return empty headers for unknown service");
  }

  @Test
  void testNoTenantHeadersForUserWithoutGroups() {
    setupConfigMapLabelStoreWithRealConfig();

    // User without any groups should get empty headers
    OAuthToken userWithoutGroups = createToken("no-groups-user", Arrays.asList());
    Map<String, String> headers = configMapLabelStore.getTenantHeaders(userWithoutGroups, "loki");
    assertNotNull(headers, "Headers should not be null");
    assertTrue(headers.isEmpty(), "Should return empty headers for user without groups");
  }

  @Test
  void testGetExcludedLabels_WithExclusions() throws Exception {
    // Config with !=label exclusions
    String configContent =
        """
            thanos:
              user-label-constraints:
                dev-team:
                  labels:
                    - "*"
                    - "!=secret_label"
                    - "!=internal_metric"
                  namespace:
                    - demo
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);

    OAuthToken devToken = createToken("dev-user", List.of("dev-team"));
    Set<String> excluded = configMapLabelStore.getExcludedLabels(devToken);

    assertNotNull(excluded, "Excluded labels should not be null");
    assertTrue(excluded.contains("secret_label"), "Should have secret_label excluded");
    assertTrue(excluded.contains("internal_metric"), "Should have internal_metric excluded");
  }

  @Test
  void testGetExcludedLabels_NoExclusions() {
    setupConfigMapLabelStoreWithRealConfig();

    // The real config has no !=exclusions
    Set<String> excluded = configMapLabelStore.getExcludedLabels(orderServiceTeamToken);

    assertNotNull(excluded, "Excluded labels should not be null");
    assertTrue(excluded.isEmpty(), "Should have no excluded labels");
  }

  @Test
  void testGetAllowedLabels_WithSpecificLabels() throws Exception {
    String configContent =
        """
            thanos:
              user-label-constraints:
                restricted-team:
                  labels:
                    - namespace
                    - service
                  namespace:
                    - demo
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);

    OAuthToken restrictedToken = createToken("restricted-user", List.of("restricted-team"));
    Set<String> allowedLabels = configMapLabelStore.getAllowedLabels(restrictedToken);

    assertTrue(allowedLabels.contains("namespace"), "Should have namespace in allowed labels");
    assertTrue(allowedLabels.contains("service"), "Should have service in allowed labels");
  }

  @Test
  void testHasLabelAccess_SpecificLabels() throws Exception {
    String configContent =
        """
            thanos:
              user-label-constraints:
                restricted-team:
                  labels:
                    - namespace
                    - service
                  namespace:
                    - demo
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);

    OAuthToken restrictedToken = createToken("restricted-user", List.of("restricted-team"));

    assertTrue(
        configMapLabelStore.hasLabelAccess(restrictedToken, "namespace"),
        "Should have access to namespace");
    assertTrue(
        configMapLabelStore.hasLabelAccess(restrictedToken, "service"),
        "Should have access to service");
    assertFalse(
        configMapLabelStore.hasLabelAccess(restrictedToken, "secret"),
        "Should NOT have access to secret");
  }

  @Test
  void testIsInitialized() {
    setupConfigMapLabelStoreWithRealConfig();

    assertTrue(configMapLabelStore.isInitialized(), "Should be initialized after loading config");
  }

  @Test
  void testGetConstraintCount() {
    setupConfigMapLabelStoreWithRealConfig();

    int count = configMapLabelStore.getConstraintCount();
    assertTrue(count > 0, "Should have at least 1 service constraint");
    assertEquals(3, count, "Should have 3 services (thanos, loki, tempo)");
  }

  @Test
  void testNoAccessForUserWithoutGroups() {
    setupConfigMapLabelStoreWithRealConfig();

    OAuthToken noGroupToken = createToken("no-group-user", List.of());

    assertThrows(
        SecurityException.class,
        () -> configMapLabelStore.getServiceLabelConstraints(noGroupToken, "thanos"),
        "Should throw SecurityException for user without matching groups");
  }

  @Test
  void testMultiGroupConstraintMerging() {
    setupConfigMapLabelStoreWithRealConfig();

    // multiGroupToken belongs to both order-service-team and my-service-team
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(multiGroupToken, "thanos");

    Set<String> namespaces = constraints.get("namespace");
    assertTrue(namespaces.contains("demo"), "Should have demo from order-service-team");
    assertTrue(
        namespaces.contains("observability"), "Should have observability from my-service-team");
  }

  @Test
  void testAdminUserGetsWildcardConstraintsForAllServices() {
    setupConfigMapLabelStoreWithRealConfig();

    // Create admin token
    OAuthToken localAdminToken = createToken("admin", Arrays.asList("admin"));

    // Test all services
    String[] services = {"thanos", "loki", "tempo"};

    for (String service : services) {
      // Admin should get wildcard constraints
      Map<String, Set<String>> constraints =
          configMapLabelStore.getServiceLabelConstraints(localAdminToken, service);

      assertNotNull(constraints, "Admin constraints should not be null for service: " + service);
      assertTrue(
          constraints.containsKey("labels"),
          "Admin should have labels constraint for service: " + service);
      assertTrue(
          constraints.containsKey("service"),
          "Admin should have service constraint for service: " + service);
      assertTrue(
          constraints.containsKey("namespace"),
          "Admin should have namespace constraint for service: " + service);
      assertTrue(
          constraints.containsKey("job"),
          "Admin should have job constraint for service: " + service);

      // All constraints should be wildcard
      assertEquals(
          Set.of("*"),
          constraints.get("labels"),
          "Admin labels should be wildcard for service: " + service);
      assertEquals(
          Set.of("*"),
          constraints.get("service"),
          "Admin service should be wildcard for service: " + service);
      assertEquals(
          Set.of("*"),
          constraints.get("namespace"),
          "Admin namespace should be wildcard for service: " + service);
      assertEquals(
          Set.of("*"),
          constraints.get("job"),
          "Admin job should be wildcard for service: " + service);
    }
  }
}
