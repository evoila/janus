package com.evoila.janus.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.evoila.janus.common.config.ProxyConfig;
import com.evoila.janus.common.enforcement.QueryEnforcementFlow;
import com.evoila.janus.common.enforcement.core.RequestContext;
import com.evoila.janus.common.enforcement.labels.LabelValuesEnhancer;
import com.evoila.janus.common.enforcement.labels.LabelValuesEnhancerRegistry;
import com.evoila.janus.common.enforcement.utils.EndpointPathUtils;
import com.evoila.janus.common.labelstore.LabelStore;
import com.evoila.janus.security.config.OAuthToken;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestProcessingServiceTest {

  @Mock private RequestUrlBuilder requestUrlBuilder;
  @Mock private QueryEnforcementFlow queryEnforcementFlow;
  @Mock private ForwardRequestService forwardRequestService;
  @Mock private ErrorHandler errorHandler;
  @Mock private EndpointPathUtils endpointPathUtils;
  @Mock private LabelValuesEnhancerRegistry labelValuesEnhancerRegistry;
  @Mock private LabelValuesEnhancer labelValuesEnhancer;
  @Mock private ProxyConfig config;
  @Mock private LabelStore labelStore;

  private RequestProcessingService service;
  private OAuthToken token;

  private static final String ENDPOINT_PATH = "api/v1/query_range";
  private static final String ENFORCEMENT_PARAM = "query";
  private static final String SERVICE_THANOS = "thanos";
  private static final Map<String, Set<String>> LABEL_CONSTRAINTS =
      Map.of("namespace", Set.of("test-ns"));
  // For label values endpoint tests: includes "labels" key to specify which labels are accessible
  private static final Map<String, Set<String>> LABEL_VALUES_CONSTRAINTS =
      Map.of("namespace", Set.of("test-ns"), "labels", Set.of("namespace", "service.name"));
  private static final ResponseEntity<String> OK_RESPONSE =
      ResponseEntity.ok("{\"status\":\"success\"}");

  @BeforeEach
  void setUp() {
    service =
        new RequestProcessingService(
            requestUrlBuilder,
            queryEnforcementFlow,
            forwardRequestService,
            errorHandler,
            endpointPathUtils,
            labelValuesEnhancerRegistry);

    token = new OAuthToken();
    token.setPreferredUsername("test-user");
    token.setGroups(List.of("test-group"));
  }

  // ========================================================================
  // Helpers
  // ========================================================================

  private void setupParseMetadata(
      String endpointPath, String enforcementParam, String serviceName) {
    when(requestUrlBuilder.processRequestPath(anyString(), any(ProxyConfig.class)))
        .thenReturn(endpointPath);
    when(config.getEnforcementParameter(endpointPath)).thenReturn(enforcementParam);
    when(config.getServiceName()).thenReturn(serviceName);
  }

  private void setupNotSpecialEndpoint(String endpointPath) {
    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(false);
    when(endpointPathUtils.isLabelsEndpoint(endpointPath)).thenReturn(false);
  }

  private void setupLabelConstraints(Map<String, Set<String>> constraints) {
    when(config.getLabelStore()).thenReturn(labelStore);
    when(labelStore.getServiceLabelConstraints(any(OAuthToken.class), anyString()))
        .thenReturn(constraints);
  }

  private void setupExcludedLabels(Set<String> excluded) {
    when(config.getLabelStore()).thenReturn(labelStore);
    when(labelStore.getExcludedLabels(any(OAuthToken.class))).thenReturn(excluded);
  }

  private void setupBuildAndForward() {
    setupBuildAndForward(OK_RESPONSE);
  }

  private void setupBuildAndForward(ResponseEntity<String> response) {
    when(requestUrlBuilder.buildTargetUrl(anyString(), any(), any(ProxyConfig.class)))
        .thenAnswer(
            inv -> {
              String endpointPath = inv.getArgument(0);
              return "http://backend:9090/" + endpointPath;
            });
    when(forwardRequestService.forwardToBackend(any(RequestContext.class)))
        .thenAnswer(
            inv -> {
              RequestContext ctx = inv.getArgument(0);
              ctx.setResponse(response);
              return Mono.just(ctx);
            });
  }

  private void setupBinaryForward(ResponseEntity<byte[]> response) {
    when(requestUrlBuilder.buildTargetUrl(anyString(), any(), any(ProxyConfig.class)))
        .thenAnswer(
            inv -> {
              String endpointPath = inv.getArgument(0);
              return "http://backend:9090/" + endpointPath;
            });
    when(forwardRequestService.forwardBinaryToBackend(any(RequestContext.class)))
        .thenAnswer(
            inv -> {
              RequestContext ctx = inv.getArgument(0);
              ctx.setResponse(response);
              return Mono.just(ctx);
            });
  }

  private Mono<MultiValueMap<String, String>> emptyFormData() {
    return Mono.just(new LinkedMultiValueMap<>());
  }

  // ========================================================================
  // 1. GET Request - Standard Query Enforcement (happy path)
  // ========================================================================

  @Test
  void processRequest_getWithQueryParam_shouldEnforceAndReturn200() {
    // Given
    String rawQuery = "query=up&start=123&end=456";
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupNotSpecialEndpoint(ENDPOINT_PATH);
    setupLabelConstraints(LABEL_CONSTRAINTS);
    setupBuildAndForward();

    when(queryEnforcementFlow.enhanceQueryWithExplicitFlow("up", SERVICE_THANOS, LABEL_CONSTRAINTS))
        .thenReturn("up{namespace=\"test-ns\"}");

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range", rawQuery, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(response.getBody()).isEqualTo("{\"status\":\"success\"}");
              return true;
            })
        .verifyComplete();

    verify(queryEnforcementFlow)
        .enhanceQueryWithExplicitFlow("up", SERVICE_THANOS, LABEL_CONSTRAINTS);
  }

  // ========================================================================
  // 2. GET Request - No Query Parameter Value (passthrough)
  // ========================================================================

  @Test
  void processRequest_getWithoutQueryParam_shouldPassthrough() {
    // Given
    String rawQuery = "start=123&end=456";
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupNotSpecialEndpoint(ENDPOINT_PATH);
    setupBuildAndForward();

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range", rawQuery, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(queryEnforcementFlow, never())
        .enhanceQueryWithExplicitFlow(anyString(), anyString(), anyMap());
  }

  // ========================================================================
  // 3. GET Request - Null rawQuery
  // ========================================================================

  @Test
  void processRequest_getWithNullRawQuery_shouldComplete() {
    // Given
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupNotSpecialEndpoint(ENDPOINT_PATH);
    setupBuildAndForward();

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range", null, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(queryEnforcementFlow, never())
        .enhanceQueryWithExplicitFlow(anyString(), anyString(), anyMap());
  }

  // ========================================================================
  // 4. POST Request - Form Data Enforcement
  // ========================================================================

  @Test
  void processRequest_postWithQueryInFormData_shouldEnforceFormData() {
    // Given
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("query", "up");

    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupLabelConstraints(LABEL_CONSTRAINTS);
    setupBuildAndForward();

    when(queryEnforcementFlow.enhanceQueryWithExplicitFlow("up", SERVICE_THANOS, LABEL_CONSTRAINTS))
        .thenReturn("up{namespace=\"test-ns\"}");

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range",
            null,
            HttpMethod.POST,
            token,
            Mono.just(formData),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(queryEnforcementFlow)
        .enhanceQueryWithExplicitFlow("up", SERVICE_THANOS, LABEL_CONSTRAINTS);
  }

  // ========================================================================
  // 5. POST Request - No Query in Form Data (passthrough)
  // ========================================================================

  @Test
  void processRequest_postWithoutQueryInFormData_shouldPassthrough() {
    // Given
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("start", "123");

    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupBuildAndForward();

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range",
            null,
            HttpMethod.POST,
            token,
            Mono.just(formData),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(queryEnforcementFlow, never())
        .enhanceQueryWithExplicitFlow(anyString(), anyString(), anyMap());
  }

  // ========================================================================
  // 6. POST Request - Empty Form Data
  // ========================================================================

  @Test
  void processRequest_postWithEmptyFormDataMono_shouldComplete() {
    // Given
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupBuildAndForward();

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range", null, HttpMethod.POST, token, Mono.empty(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();
  }

  // ========================================================================
  // 7. GET Request - Label Values Endpoint (Thanos)
  // ========================================================================

  @Test
  void processRequest_getLabelValuesThanos_shouldDelegateToThanosEnhancer() {
    // Given
    String endpointPath = "api/v1/label/namespace/values";
    String rawQuery = "start=123&end=456";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupLabelConstraints(LABEL_VALUES_CONSTRAINTS);
    setupExcludedLabels(Set.of());
    setupBuildAndForward();

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(true);
    when(endpointPathUtils.extractLabelNameFromPath(endpointPath)).thenReturn("namespace");

    when(labelValuesEnhancerRegistry.getEnhancer(SERVICE_THANOS))
        .thenReturn(Optional.of(labelValuesEnhancer));
    when(labelValuesEnhancer.enhanceLabelValuesQuery(
            any(LabelValuesEnhancer.LabelValuesContext.class)))
        .thenReturn("enhanced-thanos-query");

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/label/namespace/values",
            rawQuery,
            HttpMethod.GET,
            token,
            emptyFormData(),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(labelValuesEnhancer)
        .enhanceLabelValuesQuery(any(LabelValuesEnhancer.LabelValuesContext.class));
  }

  // ========================================================================
  // 9. GET Request - Label Values Endpoint (Loki)
  // ========================================================================

  @Test
  void processRequest_getLabelValuesLoki_shouldDelegateToLokiEnhancer() {
    // Given
    String endpointPath = "api/v1/label/namespace/values";
    String rawQuery = "start=123&end=456";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, "loki");
    setupLabelConstraints(LABEL_VALUES_CONSTRAINTS);
    setupExcludedLabels(Set.of());
    setupBuildAndForward();

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(true);
    when(endpointPathUtils.extractLabelNameFromPath(endpointPath)).thenReturn("namespace");

    when(labelValuesEnhancerRegistry.getEnhancer("loki"))
        .thenReturn(Optional.of(labelValuesEnhancer));
    when(labelValuesEnhancer.enhanceLabelValuesQuery(
            any(LabelValuesEnhancer.LabelValuesContext.class)))
        .thenReturn("enhanced-loki-query");

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/loki/api/v1/label/namespace/values",
            rawQuery,
            HttpMethod.GET,
            token,
            emptyFormData(),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(labelValuesEnhancer)
        .enhanceLabelValuesQuery(any(LabelValuesEnhancer.LabelValuesContext.class));
  }

  // ========================================================================
  // 10. GET Request - Label Values Endpoint (Tempo)
  // ========================================================================

  @Test
  void processRequest_getLabelValuesTempo_shouldDelegateToTempoEnhancer() {
    // Given
    String endpointPath = "api/v2/search/tag/service.name/values";
    String rawQuery = "start=123&end=456";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, "tempo");
    setupLabelConstraints(LABEL_VALUES_CONSTRAINTS);
    setupExcludedLabels(Set.of());
    setupBuildAndForward();

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(true);
    when(endpointPathUtils.extractLabelNameFromPath(endpointPath)).thenReturn("service.name");

    when(labelValuesEnhancerRegistry.getEnhancer("tempo"))
        .thenReturn(Optional.of(labelValuesEnhancer));
    when(labelValuesEnhancer.enhanceLabelValuesQuery(
            any(LabelValuesEnhancer.LabelValuesContext.class)))
        .thenReturn("enhanced-tempo-query");

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/tempo/api/v2/search/tag/service.name/values",
            rawQuery,
            HttpMethod.GET,
            token,
            emptyFormData(),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(labelValuesEnhancer)
        .enhanceLabelValuesQuery(any(LabelValuesEnhancer.LabelValuesContext.class));
  }

  // ========================================================================
  // 11. GET Request - Label Values Endpoint with __name__ (skip)
  // ========================================================================

  @Test
  void processRequest_getLabelValuesNameLabel_shouldSkipEnhancement() {
    // Given
    String endpointPath = "api/v1/label/__name__/values";
    String rawQuery = "start=123&end=456";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupBuildAndForward();

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(true);
    when(endpointPathUtils.extractLabelNameFromPath(endpointPath)).thenReturn("__name__");

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/label/__name__/values",
            rawQuery,
            HttpMethod.GET,
            token,
            emptyFormData(),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(labelValuesEnhancerRegistry, never()).getEnhancer(anyString());
  }

  // ========================================================================
  // 12. GET Request - Label Values Endpoint (access denied)
  // ========================================================================

  @Test
  void processRequest_getLabelValuesAccessDenied_shouldReturn403() {
    // Given
    String endpointPath = "api/v1/label/secret_label/values";
    String rawQuery = "start=123&end=456";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, SERVICE_THANOS);

    // "labels" key restricts which labels are accessible
    Map<String, Set<String>> constraints =
        Map.of("namespace", Set.of("test-ns"), "labels", Set.of("namespace"));
    setupLabelConstraints(constraints);
    setupExcludedLabels(Set.of());

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(true);
    when(endpointPathUtils.extractLabelNameFromPath(endpointPath)).thenReturn("secret_label");

    when(errorHandler.handleSecurityViolation(any(SecurityException.class), isNull()))
        .thenReturn(Mono.just(ResponseEntity.status(403).body("Forbidden")));

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/label/secret_label/values",
            rawQuery,
            HttpMethod.GET,
            token,
            emptyFormData(),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              return true;
            })
        .verifyComplete();

    verify(errorHandler).handleSecurityViolation(any(SecurityException.class), isNull());
  }

  // ========================================================================
  // 13. GET Request - Labels Endpoint (with constraints)
  // ========================================================================

  @Test
  void processRequest_getLabelsEndpointWithConstraints_shouldAppendConstraintQuery() {
    // Given
    String endpointPath = "api/v1/labels";
    String rawQuery = "start=123&end=456";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupLabelConstraints(LABEL_CONSTRAINTS);
    setupBuildAndForward();

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(false);
    when(endpointPathUtils.isLabelsEndpoint(endpointPath)).thenReturn(true);

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/labels", rawQuery, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    // Standard query enforcement should NOT be called for labels endpoints
    verify(queryEnforcementFlow, never())
        .enhanceQueryWithExplicitFlow(anyString(), anyString(), anyMap());
  }

  // ========================================================================
  // 14. GET Request - Labels Endpoint (wildcard access)
  // ========================================================================

  @Test
  void processRequest_getLabelsEndpointWildcard_shouldReturnRawQueryAsIs() {
    // Given
    String endpointPath = "api/v1/labels";
    String rawQuery = "start=123&end=456";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, SERVICE_THANOS);
    // Empty constraints produce empty constraint query -> wildcard passthrough
    setupLabelConstraints(Map.of());
    setupBuildAndForward();

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(false);
    when(endpointPathUtils.isLabelsEndpoint(endpointPath)).thenReturn(true);

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/labels", rawQuery, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();
  }

  // ========================================================================
  // 15. GET Request - Labels Endpoint (null rawQuery)
  // ========================================================================

  @Test
  void processRequest_getLabelsEndpointNullRawQuery_shouldCreateQueryWithConstraint() {
    // Given
    String endpointPath = "api/v1/labels";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupLabelConstraints(LABEL_CONSTRAINTS);
    setupBuildAndForward();

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(false);
    when(endpointPathUtils.isLabelsEndpoint(endpointPath)).thenReturn(true);

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/labels", null, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();
  }

  // ========================================================================
  // 16. SecurityException During Enforcement -> 403
  // ========================================================================

  @Test
  void processRequest_securityExceptionDuringEnforcement_shouldReturn403() {
    // Given
    String rawQuery = "query=up";
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupNotSpecialEndpoint(ENDPOINT_PATH);
    setupLabelConstraints(LABEL_CONSTRAINTS);

    when(queryEnforcementFlow.enhanceQueryWithExplicitFlow(anyString(), anyString(), anyMap()))
        .thenThrow(new SecurityException("Access denied"));

    when(errorHandler.handleSecurityViolation(any(SecurityException.class), isNull()))
        .thenReturn(Mono.just(ResponseEntity.status(403).body("Forbidden")));

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range", rawQuery, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              return true;
            })
        .verifyComplete();

    verify(errorHandler).handleSecurityViolation(any(SecurityException.class), isNull());
  }

  // ========================================================================
  // 17. General Exception During Enforcement -> 500
  // ========================================================================

  @Test
  void processRequest_runtimeExceptionDuringEnforcement_shouldReturn500() {
    // Given
    String rawQuery = "query=up";
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);
    setupNotSpecialEndpoint(ENDPOINT_PATH);
    setupLabelConstraints(LABEL_CONSTRAINTS);

    when(queryEnforcementFlow.enhanceQueryWithExplicitFlow(anyString(), anyString(), anyMap()))
        .thenThrow(new RuntimeException("Something went wrong"));

    when(errorHandler.handleGeneralError(any(Exception.class)))
        .thenReturn(Mono.just(ResponseEntity.status(500).body("Internal Server Error")));

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range", rawQuery, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              return true;
            })
        .verifyComplete();

    verify(errorHandler).handleGeneralError(any(Exception.class));
  }

  // ========================================================================
  // 18. Binary Request - Happy Path (skips enforcement)
  // ========================================================================

  @Test
  void processBinaryRequest_happyPath_shouldSkipEnforcement() {
    // Given
    String rawQuery = "start=123&end=456";
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);

    ResponseEntity<byte[]> binaryResponse = ResponseEntity.ok("binary-data".getBytes());
    setupBinaryForward(binaryResponse);

    // When
    Mono<ResponseEntity<byte[]>> result =
        service.processBinaryRequest(
            "/tempo/api/traces", rawQuery, HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(response.getBody()).isEqualTo("binary-data".getBytes());
              return true;
            })
        .verifyComplete();

    verify(queryEnforcementFlow, never())
        .enhanceQueryWithExplicitFlow(anyString(), anyString(), anyMap());
  }

  // ========================================================================
  // 19. Binary Request - SecurityException -> 403 binary
  // ========================================================================

  @Test
  void processBinaryRequest_securityException_shouldReturn403Binary() {
    // Given
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);

    when(requestUrlBuilder.buildTargetUrl(anyString(), any(), any(ProxyConfig.class)))
        .thenThrow(new SecurityException("Unauthorized"));

    when(errorHandler.handleSecurityViolationBinary(any(SecurityException.class), isNull()))
        .thenReturn(Mono.just(ResponseEntity.status(403).body("Forbidden".getBytes())));

    // When
    Mono<ResponseEntity<byte[]>> result =
        service.processBinaryRequest(
            "/tempo/api/traces", "start=123", HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              return true;
            })
        .verifyComplete();

    verify(errorHandler).handleSecurityViolationBinary(any(SecurityException.class), isNull());
  }

  // ========================================================================
  // 20. Binary Request - General Error -> 500 binary
  // ========================================================================

  @Test
  void processBinaryRequest_runtimeException_shouldReturn500Binary() {
    // Given
    setupParseMetadata(ENDPOINT_PATH, ENFORCEMENT_PARAM, SERVICE_THANOS);

    when(requestUrlBuilder.buildTargetUrl(anyString(), any(), any(ProxyConfig.class)))
        .thenThrow(new RuntimeException("Backend unavailable"));

    when(errorHandler.handleGeneralErrorBinary(any(Exception.class)))
        .thenReturn(Mono.just(ResponseEntity.status(500).body("Error".getBytes())));

    // When
    Mono<ResponseEntity<byte[]>> result =
        service.processBinaryRequest(
            "/tempo/api/traces", "start=123", HttpMethod.GET, token, emptyFormData(), config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              return true;
            })
        .verifyComplete();

    verify(errorHandler).handleGeneralErrorBinary(any(Exception.class));
  }

  // ========================================================================
  // 21. GET Request - Label Values Endpoint (default/unknown service)
  // ========================================================================

  @Test
  void processRequest_getLabelValuesUnknownService_shouldReturnRawQuery() {
    // Given
    String endpointPath = "api/v1/label/namespace/values";
    String rawQuery = "start=123&end=456";

    setupParseMetadata(endpointPath, ENFORCEMENT_PARAM, "unknown");
    // "labels" key grants access; unknown service means no enhancer found
    setupLabelConstraints(LABEL_VALUES_CONSTRAINTS);
    setupExcludedLabels(Set.of());
    setupBuildAndForward();

    when(endpointPathUtils.isLabelValuesEndpoint(endpointPath)).thenReturn(true);
    when(endpointPathUtils.extractLabelNameFromPath(endpointPath)).thenReturn("namespace");

    when(labelValuesEnhancerRegistry.getEnhancer("unknown")).thenReturn(Optional.empty());

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/unknown/api/v1/label/namespace/values",
            rawQuery,
            HttpMethod.GET,
            token,
            emptyFormData(),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
              return true;
            })
        .verifyComplete();

    verify(labelValuesEnhancer, never())
        .enhanceLabelValuesQuery(any(LabelValuesEnhancer.LabelValuesContext.class));
  }

  // ========================================================================
  // 22. Error in parseRequestMetadata propagates
  // ========================================================================

  @Test
  void processRequest_errorInParseMetadata_shouldPropagateToErrorHandler() {
    // Given
    when(requestUrlBuilder.processRequestPath(anyString(), any(ProxyConfig.class)))
        .thenReturn(ENDPOINT_PATH);
    when(config.getEnforcementParameter(ENDPOINT_PATH))
        .thenThrow(new RuntimeException("Config error"));

    when(errorHandler.handleGeneralError(any(Exception.class)))
        .thenReturn(Mono.just(ResponseEntity.status(500).body("Internal Server Error")));

    // When
    Mono<ResponseEntity<String>> result =
        service.processRequest(
            "/thanos/api/v1/query_range",
            "query=up",
            HttpMethod.GET,
            token,
            emptyFormData(),
            config);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              return true;
            })
        .verifyComplete();

    verify(errorHandler).handleGeneralError(any(Exception.class));
  }
}
