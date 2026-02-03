package com.evoila.janus.security.config;

import com.evoila.janus.common.model.GlobalErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Configuration
@Order(-2) // Higher priority than DefaultErrorWebExceptionHandler
@RequiredArgsConstructor
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

  private final JsonMapper jsonMapper;

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    // Enhanced logging with request details
    logRequestDetails(exchange, ex);

    ErrorInfo errorInfo = determineErrorResponse(exchange, ex);

    log.error(
        "Returning error response: {} {} - {}",
        errorInfo.status().value(),
        errorInfo.status().getReasonPhrase(),
        errorInfo.message());

    return writeErrorResponse(
        exchange, errorInfo.status(), errorInfo.message(), errorInfo.errorCode());
  }

  /** Logs detailed request and exception information */
  private void logRequestDetails(ServerWebExchange exchange, Throwable ex) {
    String path = exchange.getRequest().getPath().value();
    String method = exchange.getRequest().getMethod().name();
    String query = exchange.getRequest().getURI().getQuery();

    log.error("=== GLOBAL EXCEPTION HANDLER ===");
    log.error("Request: {} {} {}", method, path, query != null ? "?" + query : "");
    log.error("Exception Type: {}", ex.getClass().getSimpleName());
    log.error("Exception Message: {}", ex.getMessage());
    log.error("Full Exception: ", ex);
  }

  /** Determines the appropriate error response based on exception type */
  private ErrorInfo determineErrorResponse(ServerWebExchange exchange, Throwable ex) {
    return switch (ex) {
      case AuthenticationException e -> handleAuthenticationException(e);
      case AccessDeniedException e -> handleAccessDeniedException(e);
      case MethodNotAllowedException e -> handleMethodNotAllowedException(exchange, e);
      case UnsupportedMediaTypeStatusException e -> handleUnsupportedMediaTypeException(e);
      case WebClientResponseException e -> handleWebClientResponseException(e);
      case JacksonException e -> handleJacksonException(e);
      case IllegalArgumentException e -> handleIllegalArgumentException(e);
      case ResponseStatusException e -> handleResponseStatusException(e);
      default -> handleGenericException(ex);
    };
  }

  /** Handles authentication exceptions */
  private ErrorInfo handleAuthenticationException(AuthenticationException ex) {
    log.warn("Authentication exception: {}", ex.getMessage());
    return new ErrorInfo(HttpStatus.UNAUTHORIZED, "Authentication required", "AUTH_REQUIRED");
  }

  /** Handles access denied exceptions */
  private ErrorInfo handleAccessDeniedException(AccessDeniedException ex) {
    log.warn("Access denied: {}", ex.getMessage());
    return new ErrorInfo(HttpStatus.FORBIDDEN, "Access denied", "ACCESS_DENIED");
  }

  /** Handles method not allowed exceptions */
  private ErrorInfo handleMethodNotAllowedException(
      ServerWebExchange exchange, MethodNotAllowedException ex) {
    log.warn(
        "Method not allowed: {} for {}",
        ex.getHttpMethod(),
        exchange.getRequest().getPath().value());
    return new ErrorInfo(
        HttpStatus.METHOD_NOT_ALLOWED,
        "Method " + ex.getHttpMethod() + " not allowed",
        "METHOD_NOT_ALLOWED");
  }

  /** Handles unsupported media type exceptions */
  private ErrorInfo handleUnsupportedMediaTypeException(UnsupportedMediaTypeStatusException ex) {
    log.warn("Unsupported media type: {}", ex.getMessage());
    return new ErrorInfo(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", "UNSUPPORTED_MEDIA_TYPE");
  }

  /** Handles web client response exceptions */
  private ErrorInfo handleWebClientResponseException(WebClientResponseException ex) {
    log.error(
        "Backend service error: {} {} - {}",
        ex.getStatusCode(),
        ex.getStatusText(),
        ex.getResponseBodyAsString());
    return new ErrorInfo(
        HttpStatus.valueOf(ex.getStatusCode().value()),
        "Backend service error: " + ex.getStatusText(),
        "BACKEND_ERROR");
  }

  /** Handles Jackson exceptions (JSON processing errors) */
  private ErrorInfo handleJacksonException(JacksonException ex) {
    log.warn("JSON processing error: {}", ex.getMessage());
    return new ErrorInfo(HttpStatus.BAD_REQUEST, "Invalid JSON format", "INVALID_JSON");
  }

  /** Handles illegal argument exceptions */
  private ErrorInfo handleIllegalArgumentException(IllegalArgumentException ex) {
    log.warn("Invalid parameters: {}", ex.getMessage());
    return new ErrorInfo(
        HttpStatus.BAD_REQUEST, "Invalid request parameters: " + ex.getMessage(), "INVALID_PARAMS");
  }

  /** Handles response status exceptions */
  private ErrorInfo handleResponseStatusException(ResponseStatusException ex) {
    String message = ex.getReason() != null ? ex.getReason() : "Request failed";
    log.warn("Response status exception: {} - {}", ex.getStatusCode(), message);
    return new ErrorInfo(
        HttpStatus.valueOf(ex.getStatusCode().value()), message, "RESPONSE_STATUS_ERROR");
  }

  /** Handles generic exceptions with authentication detection */
  private ErrorInfo handleGenericException(Throwable ex) {
    String exceptionMessage = ex.getMessage();

    if (isAuthenticationRelatedException(exceptionMessage)) {
      log.error("Token-related error: {}", ex.getMessage());
      return new ErrorInfo(HttpStatus.UNAUTHORIZED, "Authentication required", "AUTH_TOKEN_ERROR");
    } else {
      log.error("Unhandled internal server error: {}", ex.getMessage());
      return new ErrorInfo(
          HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "INTERNAL_ERROR");
    }
  }

  /** Checks if the exception message indicates an authentication-related issue */
  private boolean isAuthenticationRelatedException(String exceptionMessage) {
    return exceptionMessage != null
        && (exceptionMessage.contains("JWT")
            || exceptionMessage.contains("token")
            || exceptionMessage.contains("authentication"));
  }

  /** Internal record for passing error info between methods */
  private record ErrorInfo(HttpStatus status, String message, String errorCode) {}

  private Mono<Void> writeErrorResponse(
      ServerWebExchange exchange, HttpStatus status, String message, String errorCode) {
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);

    String path = exchange.getRequest().getPath().value();
    GlobalErrorResponse errorResponse =
        new GlobalErrorResponse(
            status.getReasonPhrase(),
            message,
            status.value(),
            errorCode,
            java.time.Instant.now().toString(),
            path);

    String errorJson;
    try {
      errorJson = jsonMapper.writeValueAsString(errorResponse);
    } catch (JacksonException e) {
      log.error("Failed to serialize error response", e);
      errorJson =
          String.format(
              "{\"error\":\"%s\",\"message\":\"%s\",\"status\":%d}",
              status.getReasonPhrase(), message, status.value());
    }

    log.info("Error response JSON: {}", errorJson);

    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(errorJson.getBytes());
    return exchange.getResponse().writeWith(Mono.just(buffer));
  }
}
