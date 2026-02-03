package com.evoila.janus.common.labelstore;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.base.BaseUnitTest;
import com.evoila.janus.security.config.OAuthToken;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Test for dynamic tenant headers functionality */
class DynamicTenantHeadersTest extends BaseUnitTest {

  private OAuthToken adminToken;

  @Override
  protected void setupCommonMocks() {
    super.setupCommonMocks();

    // Create test tokens
    adminToken = createToken("admin-user", Arrays.asList("admin", "users"));
  }

  private OAuthToken createToken(String username, List<String> groups) {
    OAuthToken token = new OAuthToken();
    token.setPreferredUsername(username);
    token.setGroups(groups);
    return token;
  }

  private String createBasicTestConfig() {
    return """
            admin:
              labels:
                - "*"
              header:
                - "X-Tenant: admin"

            loki:
              user-label-constraints:
                test-user:
                  labels:
                    - "service"
                    - "environment"
                  header:
                    - "X-Tenant: test"

            thanos:
              user-label-constraints:
                test-user:
                  labels:
                    - "job"
                    - "instance"
                  header:
                    - "X-Tenant: test"

            tempo:
              user-label-constraints:
                test-user:
                  labels:
                    - "service.name"
                    - "span.kind"
                  header:
                    - "X-Tenant: test"
            """;
  }

  @Test
  void testOrderServiceTeamTenantHeaders() throws Exception {
    String configContent =
        """
            loki:
              tenant-header-constraints:
                order-service-team:
                  header:
                    - "x-scope-id: demo"
                    - "loki-tenant-id: erik"
            thanos:
              tenant-header-constraints:
                order-service-team:
                  header:
                    - "x-scope-orgid: demo"
            tempo:
              tenant-header-constraints:
                order-service-team:
                  header:
                    - "x-scope-orgid: demo"
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);

    OAuthToken orderServiceToken =
        createToken("order-service-user", Arrays.asList("order-service-team"));

    // Test Loki tenant headers
    Map<String, String> lokiHeaders =
        configMapLabelStore.getTenantHeaders(orderServiceToken, "loki");
    assertNotNull(lokiHeaders);
    assertEquals("demo", lokiHeaders.get("x-scope-id"));
    assertEquals("erik", lokiHeaders.get("loki-tenant-id"));

    // Test Thanos tenant headers
    Map<String, String> thanosHeaders =
        configMapLabelStore.getTenantHeaders(orderServiceToken, "thanos");
    assertNotNull(thanosHeaders);
    assertEquals("demo", thanosHeaders.get("x-scope-orgid"));

    // Test Tempo tenant headers
    Map<String, String> tempoHeaders =
        configMapLabelStore.getTenantHeaders(orderServiceToken, "tempo");
    assertNotNull(tempoHeaders);
    assertEquals("demo", tempoHeaders.get("x-scope-orgid"));
  }

  @Test
  void testAdminTenantHeaders() throws Exception {
    File configFile = createTestConfigFile(createBasicTestConfig());
    setupConfigMapLabelStore(configFile);

    Map<String, String> lokiHeaders = configMapLabelStore.getTenantHeaders(adminToken, "loki");
    assertNotNull(lokiHeaders, "Admin Loki tenant headers should not be null");
    assertTrue(lokiHeaders.containsKey("X-Tenant"), "Should have X-Tenant header");
    assertEquals("admin", lokiHeaders.get("X-Tenant"), "Should have admin tenant value");

    Map<String, String> thanosHeaders = configMapLabelStore.getTenantHeaders(adminToken, "thanos");
    assertNotNull(thanosHeaders, "Admin Thanos tenant headers should not be null");
    assertTrue(thanosHeaders.containsKey("X-Tenant"), "Should have X-Tenant header");
    assertEquals("admin", thanosHeaders.get("X-Tenant"), "Should have admin tenant value");

    Map<String, String> tempoHeaders = configMapLabelStore.getTenantHeaders(adminToken, "tempo");
    assertNotNull(tempoHeaders, "Admin Tempo tenant headers should not be null");
    assertTrue(tempoHeaders.containsKey("X-Tenant"), "Should have X-Tenant header");
    assertEquals("admin", tempoHeaders.get("X-Tenant"), "Should have admin tenant value");
  }
}
