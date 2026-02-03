package com.evoila.janus.common.enforcement.utils;

import com.evoila.janus.common.config.ProxyConfig;
import com.evoila.janus.security.config.OAuthToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Utility class for enforcement decision logic */
@Slf4j
@Component
public class EnforcementDecisionUtils {

  /** Determines if enforcement should be skipped for a query */
  public boolean shouldSkipQueryEnforcement(
      String endpointPath,
      String rawQuery,
      HttpMethod method,
      OAuthToken token,
      ProxyConfig config) {
    // Check if enforcement should be skipped for admin users
    if (config.getLabelStore().hasClusterWideAccess(token)) {
      log.debug("Skipping enforcement for admin user with cluster-wide access");
      return true;
    }

    // Check if enforcement should be skipped based on config rules
    if (config.shouldSkipEnforcement(endpointPath, rawQuery)) {
      log.info("Skipping enforcement for endpoint: {} with existing parameters", endpointPath);
      return true;
    }

    // For POST requests, skip query parameter enforcement since it will be handled in form data
    if (method == HttpMethod.POST) {
      log.debug(
          "Skipping query parameter enforcement for POST request - will be handled in form data");
      return true;
    }

    return false;
  }

  /** Determines if form data enforcement should be skipped */
  public boolean shouldSkipFormDataEnforcement(
      String endpointPath, OAuthToken token, ProxyConfig config) {
    // Check if enforcement should be skipped for admin users
    if (config.getLabelStore().hasClusterWideAccess(token)) {
      log.debug("Skipping form data enforcement for admin user with cluster-wide access");
      return true;
    }

    // Check if enforcement should be skipped based on config rules
    if (config.shouldSkipEnforcement(endpointPath, null)) {
      log.info("Skipping form data enforcement for endpoint: {}", endpointPath);
      return true;
    }

    return false;
  }
}
