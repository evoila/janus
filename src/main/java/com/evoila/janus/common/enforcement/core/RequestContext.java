package com.evoila.janus.common.enforcement.core;

import com.evoila.janus.common.config.ProxyConfig;
import com.evoila.janus.common.enforcement.query.ParsedQueryParameters;
import com.evoila.janus.security.config.OAuthToken;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

/**
 * Context object that holds all request data and processing state throughout the request pipeline.
 *
 * <p>This class encapsulates all information needed for request processing: - Original request data
 * (path, query, method, token, form data) - Processing state (parsed query, endpoint path, target
 * URL) - Response data and error information
 *
 * <p>The context is passed through the entire request processing pipeline to maintain state and
 * avoid repeated parsing of request data.
 */
@Data
@Builder
public class RequestContext {

  // Original request data
  private String requestPath;
  private String rawQuery;
  private HttpMethod method;
  private OAuthToken token;
  private Mono<MultiValueMap<String, String>> formDataMono;
  private ProxyConfig config;

  // Optimized query parsing (cached to avoid repeated parsing)
  private ParsedQueryParameters parsedQuery;

  // Processing state
  private String endpointPath;
  private String enforcementParam;
  private String finalQuery;
  private String targetUrl;
  private MultiValueMap<String, String> enforcedFormData;

  // Response data
  private Object response;

  /**
   * Creates a new RequestContext from the original request parameters.
   *
   * <p>This factory method initializes a RequestContext with the basic request information.
   * Processing state fields are left null and will be populated during the processing pipeline.
   *
   * @param requestPath The request path (e.g., "/api/v1/query_range")
   * @param rawQuery The raw query string (e.g., "query=up&start=123&end=456")
   * @param method The HTTP method (GET, POST, etc.)
   * @param token The OAuth token for authentication and authorization
   * @param formDataMono Optional form data for POST requests
   * @param config The proxy configuration for the target service
   * @return A new RequestContext with the provided request data
   */
  public static RequestContext fromRequest(
      String requestPath,
      String rawQuery,
      HttpMethod method,
      OAuthToken token,
      Mono<MultiValueMap<String, String>> formDataMono,
      ProxyConfig config) {

    return RequestContext.builder()
        .requestPath(requestPath)
        .rawQuery(rawQuery)
        .method(method)
        .token(token)
        .formDataMono(formDataMono)
        .config(config)
        .build();
  }
}
