package com.evoila.janus.common.labelstore;

import com.evoila.janus.security.config.OAuthToken;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Service-aware wrapper for ConfigMapLabelStore Provides service-specific label constraints while
 * maintaining the same interface
 */
@Slf4j
public class ServiceAwareLabelStore implements LabelStore {

  private final ConfigMapLabelStore delegate;

  public ServiceAwareLabelStore(ConfigMapLabelStore delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean hasClusterWideAccess(OAuthToken token) {
    return delegate.hasClusterWideAccess(token);
  }

  @Override
  public Map<String, Set<String>> getServiceLabelConstraints(OAuthToken token, String serviceName) {
    return delegate.getServiceLabelConstraints(token, serviceName);
  }

  @Override
  public boolean hasLabelAccess(OAuthToken token, String labelName) {
    return delegate.hasLabelAccess(token, labelName);
  }

  @Override
  public Set<String> getAllowedLabels(OAuthToken token) {
    return delegate.getAllowedLabels(token);
  }

  @Override
  public Map<String, String> getTenantHeaders(OAuthToken token, String serviceName) {
    return delegate.getTenantHeaders(token, serviceName);
  }

  @Override
  public Set<String> getExcludedLabels(OAuthToken token) {
    return delegate.getExcludedLabels(token);
  }
}
