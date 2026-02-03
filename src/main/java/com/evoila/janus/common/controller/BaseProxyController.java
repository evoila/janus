package com.evoila.janus.common.controller;

import com.evoila.janus.common.config.ProxyConfigFactory;
import com.evoila.janus.common.config.ServiceType;
import com.evoila.janus.common.service.RequestProcessingService;
import com.evoila.janus.security.config.OAuthToken;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Base proxy controller that provides a clean service-oriented interface for request handling.
 *
 * <p>This controller uses the unified RequestProcessingService to process requests and handles: -
 * GET requests (text responses) - POST requests (form data processing) - Binary GET requests (byte
 * array responses)
 *
 * <p>All methods include comprehensive logging for request lifecycle tracking.
 */
@Slf4j
@RequiredArgsConstructor
public class BaseProxyController {

  private final RequestProcessingService requestProcessingService;
  private final ProxyConfigFactory proxyConfigFactory;

  /**
   * Handles GET requests by delegating to the unified request processing service.
   *
   * @param exchange The server web exchange containing request details
   * @param token The OAuth token for authentication and authorization
   * @param serviceType The type of the target service (LOKI, THANOS, TEMPO)
   * @return Mono containing the response entity with text content
   */
  protected Mono<ResponseEntity<String>> handleGetRequest(
      ServerWebExchange exchange, OAuthToken token, ServiceType serviceType) {

    String path = exchange.getRequest().getPath().value();
    String query = exchange.getRequest().getURI().getRawQuery();

    // Log incoming request details
    logIncomingRequest(exchange, token, serviceType, HttpMethod.GET);

    return requestProcessingService.processRequest(
        path, query, HttpMethod.GET, token, null, proxyConfigFactory.getProxyConfig(serviceType));
  }

  /**
   * Handles POST requests with form data by delegating to the unified request processing service.
   *
   * @param exchange The server web exchange containing request details
   * @param token The OAuth token for authentication and authorization
   * @param serviceType The type of the target service (LOKI, THANOS, TEMPO)
   * @return Mono containing the response entity with text content
   */
  protected Mono<ResponseEntity<String>> handlePostRequest(
      ServerWebExchange exchange, OAuthToken token, ServiceType serviceType) {

    String path = exchange.getRequest().getPath().value();
    String query = exchange.getRequest().getURI().getRawQuery();

    // Log incoming request details
    logIncomingRequest(exchange, token, serviceType, HttpMethod.POST);

    return exchange
        .getFormData()
        .doOnNext(formData -> logFormData(formData, serviceType))
        .flatMap(
            formData ->
                requestProcessingService.processRequest(
                    path,
                    query,
                    HttpMethod.POST,
                    token,
                    Mono.just(formData),
                    proxyConfigFactory.getProxyConfig(serviceType)));
  }

  /**
   * Handles binary GET requests by delegating to the unified request processing service.
   *
   * @param exchange The server web exchange containing request details
   * @param token The OAuth token for authentication and authorization
   * @param serviceType The type of the target service (LOKI, THANOS, TEMPO)
   * @return Mono containing the response entity with binary content
   */
  protected Mono<ResponseEntity<byte[]>> handleBinaryGetRequest(
      ServerWebExchange exchange, OAuthToken token, ServiceType serviceType) {

    String path = exchange.getRequest().getPath().value();
    String query = exchange.getRequest().getURI().getRawQuery();

    // Log incoming request details
    logIncomingRequest(exchange, token, serviceType, HttpMethod.GET);

    return requestProcessingService.processBinaryRequest(
        path, query, HttpMethod.GET, token, null, proxyConfigFactory.getProxyConfig(serviceType));
  }

  /**
   * Logs comprehensive details of incoming requests for debugging purposes.
   *
   * @param exchange The server web exchange containing request details
   * @param token The OAuth token for authentication and authorization
   * @param serviceType The type/name of the target service
   * @param method The HTTP method being processed
   */
  private void logIncomingRequest(
      ServerWebExchange exchange, OAuthToken token, ServiceType serviceType, HttpMethod method) {
    log.info("=== INCOMING REQUEST ===");
    log.info(
        "Service: {}, Method: {}, Path: {}",
        serviceType.getServiceName(),
        method,
        exchange.getRequest().getPath().value());
    log.info("User: {}, Groups: {}", token.getPreferredUsername(), token.getGroups());

    log.debug("=== INCOMING REQUEST DETAILS ===");
    log.debug("Service: {}", serviceType.getServiceName());
    log.debug("Method: {}", method);
    log.debug("HTTP Method: {}", exchange.getRequest().getMethod());
    log.debug("Path: {}", exchange.getRequest().getPath().value());
    log.debug("Query String: {}", exchange.getRequest().getURI().getRawQuery());

    // Log headers (excluding sensitive ones)
    Map<String, String> headers =
        exchange.getRequest().getHeaders().headerSet().stream()
            .filter(entry -> !isSensitiveHeader(entry.getKey()))
            .collect(
                Collectors.toMap(Map.Entry::getKey, entry -> String.join(", ", entry.getValue())));
    log.debug("Headers: {}", headers);

    log.debug("=== END INCOMING REQUEST DETAILS ===");
  }

  private void logFormData(MultiValueMap<String, String> formData, ServiceType serviceType) {
    if (formData != null && !formData.isEmpty()) {
      log.info("=== INCOMING FORM DATA for {} ===", serviceType.getServiceName());
      formData.forEach((key, values) -> log.info("Form Data '{}': {}", key, values));
      log.info("=== END INCOMING FORM DATA ===");
    }
  }

  /**
   * Checks if a header is sensitive and should not be logged for security reasons.
   *
   * @param headerName The name of the header to check
   * @return true if the header is sensitive and should not be logged
   */
  private boolean isSensitiveHeader(String headerName) {
    String lowerHeader = headerName.toLowerCase();
    return lowerHeader.contains("authorization")
        || lowerHeader.contains("cookie")
        || lowerHeader.contains("x-plugin-id")
        || lowerHeader.contains("x-id-token")
        || lowerHeader.contains("x-forwarded-for")
        || lowerHeader.contains("x-real-ip");
  }
}
