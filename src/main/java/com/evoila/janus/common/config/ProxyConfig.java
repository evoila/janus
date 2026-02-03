package com.evoila.janus.common.config;

import com.evoila.janus.common.labelstore.LabelStore;
import com.evoila.janus.security.config.OAuthToken;
import org.springframework.util.MultiValueMap;

public interface ProxyConfig {
  String getApiPrefix();

  String getBaseUrl();

  String getServiceName();

  String getEnforcementParameter(String endpointPath);

  String enforceQuery(String query, OAuthToken token, String endpointPath);

  MultiValueMap<String, String> enforceFormData(
      MultiValueMap<String, String> formData,
      OAuthToken token,
      String enforcementParam,
      String endpointPath);

  boolean shouldSkipEnforcement(String endpointPath, String rawQuery);

  String processRequestPath(String requestPath);

  LabelStore getLabelStore();
}
