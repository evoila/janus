package com.evoila.janus.common.service;

import com.evoila.janus.common.model.ProxyErrorResponse;
import com.evoila.janus.security.config.OAuthToken;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Centralized error handling for proxy requests Manages security violations and general errors */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorHandler {

  private final JsonMapper jsonMapper;

  /** Handles security violations for text responses */
  public Mono<ResponseEntity<String>> handleSecurityViolation(
      SecurityException e, OAuthToken token) {
    return handleSecurityViolationTyped(e, token, Function.identity());
  }

  /** Handles security violations for binary responses */
  public Mono<ResponseEntity<byte[]>> handleSecurityViolationBinary(
      SecurityException e, OAuthToken token) {
    return handleSecurityViolationTyped(e, token, s -> s.getBytes(StandardCharsets.UTF_8));
  }

  /** Handles general errors for text responses */
  public Mono<ResponseEntity<String>> handleGeneralError(Throwable e) {
    return handleGeneralErrorTyped(e, Function.identity());
  }

  /** Handles general errors for binary responses */
  public Mono<ResponseEntity<byte[]>> handleGeneralErrorBinary(Throwable e) {
    return handleGeneralErrorTyped(e, s -> s.getBytes(StandardCharsets.UTF_8));
  }

  private <T> Mono<ResponseEntity<T>> handleSecurityViolationTyped(
      SecurityException e, OAuthToken token, Function<String, T> bodyConverter) {
    String username = token != null ? token.getPreferredUsername() : "unknown";
    log.warn("Security violation for user {}: {}", username, e.getMessage());
    String errorMessage = serializeError(new ProxyErrorResponse("Forbidden", e.getMessage()));
    return Mono.just(ResponseEntity.status(403).body(bodyConverter.apply(errorMessage)));
  }

  private <T> Mono<ResponseEntity<T>> handleGeneralErrorTyped(
      Throwable e, Function<String, T> bodyConverter) {
    if (e instanceof WebClientResponseException webEx) {
      log.warn("Backend responded with error: {} {}", webEx.getStatusCode(), webEx.getStatusText());
      HttpStatusCode status = webEx.getStatusCode();
      String body = webEx.getResponseBodyAsString();
      String errorMessage =
          body.isEmpty()
              ? serializeError(new ProxyErrorResponse(webEx.getStatusText(), "Request failed"))
              : body;
      return Mono.just(ResponseEntity.status(status).body(bodyConverter.apply(errorMessage)));
    }
    log.error("Error processing request: {}", e.getMessage(), e);
    String errorMessage =
        serializeError(
            new ProxyErrorResponse("Internal Server Error", "An unexpected error occurred"));
    return Mono.just(ResponseEntity.status(500).body(bodyConverter.apply(errorMessage)));
  }

  private String serializeError(ProxyErrorResponse response) {
    try {
      return jsonMapper.writeValueAsString(response);
    } catch (JacksonException e) {
      log.error("Failed to serialize error response", e);
      return "{\"error\":\"Internal Server Error\",\"message\":\"An unexpected error occurred\"}";
    }
  }
}
