package com.evoila.janus.common.service;

import com.evoila.janus.common.config.ProxyConfig;
import com.evoila.janus.common.config.ServiceProxyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Builds target URLs for backend services Handles URL construction and path normalization */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestUrlBuilder {
  private static final String PATH_SEPARATOR = "/";

  /** Builds the target URL for the backend service */
  public String buildTargetUrl(String endpointPath, String finalQuery, ProxyConfig config) {
    // Check if this is a root API call (starts with "api/")
    boolean isRootApiCall = endpointPath.startsWith("api/");

    String baseUrl;
    if (isRootApiCall && config instanceof ServiceProxyConfig serviceProxyConfig) {
      baseUrl = serviceProxyConfig.getRootApiBaseUrl();
      log.debug("Using root API base URL for endpoint: {}", endpointPath);
    } else {
      baseUrl = config.getBaseUrl();
    }

    String fullPath;
    if (baseUrl.endsWith(PATH_SEPARATOR) && endpointPath.startsWith(PATH_SEPARATOR)) {
      fullPath = baseUrl + endpointPath.substring(1);
    } else if (!baseUrl.endsWith(PATH_SEPARATOR) && !endpointPath.startsWith(PATH_SEPARATOR)) {
      fullPath = baseUrl + PATH_SEPARATOR + endpointPath;
    } else {
      fullPath = baseUrl + endpointPath;
    }

    String targetUrl =
        (finalQuery != null && !finalQuery.isEmpty()) ? fullPath + "?" + finalQuery : fullPath;
    log.debug("Built target URL: {} for service: {}", targetUrl, config.getServiceName());
    return targetUrl;
  }

  /** Processes the request path according to service-specific rules */
  public String processRequestPath(String requestPath, ProxyConfig config) {
    String processedPath = config.processRequestPath(requestPath);
    log.debug(
        "Processed request path: {} -> {} for service: {}",
        requestPath,
        processedPath,
        config.getServiceName());
    return processedPath;
  }
}
