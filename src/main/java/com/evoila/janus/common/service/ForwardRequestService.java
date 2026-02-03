package com.evoila.janus.common.service;

import com.evoila.janus.common.enforcement.core.RequestContext;
import com.evoila.janus.proxy.ForwardRequestClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Unified service responsible for forwarding requests to backend services.
 *
 * <p>This service handles: - Forwarding both text-based and binary requests to backend services -
 * Building proper HTTP headers with authentication and authorization - Handling response processing
 * and error management - Ensuring proper request/response flow through the proxy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForwardRequestService {

  private final ForwardRequestClient forwardRequestClient;
  private final RequestHeaderManager requestHeaderManager;

  /**
   * Forwards text-based request to the backend service.
   *
   * @param context The request context containing all request information
   * @return Mono containing the updated context with backend response
   */
  public Mono<RequestContext> forwardToBackend(RequestContext context) {
    return forwardRequest(context, false);
  }

  /**
   * Forwards binary request to the backend service.
   *
   * @param context The request context containing all request information
   * @return Mono containing the updated context with binary response
   */
  public Mono<RequestContext> forwardBinaryToBackend(RequestContext context) {
    return forwardRequest(context, true);
  }

  /**
   * Unified method to forward requests to backend services.
   *
   * @param context The request context containing all request information
   * @param isBinary Whether this is a binary request
   * @return Mono containing the updated context with response
   */
  private Mono<RequestContext> forwardRequest(RequestContext context, boolean isBinary) {
    Map<String, String> headers = buildHeaders(context, isBinary);

    if (isBinary) {
      return forwardBinaryRequest(context, headers);
    } else {
      return forwardTextRequest(context, headers);
    }
  }

  /** Builds headers for the request based on whether it's binary or text. */
  private Map<String, String> buildHeaders(RequestContext context, boolean isBinary) {
    Map<String, String> headers =
        requestHeaderManager.buildHeaders(context.getConfig(), context.getToken());

    if (isBinary) {
      // Add Accept header for binary requests to ensure Tempo returns binary data
      headers.put("Accept", "application/protobuf");
      log.debug("Added Accept header for binary request: application/protobuf");
    } else {
      // Add Content-Type header for text requests
      headers = requestHeaderManager.addContentTypeHeader(headers, context.getMethod());
    }

    return headers;
  }

  /** Forwards a text-based request. */
  private Mono<RequestContext> forwardTextRequest(
      RequestContext context, Map<String, String> headers) {
    return executeForward(
        context,
        forwardRequestClient.forwardRequest(
            context.getTargetUrl(), context.getMethod(), headers, context.getEnforcedFormData()));
  }

  /** Forwards a binary request. */
  private Mono<RequestContext> forwardBinaryRequest(
      RequestContext context, Map<String, String> headers) {
    return executeForward(
        context,
        forwardRequestClient.forwardRequestBinary(
            context.getTargetUrl(), context.getMethod(), headers, context.getEnforcedFormData()));
  }

  /** Sends the request and stores the response on the context. */
  private Mono<RequestContext> executeForward(
      RequestContext context, Mono<? extends ResponseEntity<?>> responseMono) {
    return responseMono.map(
        response -> {
          context.setResponse(response);
          return context;
        });
  }
}
