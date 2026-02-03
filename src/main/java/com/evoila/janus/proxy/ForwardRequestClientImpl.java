package com.evoila.janus.proxy;

import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Implementation of ForwardRequestClient that handles HTTP requests to backend services.
 *
 * <p>This service provides functionality for: - Forwarding text-based requests (JSON, XML, etc.) -
 * Forwarding binary requests (protobuf, etc.) - Building proper HTTP headers with authentication -
 * Handling form data for POST requests - Comprehensive logging and error handling
 *
 * <p>The implementation uses Spring WebClient for reactive HTTP communication.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ForwardRequestClientImpl implements ForwardRequestClient {

  private final WebClient webClient;

  /**
   * Forwards a text-based request to the backend service.
   *
   * @param fullUrl The complete URL to forward the request to
   * @param method The HTTP method (GET, POST, etc.)
   * @param headers The HTTP headers to include in the request
   * @param formData Optional form data for POST requests
   * @return Mono containing the response entity with text content
   */
  @Override
  public Mono<ResponseEntity<String>> forwardRequest(
      String fullUrl,
      HttpMethod method,
      Map<String, String> headers,
      MultiValueMap<String, String> formData) {
    logRequest(fullUrl, method, headers, formData);

    URI uri = URI.create(fullUrl);

    WebClient.RequestHeadersSpec<?> requestSpec = buildRequestSpec(uri, method, headers);

    Mono<ResponseEntity<String>> responseMono =
        supportsBody(method) && hasFormData(formData)
            ? ((WebClient.RequestBodySpec) requestSpec)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .toEntity(String.class)
            : requestSpec.retrieve().toEntity(String.class);

    return responseMono
        .doOnNext(this::logResponse)
        .doOnError(e -> logError(e, fullUrl, method, headers, formData));
  }

  /**
   * Forwards a binary request to the backend service.
   *
   * @param fullUrl The complete URL to forward the request to
   * @param method The HTTP method (GET, POST, etc.)
   * @param headers The HTTP headers to include in the request
   * @param formData Optional form data for POST requests
   * @return Mono containing the response entity with binary content
   */
  @Override
  public Mono<ResponseEntity<byte[]>> forwardRequestBinary(
      String fullUrl,
      HttpMethod method,
      Map<String, String> headers,
      MultiValueMap<String, String> formData) {

    logRequest(fullUrl, method, headers, formData);

    URI uri = URI.create(fullUrl);

    WebClient.RequestHeadersSpec<?> requestSpec = buildRequestSpec(uri, method, headers);

    Mono<ResponseEntity<byte[]>> responseMono =
        supportsBody(method) && hasFormData(formData)
            ? ((WebClient.RequestBodySpec) requestSpec)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .toEntity(byte[].class)
            : requestSpec.retrieve().toEntity(byte[].class);

    return responseMono
        .doOnNext(this::logResponse)
        .doOnError(e -> logError(e, fullUrl, method, headers, formData));
  }

  /**
   * Builds a WebClient request specification with the given URI, method, and headers.
   *
   * @param uri The URI to request
   * @param method The HTTP method
   * @param headers The headers to include
   * @return WebClient request specification
   */
  private WebClient.RequestHeadersSpec<?> buildRequestSpec(
      URI uri, HttpMethod method, Map<String, String> headers) {
    return webClient.method(method).uri(uri).headers(httpHeaders -> httpHeaders.setAll(headers));
  }

  /**
   * Checks if the HTTP method supports a request body.
   *
   * @param method The HTTP method to check
   * @return true if the method supports a body (POST, PUT, PATCH)
   */
  private boolean supportsBody(HttpMethod method) {
    return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
  }

  /**
   * Checks if form data is present and not empty.
   *
   * @param formData The form data to check
   * @return true if form data is present and not empty
   */
  private boolean hasFormData(MultiValueMap<String, String> formData) {
    return formData != null && !formData.isEmpty();
  }

  /**
   * Logs request details for debugging and monitoring.
   *
   * @param url The URL being requested
   * @param method The HTTP method
   * @param headers The request headers
   * @param formData The form data (if any)
   */
  private void logRequest(
      String url,
      HttpMethod method,
      Map<String, String> headers,
      MultiValueMap<String, String> formData) {
    log.info("Forwarding to backend: {} {}", method, url);
    log.debug("Headers: {}", headers);
    if (hasFormData(formData)) {
      log.info("Form Data: {}", formData);
    }
  }

  private void logResponse(ResponseEntity<?> response) {
    log.info(
        "Response received - Status: {}, Body length: {}",
        response.getStatusCode(),
        response.getBody() != null ? response.getBody().toString().length() : 0);
    log.debug("Response body: {}", response.getBody());
  }

  private void logError(
      Throwable e,
      String url,
      HttpMethod method,
      Map<String, String> headers,
      MultiValueMap<String, String> formData) {
    log.error("=== FORWARD RAW URL SERVICE ERROR ===");
    log.error("Target URL: {}", url);
    log.error("Method: {}", method);
    log.error("Headers: {}", headers);
    if (hasFormData(formData)) {
      log.error("Form Data: {}", formData);
    }
    log.error("Exception Type: {}", e.getClass().getSimpleName());
    log.error("Exception Message: {}", e.getMessage());
    if (e instanceof WebClientResponseException webEx) {
      log.error("HTTP Status: {}", webEx.getStatusCode());
      log.error("Response Body: {}", webEx.getResponseBodyAsString());
      log.error("Response Headers: {}", webEx.getHeaders());
    }
  }
}
