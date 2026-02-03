package com.evoila.janus.common.labelstore;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.security.config.OAuthToken;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminTenantHeadersTest extends com.evoila.janus.base.BaseUnitTest {

  private ConfigMapLabelStore labelStore;

  @BeforeEach
  void setUp() throws IOException {
    super.setUpBase();
    labelStore = new ConfigMapLabelStore();

    // Create test configuration
    String configContent =
        """
            admin:
              labels:
                - "*"
              header:
                X-Tenant: admin
                X-Scope-OrgID: admin-org

            loki:
              tenant-header-constraints:
                users:
                  header:
                    X-Tenant: test
                    X-Scope-OrgID: test-org
              user-label-constraints:
                test-user:
                  labels:
                    - "service"
                    - "environment"
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);
  }

  @Test
  void testAdminClusterWideAccess() {
    OAuthToken adminToken = createToken("admin-user", List.of("admin"));

    // Admin should have cluster-wide access
    assertTrue(
        labelStore.hasClusterWideAccess(adminToken), "Admin should have cluster-wide access");

    // Admin should have access to all labels
    assertTrue(
        labelStore.hasLabelAccess(adminToken, "any-label"),
        "Admin should have access to any label");
  }

  @Test
  void testRegularUserNoClusterWideAccess() {
    OAuthToken userToken = createToken("test-user", List.of("users"));

    // Regular user should not have cluster-wide access
    assertFalse(
        labelStore.hasClusterWideAccess(userToken),
        "Regular user should not have cluster-wide access");
  }

  @Test
  void testMultipleAdminHeaders() throws Exception {
    // Create configuration with multiple admin headers
    String multiHeaderConfig =
        """
            admin:
              labels:
                - "*"
              header:
                - "X-Tenant: admin"
                - "X-Scope-OrgID: admin-org"
                - "X-Custom-Header: admin-custom"
            """;

    File configFile = createTestConfigFile(multiHeaderConfig);

    // Create new label store instance
    ConfigMapLabelStore labelStore2 = new ConfigMapLabelStore();
    Field configMapPathField = ConfigMapLabelStore.class.getDeclaredField("configMapPath");
    configMapPathField.setAccessible(true);
    configMapPathField.set(labelStore2, configFile.getAbsolutePath());

    Field labelStoreTypeField = ConfigMapLabelStore.class.getDeclaredField("labelStoreType");
    labelStoreTypeField.setAccessible(true);
    labelStoreTypeField.set(labelStore2, "configmap");

    Method initializeMethod = ConfigMapLabelStore.class.getDeclaredMethod("initialize");
    initializeMethod.setAccessible(true);
    initializeMethod.invoke(labelStore2);

    OAuthToken adminToken = createToken("admin-user", List.of("admin"));
    Map<String, String> headers = labelStore2.getTenantHeaders(adminToken, "loki");

    // Should have all admin headers
    assertEquals("admin", headers.get("X-Tenant"), "Should have admin tenant");
    assertEquals("admin-org", headers.get("X-Scope-OrgID"), "Should have admin org");
    assertEquals("admin-custom", headers.get("X-Custom-Header"), "Should have admin custom header");
    assertEquals(3, headers.size(), "Should have exactly 3 admin headers");
  }

  private OAuthToken createToken(String username, List<String> groups) {
    OAuthToken token = new OAuthToken();
    token.setPreferredUsername(username);
    token.setGroups(groups);
    return token;
  }
}
