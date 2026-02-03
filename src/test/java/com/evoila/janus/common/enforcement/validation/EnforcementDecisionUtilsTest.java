package com.evoila.janus.common.enforcement.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.evoila.janus.common.config.ProxyConfig;
import com.evoila.janus.common.enforcement.utils.EnforcementDecisionUtils;
import com.evoila.janus.security.config.OAuthToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;

@ExtendWith(MockitoExtension.class)
class EnforcementDecisionUtilsTest {

  @Mock private ProxyConfig mockConfig;

  @Mock private OAuthToken mockToken;

  private EnforcementDecisionUtils enforcementDecisionUtils;

  @BeforeEach
  void setUp() {
    enforcementDecisionUtils = new EnforcementDecisionUtils();
  }

  @Test
  void shouldSkipQueryEnforcement_WhenAdminUser_ShouldReturnTrue() {
    // Given
    String endpointPath = "/api/v1/query";
    String rawQuery = "query=up";
    HttpMethod method = HttpMethod.GET;

    when(mockConfig.getLabelStore()).thenReturn(new MockLabelStore(true));

    // When
    boolean result =
        enforcementDecisionUtils.shouldSkipQueryEnforcement(
            endpointPath, rawQuery, method, mockToken, mockConfig);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void shouldSkipQueryEnforcement_WhenConfigSkipsEnforcement_ShouldReturnTrue() {
    // Given
    String endpointPath = "/api/v1/query";
    String rawQuery = "query=up";
    HttpMethod method = HttpMethod.GET;

    when(mockConfig.getLabelStore()).thenReturn(new MockLabelStore(false));
    when(mockConfig.shouldSkipEnforcement(endpointPath, rawQuery)).thenReturn(true);

    // When
    boolean result =
        enforcementDecisionUtils.shouldSkipQueryEnforcement(
            endpointPath, rawQuery, method, mockToken, mockConfig);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void shouldSkipQueryEnforcement_WhenPostRequest_ShouldReturnTrue() {
    // Given
    String endpointPath = "/api/v1/query";
    String rawQuery = "query=up";
    HttpMethod method = HttpMethod.POST;

    when(mockConfig.getLabelStore()).thenReturn(new MockLabelStore(false));
    when(mockConfig.shouldSkipEnforcement(endpointPath, rawQuery)).thenReturn(false);

    // When
    boolean result =
        enforcementDecisionUtils.shouldSkipQueryEnforcement(
            endpointPath, rawQuery, method, mockToken, mockConfig);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void shouldSkipQueryEnforcement_WhenNormalGetRequest_ShouldReturnFalse() {
    // Given
    String endpointPath = "/api/v1/query";
    String rawQuery = "query=up";
    HttpMethod method = HttpMethod.GET;

    when(mockConfig.getLabelStore()).thenReturn(new MockLabelStore(false));
    when(mockConfig.shouldSkipEnforcement(endpointPath, rawQuery)).thenReturn(false);

    // When
    boolean result =
        enforcementDecisionUtils.shouldSkipQueryEnforcement(
            endpointPath, rawQuery, method, mockToken, mockConfig);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void shouldSkipFormDataEnforcement_WhenAdminUser_ShouldReturnTrue() {
    // Given
    String endpointPath = "/api/v1/query";

    when(mockConfig.getLabelStore()).thenReturn(new MockLabelStore(true));

    // When
    boolean result =
        enforcementDecisionUtils.shouldSkipFormDataEnforcement(endpointPath, mockToken, mockConfig);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void shouldSkipFormDataEnforcement_WhenConfigSkipsEnforcement_ShouldReturnTrue() {
    // Given
    String endpointPath = "/api/v1/query";

    when(mockConfig.getLabelStore()).thenReturn(new MockLabelStore(false));
    when(mockConfig.shouldSkipEnforcement(endpointPath, null)).thenReturn(true);

    // When
    boolean result =
        enforcementDecisionUtils.shouldSkipFormDataEnforcement(endpointPath, mockToken, mockConfig);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void shouldSkipFormDataEnforcement_WhenNormalRequest_ShouldReturnFalse() {
    // Given
    String endpointPath = "/api/v1/query";

    when(mockConfig.getLabelStore()).thenReturn(new MockLabelStore(false));
    when(mockConfig.shouldSkipEnforcement(endpointPath, null)).thenReturn(false);

    // When
    boolean result =
        enforcementDecisionUtils.shouldSkipFormDataEnforcement(endpointPath, mockToken, mockConfig);

    // Then
    assertThat(result).isFalse();
  }

  // Mock implementation for testing
  private static class MockLabelStore implements com.evoila.janus.common.labelstore.LabelStore {
    private final boolean hasClusterWideAccess;

    public MockLabelStore(boolean hasClusterWideAccess) {
      this.hasClusterWideAccess = hasClusterWideAccess;
    }

    @Override
    public boolean hasClusterWideAccess(OAuthToken token) {
      return hasClusterWideAccess;
    }

    @Override
    public boolean hasLabelAccess(OAuthToken token, String labelName) {
      return hasClusterWideAccess || "namespace".equals(labelName);
    }

    @Override
    public java.util.Set<String> getAllowedLabels(OAuthToken token) {
      if (hasClusterWideAccess) {
        return java.util.Set.of("*");
      }
      return java.util.Set.of("namespace", "service");
    }

    @Override
    public java.util.Map<String, java.util.Set<String>> getServiceLabelConstraints(
        OAuthToken token, String serviceName) {
      java.util.Map<String, java.util.Set<String>> constraints = new java.util.HashMap<>();
      constraints.put("namespace", java.util.Set.of("observability", "demo"));
      constraints.put("service", java.util.Set.of("*"));
      return constraints;
    }

    @Override
    public java.util.Map<String, String> getTenantHeaders(OAuthToken token, String serviceName) {
      return new java.util.HashMap<>();
    }

    @Override
    public java.util.Set<String> getExcludedLabels(OAuthToken token) {
      return java.util.Set.of(); // No exclusions for testing
    }
  }
}
