package com.evoila.janus.common.labelstore;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.security.config.OAuthToken;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("labelstore")
@Tag("configmap")
class ConfigMapLoadingTest extends com.evoila.janus.base.BaseUnitTest {

  private static final Logger log = LoggerFactory.getLogger(ConfigMapLoadingTest.class);
  private ConfigMapLabelStore labelStore;

  @BeforeEach
  void setUp()
      throws IOException,
          NoSuchFieldException,
          IllegalAccessException,
          NoSuchMethodException,
          InvocationTargetException {
    super.setUpBase();
    labelStore = new ConfigMapLabelStore();

    // Set up label store with test configuration
    java.lang.reflect.Field labelStoreTypeField =
        ConfigMapLabelStore.class.getDeclaredField("labelStoreType");
    labelStoreTypeField.setAccessible(true);
    labelStoreTypeField.set(labelStore, "configmap");

    java.lang.reflect.Field configMapPathField =
        ConfigMapLabelStore.class.getDeclaredField("configMapPath");
    configMapPathField.setAccessible(true);
    configMapPathField.set(labelStore, "src/test/resources/configs/local-configmap.yaml");

    java.lang.reflect.Method initializeMethod =
        ConfigMapLabelStore.class.getDeclaredMethod("initialize");
    initializeMethod.setAccessible(true);
    initializeMethod.invoke(labelStore);
  }

  @Test
  void testConfigMapLoading() {
    // Test that the label store is properly initialized
    assertTrue(labelStore.isInitialized(), "Label store should be initialized");
    assertTrue(labelStore.getConstraintCount() > 0, "Should have loaded constraints");

    log.info("Label store initialized with {} services", labelStore.getConstraintCount());
  }

  @Test
  void testUserConstraints() {
    OAuthToken userToken = createToken("order-service-team", Arrays.asList("order-service-team"));

    // Test Loki constraints
    Map<String, java.util.Set<String>> lokiConstraints =
        labelStore.getServiceLabelConstraints(userToken, "loki");
    assertNotNull(lokiConstraints, "Loki constraints should not be null");
    assertTrue(lokiConstraints.containsKey("labels"), "Should have labels constraint");

    // Test Thanos constraints
    Map<String, java.util.Set<String>> thanosConstraints =
        labelStore.getServiceLabelConstraints(userToken, "thanos");
    assertNotNull(thanosConstraints, "Thanos constraints should not be null");
    assertTrue(thanosConstraints.containsKey("labels"), "Should have labels constraint");

    // Test Tempo constraints
    Map<String, java.util.Set<String>> tempoConstraints =
        labelStore.getServiceLabelConstraints(userToken, "tempo");
    assertNotNull(tempoConstraints, "Tempo constraints should not be null");
    assertTrue(tempoConstraints.containsKey("labels"), "Should have labels constraint");
  }

  @Test
  void testAdminAccess() {
    OAuthToken adminToken = createToken("admin", Arrays.asList("admin"));

    // Admin should have cluster-wide access
    assertTrue(
        labelStore.hasClusterWideAccess(adminToken), "Admin should have cluster-wide access");

    // Admin should have access to all labels
    assertTrue(
        labelStore.hasLabelAccess(adminToken, "any-label"),
        "Admin should have access to any label");
  }

  @Test
  void testTenantHeaders() {
    OAuthToken userToken = createToken("order-service-team", Arrays.asList("order-service-team"));

    // Test tenant headers for different services
    Map<String, String> lokiHeaders = labelStore.getTenantHeaders(userToken, "loki");
    assertNotNull(lokiHeaders, "Loki tenant headers should not be null");

    Map<String, String> thanosHeaders = labelStore.getTenantHeaders(userToken, "thanos");
    assertNotNull(thanosHeaders, "Thanos tenant headers should not be null");

    Map<String, String> tempoHeaders = labelStore.getTenantHeaders(userToken, "tempo");
    assertNotNull(tempoHeaders, "Tempo tenant headers should not be null");
  }

  private OAuthToken createToken(String username, java.util.List<String> groups) {
    OAuthToken token = new OAuthToken();
    token.setPreferredUsername(username);
    token.setGroups(groups);
    return token;
  }
}
