package com.evoila.janus.common.service;

import com.evoila.janus.common.config.ProxyConfig;
import com.evoila.janus.common.labelstore.LabelStore;
import com.evoila.janus.security.config.OAuthToken;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * Manages request headers for backend service communication Handles header construction and
 * content-type management
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestHeaderManager {

  private final LabelStore labelStore;

  /** Builds headers for the request with dynamic tenant headers */
  public Map<String, String> buildHeaders(ProxyConfig config, OAuthToken token) {
    Map<String, String> headers = new HashMap<>();

    // Get dynamic tenant headers based on user and service
    Map<String, String> tenantHeaders = labelStore.getTenantHeaders(token, config.getServiceName());
    headers.putAll(tenantHeaders);

    log.debug("Built headers for {} request: {}", config.getServiceName(), headers);
    return headers;
  }

  /** Adds content-type header for POST requests */
  public Map<String, String> addContentTypeHeader(Map<String, String> headers, HttpMethod method) {
    if (method == HttpMethod.POST) {
      headers.put("Content-Type", "application/x-www-form-urlencoded");
      log.debug("Added content-type header for POST request");
    }
    return headers;
  }

  /** Adds accept header for binary requests */
  public Map<String, String> addAcceptHeader(Map<String, String> headers, String acceptType) {
    headers.put("Accept", acceptType);
    log.debug("Added accept header: {}", acceptType);
    return headers;
  }
}
