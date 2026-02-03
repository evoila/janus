package com.evoila.janus.common.labelstore;

import com.evoila.janus.security.config.OAuthToken;
import java.util.Map;
import java.util.Set;

/**
 * Interface for retrieving user-specific label permissions Supports both single-dimension
 * (namespace) and multi-dimension (namespace + service_name) access control
 */
public interface LabelStore {

  /**
   * Check if user has cluster-wide access (bypasses all label restrictions)
   *
   * @param token OAuth token containing user information
   * @return true if user should bypass all label enforcement
   */
  boolean hasClusterWideAccess(OAuthToken token);

  /**
   * Check if user has access to a specific label
   *
   * @param token OAuth token containing user information
   * @param labelName name of the label to check access for
   * @return true if user has access to this label, false otherwise
   */
  boolean hasLabelAccess(OAuthToken token, String labelName);

  /**
   * Get all labels that the user has access to
   *
   * @param token OAuth token containing user information
   * @return Set of label names the user can access, or ["*"] for all labels
   */
  Set<String> getAllowedLabels(OAuthToken token);

  /**
   * Get service-specific label constraints for a user
   *
   * @param token OAuth token containing user information
   * @param serviceName name of the service (loki, thanos, tempo)
   * @return Map of label dimensions to allowed values for the specific service
   */
  Map<String, Set<String>> getServiceLabelConstraints(OAuthToken token, String serviceName);

  /**
   * Get tenant headers for a user and service
   *
   * @param token OAuth token containing user information
   * @param serviceName name of the service (loki, thanos, tempo)
   * @return Map of header names to header values for the specific service and user
   */
  Map<String, String> getTenantHeaders(OAuthToken token, String serviceName);

  /**
   * Get labels that are explicitly excluded for a user
   *
   * @param token OAuth token containing user information
   * @return Set of label names that are excluded (e.g., from "!=labelname" syntax)
   */
  Set<String> getExcludedLabels(OAuthToken token);
}
