package com.evoila.janus.common.config;

import com.evoila.janus.common.labelstore.ConfigMapLabelStoreFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Factory for creating proxy configurations Provides caching and centralized configuration
 * management
 */
@Component
@RequiredArgsConstructor
public class ProxyConfigFactory {

  private final ProxyProperties proxyProperties;
  private final ConfigMapLabelStoreFactory labelStoreFactory;
  private final Map<String, ProxyConfig> configCache = new ConcurrentHashMap<>();

  /** Gets or creates a proxy configuration for the given service type */
  public ProxyConfig getProxyConfig(ServiceType serviceType) {
    return configCache.computeIfAbsent(
        serviceType.getServiceName(), serviceName -> createProxyConfig(serviceType));
  }

  /** Creates a new proxy configuration for the given service type */
  private ServiceProxyConfig createProxyConfig(ServiceType serviceType) {
    // Get the service URL from properties
    ProxyProperties.ServiceConfig serviceConfig =
        proxyProperties.getServices().get(serviceType.getServiceName());
    if (serviceConfig == null) {
      throw new IllegalArgumentException(
          "Service configuration not found for: " + serviceType.getServiceName());
    }

    return new ServiceProxyConfig(serviceType, serviceConfig.getUrl(), labelStoreFactory);
  }
}
