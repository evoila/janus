package com.evoila.janus.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.evoila.janus.security.config.OAuthToken;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ErrorHandlerTest {

  @Mock private OAuthToken mockToken;

  private ErrorHandler errorHandler;

  @BeforeEach
  void setUp() {
    errorHandler = new ErrorHandler(JsonMapper.builder().build());
  }

  @Test
  void handleSecurityViolation_ShouldReturnForbiddenResponse() {
    // Given
    SecurityException securityException = new SecurityException("Access denied");
    when(mockToken.getPreferredUsername()).thenReturn("test-user");

    // When
    Mono<ResponseEntity<String>> result =
        errorHandler.handleSecurityViolation(securityException, mockToken);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(response.getBody()).contains("Forbidden");
              assertThat(response.getBody()).contains("Access denied");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void handleGeneralError_ShouldReturnInternalServerError() {
    // Given
    RuntimeException generalException = new RuntimeException("Something went wrong");

    // When
    Mono<ResponseEntity<String>> result = errorHandler.handleGeneralError(generalException);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              assertThat(response.getBody()).contains("Internal Server Error");
              assertThat(response.getBody()).contains("An unexpected error occurred");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void handleSecurityViolationBinary_ShouldReturnForbiddenResponseWithBytes() {
    // Given
    SecurityException securityException = new SecurityException("Access denied");
    when(mockToken.getPreferredUsername()).thenReturn("test-user");

    // When
    Mono<ResponseEntity<byte[]>> result =
        errorHandler.handleSecurityViolationBinary(securityException, mockToken);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              String body = new String(response.getBody());
              assertThat(body).contains("Forbidden");
              assertThat(body).contains("Access denied");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void handleGeneralError_WebClientResponseException_ShouldForwardBackendStatus() {
    // Given
    String backendBody = "{\"status\":\"error\",\"error\":\"invalid parameter\"}";
    WebClientResponseException webEx =
        WebClientResponseException.create(
            400,
            "Bad Request",
            HttpHeaders.EMPTY,
            backendBody.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

    // When
    Mono<ResponseEntity<String>> result = errorHandler.handleGeneralError(webEx);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(response.getBody()).contains("invalid parameter");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void handleGeneralErrorBinary_WebClientResponseException_ShouldForwardBackendStatus() {
    // Given
    String backendBody = "{\"status\":\"error\",\"error\":\"not found\"}";
    WebClientResponseException webEx =
        WebClientResponseException.create(
            404,
            "Not Found",
            HttpHeaders.EMPTY,
            backendBody.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

    // When
    Mono<ResponseEntity<byte[]>> result = errorHandler.handleGeneralErrorBinary(webEx);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
              String body = new String(response.getBody());
              assertThat(body).contains("not found");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void handleSecurityViolation_NullToken_ShouldReturnForbiddenWithUnknownUser() {
    // Given - null token should result in "unknown" username
    SecurityException securityException = new SecurityException("Forbidden access");

    // When
    Mono<ResponseEntity<String>> result =
        errorHandler.handleSecurityViolation(securityException, null);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(response.getBody()).contains("Forbidden");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void handleSecurityViolationBinary_NullToken_ShouldReturnForbiddenWithUnknownUser() {
    // Given
    SecurityException securityException = new SecurityException("Forbidden access");

    // When
    Mono<ResponseEntity<byte[]>> result =
        errorHandler.handleSecurityViolationBinary(securityException, null);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              String body = new String(response.getBody());
              assertThat(body).contains("Forbidden");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void handleGeneralError_WebClientResponseException_EmptyBody_ShouldReturnSerializedError() {
    // Given - WebClientResponseException with empty response body
    WebClientResponseException webEx =
        WebClientResponseException.create(
            503, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

    // When
    Mono<ResponseEntity<String>> result = errorHandler.handleGeneralError(webEx);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              // Empty body should trigger serialized error response
              assertThat(response.getBody()).contains("Service Unavailable");
              assertThat(response.getBody()).contains("Request failed");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void handleGeneralErrorBinary_ShouldReturnInternalServerErrorWithBytes() {
    // Given
    RuntimeException generalException = new RuntimeException("Something went wrong");

    // When
    Mono<ResponseEntity<byte[]>> result = errorHandler.handleGeneralErrorBinary(generalException);

    // Then
    StepVerifier.create(result)
        .expectNextMatches(
            response -> {
              assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              String body = new String(response.getBody());
              assertThat(body).contains("Internal Server Error");
              assertThat(body).contains("An unexpected error occurred");
              return true;
            })
        .verifyComplete();
  }
}
