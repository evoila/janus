package com.evoila.janus.common.config;

import com.evoila.janus.common.labelstore.ConfigMapLabelStoreFactory;
import com.evoila.janus.common.labelstore.LabelStore;
import com.evoila.janus.security.config.OAuthToken;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Service-specific proxy configuration implementing the ProxyConfig interface. Each service (Loki,
 * Thanos, Tempo) has its own instance with service-specific URL patterns, enforcement parameters,
 * and label constraints.
 */
@Slf4j
@Getter
public final class ServiceProxyConfig implements ProxyConfig {

  private final ServiceType serviceType;
  private final String serviceName;
  private final String baseUrl;
  private final String apiPrefix;
  private final ConfigMapLabelStoreFactory labelStoreFactory;

  public ServiceProxyConfig(
      ServiceType serviceType, String baseUrl, ConfigMapLabelStoreFactory labelStoreFactory) {
    this.serviceType = serviceType;
    this.serviceName = serviceType.getServiceName(); // For backward compatibility
    this.baseUrl = baseUrl;
    this.apiPrefix = serviceType.getApiPrefix();
    this.labelStoreFactory = labelStoreFactory;
  }

  @Override
  public String getApiPrefix() {
    return apiPrefix;
  }

  @Override
  public String getBaseUrl() {
    return baseUrl + serviceType.getApiPrefix();
  }

  /**
   * Get base URL for root API calls (without service prefix) Used when calling /api/** directly
   * instead of /service/api/**
   */
  public String getRootApiBaseUrl() {
    return baseUrl; // Direct to backend without service prefix
  }

  private Map<String, String> getEnforcementParams() {
    return ServiceTypeMapper.getEnforcementParams(serviceType);
  }

  public LabelStore getLabelStore() {
    // Use appropriate label store based on configuration type
    return labelStoreFactory.createServiceAwareLabelStore();
  }

  @Override
  public String getEnforcementParameter(String endpointPath) {
    // Only return enforcement parameter for endpoints that actually support query parameters
    if (!supportsQueryParameters(endpointPath)) {
      return null;
    }

    Map<String, String> enforcementParams = getEnforcementParams();

    // First try exact match
    String exactMatch = enforcementParams.get(endpointPath);
    if (exactMatch != null) {
      return exactMatch;
    }

    // Handle dynamic endpoints with pattern matching
    return getDynamicEnforcementParameter(endpointPath);
  }

  /** Get enforcement parameter for dynamic endpoints using pattern matching */
  private String getDynamicEnforcementParameter(String endpointPath) {
    return ServiceTypeMapper.getDynamicEnforcementParameter(serviceType, endpointPath);
  }

  /**
   * Determines if an endpoint supports query parameters for enforcement Simplified version with
   * only essential logic
   */
  private boolean supportsQueryParameters(String endpointPath) {
    return ServiceTypeMapper.supportsQueryParameters(serviceType, endpointPath);
  }

  @Override
  public String enforceQuery(String query, OAuthToken token, String endpointPath) {
    // This method is deprecated - enforcement is handled by RequestProcessingService
    // Keeping for interface compatibility only
    log.warn(
        "enforceQuery called on ServiceProxyConfig - this should be handled by RequestProcessingService");

    // Return original query (enforcement logic moved to dedicated services)
    return query;
  }

  @Override
  public MultiValueMap<String, String> enforceFormData(
      MultiValueMap<String, String> formData,
      OAuthToken token,
      String enforcementParam,
      String endpointPath) {
    // For form data, we need to enforce the query parameter
    String query = formData.getFirst(enforcementParam);
    String enforcedQuery = enforceQuery(query, token, endpointPath);

    MultiValueMap<String, String> enforcedFormData = new LinkedMultiValueMap<>(formData);
    enforcedFormData.set(enforcementParam, enforcedQuery);
    return enforcedFormData;
  }

  @Override
  public boolean shouldSkipEnforcement(String endpointPath, String rawQuery) {
    boolean skip = !supportsQueryParameters(endpointPath);
    if (skip) {
      log.debug(
          "Skipping enforcement for endpoint {} - does not support query parameters", endpointPath);
    }
    return skip;
  }

  @Override
  public String processRequestPath(String requestPath) {
    return ServiceTypeMapper.getPathProcessor(serviceType).apply(requestPath);
  }
}
