package com.evoila.janus.security.config;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.json.JsonMapper;

class GlobalExceptionHandlerTest {

  private GlobalExceptionHandler exceptionHandler;
  private ServerWebExchange exchange;
  private ServerHttpRequest request;
  private ServerHttpResponse response;
  private DataBufferFactory dataBufferFactory;
  private DataBuffer dataBuffer;

  @BeforeEach
  void setUp() {
    exceptionHandler = new GlobalExceptionHandler(JsonMapper.builder().build());
    exchange = mock(ServerWebExchange.class);
    request = mock(ServerHttpRequest.class);
    response = mock(ServerHttpResponse.class);
    dataBufferFactory = mock(DataBufferFactory.class);
    dataBuffer = mock(DataBuffer.class);

    // Mock request
    var uri = mock(java.net.URI.class);
    when(uri.getQuery()).thenReturn("param=value");
    when(request.getURI()).thenReturn(uri);
    when(request.getPath()).thenReturn(mock(org.springframework.http.server.RequestPath.class));
    when(request.getMethod()).thenReturn(org.springframework.http.HttpMethod.GET);
    when(exchange.getRequest()).thenReturn(request);

    // Mock response
    var headers = mock(org.springframework.http.HttpHeaders.class);
    when(response.getHeaders()).thenReturn(headers);
    when(response.bufferFactory()).thenReturn(dataBufferFactory);
    when(dataBufferFactory.wrap(any(byte[].class))).thenReturn(dataBuffer);
    when(response.writeWith(any(Mono.class))).thenReturn(Mono.empty());
    when(exchange.getResponse()).thenReturn(response);
  }

  @Test
  void testHandleAuthenticationException() {
    // Given
    AuthenticationException authException = new AuthenticationException("Invalid token") {};

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, authException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    verify(response.getHeaders()).add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
  }

  @Test
  void testHandleAccessDeniedException() {
    // Given
    AccessDeniedException accessException = new AccessDeniedException("Access denied");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, accessException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void testHandleJacksonException() {
    // Given - Use StreamReadException which is a concrete JacksonException subclass
    StreamReadException jacksonException = new StreamReadException(null, "Invalid JSON");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, jacksonException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.BAD_REQUEST);
  }

  @Test
  void testHandleIllegalArgumentException() {
    // Given
    IllegalArgumentException illegalException = new IllegalArgumentException("Invalid parameter");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, illegalException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.BAD_REQUEST);
  }

  @Test
  void testHandleResponseStatusException() {
    // Given
    ResponseStatusException responseException =
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, responseException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.NOT_FOUND);
  }

  @Test
  void testHandleJwtTokenException() {
    // Given
    RuntimeException jwtException = new RuntimeException("JWT token is invalid");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, jwtException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void testHandleGenericException() {
    // Given
    RuntimeException genericException = new RuntimeException("Something went wrong");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, genericException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void testHandleNullMessageException() {
    // Given
    RuntimeException nullMessageException = new RuntimeException();

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, nullMessageException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void testHandleWebClientResponseException() {
    // Given
    WebClientResponseException webClientException =
        WebClientResponseException.create(
            502, "Bad Gateway", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, webClientException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.BAD_GATEWAY);
  }

  @Test
  void testHandleMethodNotAllowedException() {
    // Given
    MethodNotAllowedException methodException =
        new MethodNotAllowedException(
            org.springframework.http.HttpMethod.DELETE,
            java.util.List.of(org.springframework.http.HttpMethod.GET));

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, methodException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.METHOD_NOT_ALLOWED);
  }

  @Test
  void testHandleUnsupportedMediaTypeException() {
    // Given
    UnsupportedMediaTypeStatusException mediaTypeException =
        new UnsupportedMediaTypeStatusException("Unsupported");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, mediaTypeException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void testHandleTokenRelatedException() {
    // Given - exception with "token" in message triggers auth detection
    RuntimeException tokenException = new RuntimeException("Invalid token provided");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, tokenException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void testHandleAuthenticationRelatedException() {
    // Given - exception with "authentication" in message triggers auth detection
    RuntimeException authException = new RuntimeException("authentication failed");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, authException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void testHandleExceptionWithNullQuery() {
    // Given - request with null query string
    var uri = mock(java.net.URI.class);
    when(uri.getQuery()).thenReturn(null);
    when(request.getURI()).thenReturn(uri);

    RuntimeException exception = new RuntimeException("Error with null query");

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, exception);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void testHandleResponseStatusExceptionWithoutReason() {
    // Given - ResponseStatusException with null reason
    ResponseStatusException responseException =
        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

    // When
    Mono<Void> result = exceptionHandler.handle(exchange, responseException);

    // Then
    StepVerifier.create(result).verifyComplete();

    verify(response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
