package com.evoila.janus.thanos.enforcement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.evoila.janus.base.BaseUnitTest;
import com.evoila.janus.common.enforcement.QueryEnforcementFlow;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import com.evoila.janus.common.labelstore.ServiceAwareLabelStore;
import com.evoila.janus.security.config.OAuthToken;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ThanosEnforcementTest extends BaseUnitTest {

  @Mock private QueryEnforcementFlow queryEnforcementFlow;

  private ServiceAwareLabelStore serviceAwareLabelStore;
  private OAuthToken testToken;

  @BeforeEach
  void setUp() throws java.io.IOException {
    super.setUpBase();

    // Create test token
    testToken = createToken("test-user", List.of("test-group"));

    // Set up label store with test configuration
    String configContent =
        """
            thanos:
              user-label-constraints:
                test-user:
                  labels:
                    - "job"
                    - "instance"
                    - "service"
                  job:
                    - "prometheus"
                    - "node-exporter"
                  service:
                    - "order-service"
                    - "stock-service"
                admin:
                  labels:
                    - "*"
                  service:
                    - "*"
                  namespace:
                    - "*"
                  job:
                    - "*"
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);

    // Create service-aware label store
    serviceAwareLabelStore = new ServiceAwareLabelStore(configMapLabelStore);

    // Set up QueryEnforcementFlow mock to delegate to real implementation
    lenient()
        .when(queryEnforcementFlow.enhanceQuery(any()))
        .thenAnswer(
            invocation -> {
              QueryContext context = invocation.getArgument(0);
              return QueryEnhancementProcessor.enhanceQuery(context);
            });
  }

  @Test
  void testBasicQueryEnforcement() {
    // Get user constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(testToken, "thanos");

    // Test basic query
    String originalQuery = "up{job=\"prometheus\"}";

    QueryContext context =
        QueryContext.builder()
            .originalQuery(originalQuery)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enforcedQuery = result.getEnhancedQuery();
    assertNotNull(enforcedQuery, "Enforced query should not be null");
    assertTrue(enforcedQuery.contains("job=\"prometheus\""), "Should contain job constraint");
    assertTrue(
        enforcedQuery.contains("service=~\"stock-service|order-service\"")
            || enforcedQuery.contains("service=~\"order-service|stock-service\""),
        "Should contain service constraint");
  }

  @Test
  void testComplexQueryEnforcement() {
    // Get user constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(testToken, "thanos");

    // Test complex query with multiple labels
    String originalQuery = "rate(http_requests_total[5m])";

    QueryContext context =
        QueryContext.builder()
            .originalQuery(originalQuery)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enforcedQuery = result.getEnhancedQuery();
    assertNotNull(enforcedQuery, "Enforced query should not be null");
    assertTrue(
        enforcedQuery.contains("job=~\"node-exporter|prometheus\""),
        "Should contain job constraint");
    assertTrue(
        enforcedQuery.contains("service=~\"stock-service|order-service\"")
            || enforcedQuery.contains("service=~\"order-service|stock-service\""),
        "Should contain service constraint");
  }

  @Test
  void testQueryWithExistingConstraints() {
    // Get user constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(testToken, "thanos");

    // Test query that already has some constraints
    String originalQuery = "up{job=\"prometheus\", instance=\"localhost:9090\"}";

    QueryContext context =
        QueryContext.builder()
            .originalQuery(originalQuery)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enforcedQuery = result.getEnhancedQuery();
    assertNotNull(enforcedQuery, "Enforced query should not be null");
    assertTrue(
        enforcedQuery.contains("job=\"prometheus\""), "Should preserve existing job constraint");
    assertTrue(
        enforcedQuery.contains("instance=\"localhost:9090\""),
        "Should preserve existing instance constraint");
    assertTrue(
        enforcedQuery.contains("service=~\"stock-service|order-service\"")
            || enforcedQuery.contains("service=~\"order-service|stock-service\""),
        "Should contain service constraint");
  }

  @Test
  void testServiceAwareLabelStore() {
    // Test service-aware label store functionality
    Map<String, Set<String>> constraints =
        serviceAwareLabelStore.getServiceLabelConstraints(testToken, "thanos");

    assertNotNull(constraints, "Constraints should not be null");
    assertTrue(constraints.containsKey("job"), "Should contain job constraints");
    assertTrue(constraints.containsKey("service"), "Should contain service constraints");

    Set<String> jobConstraints = constraints.get("job");
    assertTrue(jobConstraints.contains("prometheus"), "Should contain prometheus job");
    assertTrue(jobConstraints.contains("node-exporter"), "Should contain node-exporter job");
  }

  @Test
  void testLabelAccess() {
    // Test label access validation
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(testToken, "thanos");

    assertTrue(constraints.containsKey("job"), "User should have access to job label");
    assertTrue(constraints.containsKey("service"), "User should have access to service label");
    assertFalse(
        constraints.containsKey("namespace"), "User should not have access to namespace label");
  }

  @Test
  void testAdminAccess() {
    // Test admin token access
    OAuthToken adminToken = createToken("admin", List.of("admin-group"));
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(adminToken, "thanos");

    // Admin should have broader access
    assertNotNull(constraints, "Admin constraints should not be null");
  }

  @Test
  void testWildcardPatternsWithoutUnnecessaryConstraints() {
    // Get user constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(testToken, "thanos");

    // Test query with wildcard patterns that should NOT generate !="" constraints
    // This reproduces the issue from the logs where service=".+" and pod=".+"
    // were incorrectly generating service!="" and pod!="" constraints
    String originalQuery =
        "max(http_server_requests_seconds_max{namespace=\"(demo|observability)\",service=\".+\",pod=\".+\",status!~\"5..\"})";

    QueryContext context =
        QueryContext.builder()
            .originalQuery(originalQuery)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enforcedQuery = result.getEnhancedQuery();
    assertNotNull(enforcedQuery, "Enforced query should not be null");

    // Verify that the query is properly enhanced with user constraints
    // The system should apply the user's allowed values for service and job
    assertTrue(
        enforcedQuery.contains("service=~\"stock-service|order-service\""),
        "Should contain service constraint with user's allowed values");
    assertTrue(
        enforcedQuery.contains("job=~\"node-exporter|prometheus\""),
        "Should contain job constraint with user's allowed values");
    assertTrue(enforcedQuery.contains("status!~\"5..\""), "Should contain status constraint");

    // CRITICAL: Verify that NO unnecessary !="" constraints are added
    // The wildcard patterns .+ already ensure non-empty values, so we shouldn't add service!="" or
    // pod!=""
    assertFalse(
        enforcedQuery.contains("service!=\"\""),
        "Should NOT contain unnecessary service!=\"\" constraint");
    assertFalse(
        enforcedQuery.contains("pod!=\"\""), "Should NOT contain unnecessary pod!=\"\" constraint");

    // Verify that namespace constraint is properly applied
    assertTrue(
        enforcedQuery.contains("namespace=~\"(demo|observability)\""),
        "Should contain namespace constraint");
  }

  @Test
  void testPreserveExistingNotEmptyConstraints() {
    // Get user constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(testToken, "thanos");

    // Test query where user explicitly provides !="" constraints
    // These should be preserved, not removed
    String originalQuery = "up{service=\"stock-service\",pod!=\"\",job=\"prometheus\"}";

    QueryContext context =
        QueryContext.builder()
            .originalQuery(originalQuery)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enforcedQuery = result.getEnhancedQuery();
    assertNotNull(enforcedQuery, "Enforced query should not be null");

    // Verify that existing !="" constraint is preserved
    assertTrue(
        enforcedQuery.contains("pod!=\"\""),
        "Should preserve existing pod!=\"\" constraint from user");

    // Verify that service is preserved since it's an allowed value
    assertTrue(
        enforcedQuery.contains("service=\"stock-service\""),
        "Should preserve service since it's an allowed value");

    // Verify that job constraint is properly applied
    assertTrue(enforcedQuery.contains("job=\"prometheus\""), "Should contain job constraint");
  }

  @Test
  void testWildcardPatternConversion() {
    // Get user constraints
    Map<String, Set<String>> constraints =
        configMapLabelStore.getServiceLabelConstraints(testToken, "thanos");

    // Test query with various wildcard patterns that should be converted to regex operators
    String originalQuery = "up{service=\"*\",pod=\".*\",namespace=\".+\"}";

    QueryContext context =
        QueryContext.builder()
            .originalQuery(originalQuery)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enforcedQuery = result.getEnhancedQuery();
    assertNotNull(enforcedQuery, "Enforced query should not be null");

    // Verify that wildcard patterns are converted to regex operators
    // service="*" should be enhanced with user constraints
    assertTrue(
        enforcedQuery.contains("service=~\"stock-service|order-service\""),
        "Should enhance service=\"*\" with user constraints");

    // pod=".*" should become pod=~".*"
    assertTrue(enforcedQuery.contains("pod=~\".*\""), "Should convert pod=\".*\" to pod=~\".*\"");

    // namespace=".+" should become namespace=~".*" (converted to default wildcard)
    assertTrue(
        enforcedQuery.contains("namespace=~\".*\""),
        "Should convert namespace=\".+\" to namespace=~\".*\"");
  }

  private OAuthToken createToken(String username, List<String> groups) {
    OAuthToken token = new OAuthToken();
    token.setPreferredUsername(username);
    token.setGroups(groups);
    return token;
  }
}
