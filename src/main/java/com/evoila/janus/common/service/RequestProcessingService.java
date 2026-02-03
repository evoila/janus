package com.evoila.janus.common.service;

import com.evoila.janus.common.config.ProxyConfig;
import com.evoila.janus.common.enforcement.QueryEnforcementFlow;
import com.evoila.janus.common.enforcement.core.RequestContext;
import com.evoila.janus.common.enforcement.label.LabelAccessValidator;
import com.evoila.janus.common.enforcement.labels.LabelValuesEnhancer.LabelValuesContext;
import com.evoila.janus.common.enforcement.labels.LabelValuesEnhancerRegistry;
import com.evoila.janus.common.enforcement.query.ParsedQueryParameters;
import com.evoila.janus.common.enforcement.query.QueryEnhancementBuilder;
import com.evoila.janus.common.enforcement.query.QueryParameterParser;
import com.evoila.janus.common.enforcement.utils.EndpointPathUtils;
import com.evoila.janus.security.config.OAuthToken;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

/**
 * Core service for processing and forwarding requests to backend services.
 *
 * <p>This service orchestrates the complete request processing pipeline: - Parses request metadata
 * (path, query, form data) - Routes enforcement based on HTTP method and endpoint - Builds target
 * URLs with enforced queries - Forwards requests to backend services - Handles errors and provides
 * comprehensive logging
 *
 * <p>The service supports both text and binary requests, with specialized handling for different
 * observability services (Thanos, Loki, Tempo).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestProcessingService {

  private final RequestUrlBuilder requestUrlBuilder;
  private final QueryEnforcementFlow queryEnforcementFlow;
  private final ForwardRequestService forwardRequestService;
  private final ErrorHandler errorHandler;

  // Special endpoint handlers
  private final EndpointPathUtils endpointPathUtils;
  private final LabelValuesEnhancerRegistry labelValuesEnhancerRegistry;

  /**
   * Processes a text request through the full enforcement pipeline.
   *
   * @param requestPath The request path (e.g., "/api/v1/query_range")
   * @param rawQuery The raw query string (e.g., "query=up&start=123&end=456")
   * @param method The HTTP method (GET, POST)
   * @param token The OAuth token for authentication and authorization
   * @param formDataMono Optional form data for POST requests
   * @param config The proxy configuration for the target service
   * @return Mono containing the response from the backend service
   */
  public Mono<ResponseEntity<String>> processRequest(
      String requestPath,
      String rawQuery,
      HttpMethod method,
      OAuthToken token,
      Mono<MultiValueMap<String, String>> formDataMono,
      ProxyConfig config) {

    log.debug("RequestProcessingService: Processing {} request to {}", method, requestPath);

    RequestContext context =
        RequestContext.fromRequest(requestPath, rawQuery, method, token, formDataMono, config);

    return executePipeline(
        context, this::routeEnforcementByMethod, this::forwardToBackend, this::handleError);
  }

  /**
   * Processes a binary request, skipping query enforcement.
   *
   * @param requestPath The request path (e.g., "/api/v1/traces")
   * @param rawQuery The raw query string
   * @param method The HTTP method (typically GET for binary requests)
   * @param token The OAuth token for authentication and authorization
   * @param formDataMono Optional form data
   * @param config The proxy configuration for the target service
   * @return Mono containing the binary response from the backend service
   */
  public Mono<ResponseEntity<byte[]>> processBinaryRequest(
      String requestPath,
      String rawQuery,
      HttpMethod method,
      OAuthToken token,
      Mono<MultiValueMap<String, String>> formDataMono,
      ProxyConfig config) {

    log.debug("RequestProcessingService: Processing binary {} request to {}", method, requestPath);

    RequestContext context =
        RequestContext.fromRequest(requestPath, rawQuery, method, token, formDataMono, config);

    return executePipeline(
        context,
        this::skipEnforcementForBinaryRequest,
        this::forwardBinaryToBackend,
        this::handleBinaryError);
  }

  /**
   * Generic pipeline shared by text and binary request processing.
   *
   * <p>Steps: parse metadata → enforce → build URL → forward → extract response. Sync steps are
   * wrapped in Mono.defer() so exceptions become Mono.error(). Only enforcement (POST form data)
   * and forwarding (WebClient) are truly async.
   */
  private <T> Mono<ResponseEntity<T>> executePipeline(
      RequestContext context,
      Function<RequestContext, Mono<RequestContext>> enforcementStep,
      Function<RequestContext, Mono<RequestContext>> forwardStep,
      Function<Throwable, Mono<ResponseEntity<T>>> errorStep) {

    return Mono.defer(
            () -> {
              parseRequestMetadata(context);
              return enforcementStep.apply(context);
            })
        .map(
            ctx -> {
              buildTargetUrl(ctx);
              return ctx;
            })
        .flatMap(forwardStep)
        .map(this::<T>extractTypedResponse)
        .onErrorResume(errorStep);
  }

  /**
   * Parses request metadata and populates the context. Throws on error — Mono.defer() converts to
   * Mono.error().
   */
  private void parseRequestMetadata(RequestContext context) {
    String endpointPath =
        requestUrlBuilder.processRequestPath(context.getRequestPath(), context.getConfig());
    context.setEndpointPath(endpointPath);

    // Determine which parameter needs security enforcement based on the service type
    String enforcementParam = context.getConfig().getEnforcementParameter(endpointPath);
    context.setEnforcementParam(enforcementParam);

    // Parse query parameters
    context.setParsedQuery(
        QueryParameterParser.parseQuery(context.getRawQuery(), enforcementParam));

    log.debug(
        "Parsed request metadata for service {}: EndpointPath={}, EnforcementParam={}",
        context.getConfig().getServiceName(),
        endpointPath,
        enforcementParam);
  }

  /**
   * Routes enforcement by HTTP method. - GET requests: Apply label constraints to query parameters
   * - POST requests: Apply label constraints to form data
   */
  private Mono<RequestContext> routeEnforcementByMethod(RequestContext context) {
    if (context.getMethod() == HttpMethod.GET) {
      log.debug(
          "RequestProcessingService: Enforcing query parameters for GET request using QueryEnforcementFlow");
      return enforceQueryParameters(context);
    } else if (context.getMethod() == HttpMethod.POST) {
      log.debug("RequestProcessingService: Enforcing form data for POST request");
      return enforceFormData(context);
    } else {
      log.debug(
          "RequestProcessingService: No enforcement needed for {} request", context.getMethod());
      return Mono.just(context);
    }
  }

  /** Skip enforcement for binary requests (like Tempo trace endpoints). */
  private Mono<RequestContext> skipEnforcementForBinaryRequest(RequestContext context) {
    log.debug(
        "RequestProcessingService: Skipping enforcement for binary request to {}",
        context.getEndpointPath());

    // For binary requests, just pass through the original query without enforcement
    context.setFinalQuery(context.getRawQuery());
    return Mono.just(context);
  }

  /** Enforce query parameters with security constraints (GET requests). */
  private Mono<RequestContext> enforceQueryParameters(RequestContext context) {
    log.debug(
        "RequestProcessingService: Using QueryEnforcementFlow for query parameter enforcement");

    // Handle special endpoints that require custom processing
    if (endpointPathUtils.isLabelValuesEndpoint(context.getEndpointPath())) {
      log.debug("Processing label values endpoint: {}", context.getEndpointPath());
      String finalQuery = handleLabelValuesEndpoint(context);
      context.setFinalQuery(finalQuery);
      return Mono.just(context);
    }

    if (endpointPathUtils.isLabelsEndpoint(context.getEndpointPath())) {
      log.debug("Processing labels endpoint: {}", context.getEndpointPath());
      String finalQuery = handleLabelsEndpoint(context);
      context.setFinalQuery(finalQuery);
      return Mono.just(context);
    }

    // Handle series endpoints (Thanos/Prometheus specific)
    if (endpointPathUtils.isSeriesEndpoint(context.getEndpointPath())
        && "thanos".equalsIgnoreCase(context.getConfig().getServiceName())) {
      log.debug("Processing series endpoint: {}", context.getEndpointPath());
      String finalQuery = handleSeriesEndpoint(context);
      context.setFinalQuery(finalQuery);
      return Mono.just(context);
    }

    // For regular query endpoints, apply standard enforcement
    String finalQuery = enforceQueryAndConstructFinalQuery(context);
    context.setFinalQuery(finalQuery);

    log.debug(
        "QueryEnforcementFlow completed: '{}' -> '{}'",
        context.getParsedQuery().enforcementParamValue(),
        finalQuery);

    return Mono.just(context);
  }

  /** Enforce form data with security constraints (POST requests). */
  private Mono<RequestContext> enforceFormData(RequestContext context) {
    log.debug("RequestProcessingService: Enforcing form data using QueryEnforcementFlow");

    return context
        .getFormDataMono()
        .map(
            formData -> {
              String query = formData.getFirst(context.getEnforcementParam());
              if (query == null || query.trim().isEmpty()) {
                log.debug("No query parameter found in form data, returning original");
                context.setEnforcedFormData(formData);
                return context;
              }

              log.debug("Extracted query from form data: '{}'", query);

              // Get label constraints for the user
              var labelConstraints =
                  context
                      .getConfig()
                      .getLabelStore()
                      .getServiceLabelConstraints(
                          context.getToken(), context.getConfig().getServiceName());

              // Use the unified query enforcement flow
              String enhancedQuery =
                  queryEnforcementFlow.enhanceQueryWithExplicitFlow(
                      query, context.getConfig().getServiceName(), labelConstraints);

              // Create new form data with enhanced query
              MultiValueMap<String, String> enforcedFormData = new LinkedMultiValueMap<>(formData);
              enforcedFormData.set(context.getEnforcementParam(), enhancedQuery);

              context.setEnforcedFormData(enforcedFormData);
              log.debug("Form data enforcement completed: '{}' -> '{}'", query, enhancedQuery);
              return context;
            })
        .defaultIfEmpty(context);
  }

  /**
   * Builds the target URL with enforced query. Throws on error — .map() converts to Mono.error().
   */
  private void buildTargetUrl(RequestContext context) {
    String targetUrl =
        requestUrlBuilder.buildTargetUrl(
            context.getEndpointPath(), context.getFinalQuery(), context.getConfig());
    context.setTargetUrl(targetUrl);
    log.debug("Built target URL: {}", targetUrl);
  }

  /** Forward request to backend service. */
  private Mono<RequestContext> forwardToBackend(RequestContext context) {
    log.debug("RequestProcessingService: Forwarding to backend");
    return forwardRequestService.forwardToBackend(context);
  }

  /** Forward binary request to backend service. */
  private Mono<RequestContext> forwardBinaryToBackend(RequestContext context) {
    log.debug("RequestProcessingService: Forwarding binary request to backend");
    return forwardRequestService.forwardBinaryToBackend(context);
  }

  /** Extract typed response from context. */
  @SuppressWarnings("unchecked")
  private <T> ResponseEntity<T> extractTypedResponse(RequestContext context) {
    return (ResponseEntity<T>) context.getResponse();
  }

  /** Handle errors for text requests. */
  private Mono<ResponseEntity<String>> handleError(Throwable e) {
    if (e instanceof SecurityException securityException) {
      log.warn("Security violation: {}", e.getMessage());
      return errorHandler.handleSecurityViolation(securityException, null);
    } else {
      log.error("Error processing request: {}", e.getMessage(), e);
      return errorHandler.handleGeneralError(e);
    }
  }

  /** Handle errors for binary requests. */
  private Mono<ResponseEntity<byte[]>> handleBinaryError(Throwable e) {
    if (e instanceof SecurityException securityException) {
      log.warn("Security violation: {}", e.getMessage());
      return errorHandler.handleSecurityViolationBinary(securityException, null);
    } else {
      log.error("Error processing binary request: {}", e.getMessage(), e);
      return errorHandler.handleGeneralErrorBinary(e);
    }
  }

  /** Enforces query parameters and constructs the final query string. */
  private String enforceQueryAndConstructFinalQuery(RequestContext context) {
    log.debug("Enforcing query parameters for endpoint: {}", context.getEndpointPath());

    // Extract the query parameter value to enforce
    String existingQueryValue =
        extractQueryParameterValue(context.getParsedQuery(), context.getEnforcementParam());

    if (existingQueryValue == null || existingQueryValue.trim().isEmpty()) {
      log.debug("No query parameter to enforce, returning original query");
      return context.getRawQuery();
    }

    // Apply service-specific enforcement with explicit flow
    String enforcedQueryValue =
        queryEnforcementFlow.enhanceQueryWithExplicitFlow(
            existingQueryValue,
            context.getConfig().getServiceName(),
            context
                .getConfig()
                .getLabelStore()
                .getServiceLabelConstraints(
                    context.getToken(), context.getConfig().getServiceName()));

    // Replace the original query parameter with the enforced value
    String finalQuery =
        replaceOrAddQueryParameter(
            context.getRawQuery(), context.getEnforcementParam(), enforcedQueryValue);

    log.debug("Query enforcement completed: '{}' -> '{}'", existingQueryValue, enforcedQueryValue);
    return finalQuery;
  }

  /** Handles label values endpoints for all services. */
  private String handleLabelValuesEndpoint(RequestContext context) {
    String labelName = endpointPathUtils.extractLabelNameFromPath(context.getEndpointPath());

    if ("__name__".equals(labelName)) {
      return context.getRawQuery();
    }

    log.debug("Processing label/tag values endpoint for label: {}", labelName);

    var labelConstraints =
        context
            .getConfig()
            .getLabelStore()
            .getServiceLabelConstraints(context.getToken(), context.getConfig().getServiceName());

    // Get excluded labels
    Set<String> excludedLabels =
        context.getConfig().getLabelStore().getExcludedLabels(context.getToken());

    // Debug logging
    log.info(
        "LABEL ACCESS CHECK: labelName='{}', labelConstraints={}, excludedLabels={}",
        labelName,
        labelConstraints,
        excludedLabels);

    // Check access with exclusions
    if (!LabelAccessValidator.isLabelAccessAllowed(labelConstraints, labelName, excludedLabels)) {
      log.error(
          "LABEL ACCESS DENIED: labelName='{}', labelConstraints={}, excludedLabels={}",
          labelName,
          labelConstraints,
          excludedLabels);
      throw new SecurityException("Unauthorized label access: " + labelName);
    }

    // Handle service-specific label values endpoints via registry
    return labelValuesEnhancerRegistry
        .getEnhancer(context.getConfig().getServiceName())
        .map(
            enhancer ->
                enhancer.enhanceLabelValuesQuery(
                    new LabelValuesContext(
                        context.getRawQuery(),
                        context.getEnforcementParam(),
                        labelConstraints,
                        context.getParsedQuery())))
        .orElse(context.getRawQuery());
  }

  /** Handles series endpoints for Thanos/Prometheus. */
  private String handleSeriesEndpoint(RequestContext context) {
    log.debug("Processing series endpoint for user: {}", context.getToken().getPreferredUsername());

    var labelConstraints =
        context
            .getConfig()
            .getLabelStore()
            .getServiceLabelConstraints(context.getToken(), context.getConfig().getServiceName());

    return labelValuesEnhancerRegistry
        .getThanosEnhancer()
        .handleSeriesEndpoint(context.getRawQuery(), labelConstraints, context.getParsedQuery());
  }

  /** Handles labels endpoints for all services. */
  private String handleLabelsEndpoint(RequestContext context) {
    log.debug("Processing labels endpoint for user: {}", context.getToken().getPreferredUsername());

    var labelConstraints =
        context
            .getConfig()
            .getLabelStore()
            .getServiceLabelConstraints(context.getToken(), context.getConfig().getServiceName());
    String constraintQuery = QueryEnhancementBuilder.buildLabelsConstraintQuery(labelConstraints);

    log.debug("Built constraint query for labels endpoint: '{}'", constraintQuery);

    if (constraintQuery.isEmpty()) {
      log.debug("No constraints to add for labels endpoint - user has wildcard access");
      return context.getRawQuery() != null ? context.getRawQuery() : "";
    }

    // Handle null rawQuery case
    if (context.getRawQuery() == null) {
      log.debug("Raw query is null, creating new query with constraint");
      return context.getEnforcementParam()
          + "="
          + URLEncoder.encode(constraintQuery, StandardCharsets.UTF_8);
    }

    // Add the constraint query to the existing query parameters
    return context.getRawQuery()
        + "&"
        + context.getEnforcementParam()
        + "="
        + URLEncoder.encode(constraintQuery, StandardCharsets.UTF_8);
  }

  // ============================================================================
  // UTILITY METHODS
  // ============================================================================

  /** Extracts the value of a specific query parameter. */
  private String extractQueryParameterValue(ParsedQueryParameters parsedQuery, String paramName) {
    if (parsedQuery == null) {
      log.debug("No parsed query parameters available (likely POST request)");
      return null;
    }
    if (paramName == null) {
      log.debug("No enforcement parameter specified, skipping query parameter extraction");
      return null;
    }
    var values = parsedQuery.parameters().get(paramName);
    return values != null && !values.isEmpty() ? values.get(0) : null;
  }

  /** Replaces or adds a query parameter in the raw query string. */
  private String replaceOrAddQueryParameter(String rawQuery, String paramName, String paramValue) {
    String encodedValue = URLEncoder.encode(paramValue, StandardCharsets.UTF_8);
    String paramPattern = paramName + "=" + "[^&]*";
    String replacement = paramName + "=" + encodedValue;

    // Handle null rawQuery case
    if (rawQuery == null) {
      log.debug(
          "Raw query is null, creating new query with parameter: {}={}", paramName, paramValue);
      return replacement;
    }

    if (rawQuery.contains(paramName + "=")) {
      // Parameter exists, replace it
      return rawQuery.replaceAll(paramPattern, replacement);
    } else {
      // Parameter doesn't exist, add it
      return rawQuery.isEmpty() ? replacement : rawQuery + "&" + replacement;
    }
  }
}
