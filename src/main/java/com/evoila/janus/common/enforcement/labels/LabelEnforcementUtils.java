package com.evoila.janus.common.enforcement.labels;

import com.evoila.janus.common.enforcement.query.QueryEnhancementBuilder;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared utilities for label enforcement operations.
 *
 * <p>This class provides common functionality used by query enhancers to build safe constraint
 * queries.
 */
@Slf4j
@Component
public class LabelEnforcementUtils {

  /** Builds a safe constraint query when the original query is empty or null. */
  public String buildSafeConstraintQuery(
      String serviceType, Map<String, Set<String>> labelConstraints) {
    log.info("{}: Building safe query with required constraints", serviceType);
    String safeQuery = QueryEnhancementBuilder.buildLabelValuesConstraintQuery(labelConstraints);
    if (safeQuery.isEmpty()) {
      return "tempo".equalsIgnoreCase(serviceType) ? "{}" : "";
    }
    return safeQuery;
  }
}
