package com.evoila.janus.common.enforcement.label;

import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import com.evoila.janus.common.enforcement.utils.ValidationUtils;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Validates inputs for security enforcement operations.
 *
 * <p>Consolidates functionality from InputValidator and provides comprehensive validation for all
 * security-related operations.
 */
@Slf4j
public final class LabelAccessValidator {

  // Field name constants for consistent validation messages
  private static final String FIELD_QUERY = "query";
  private static final String FIELD_LABEL_CONSTRAINTS = "label constraints";
  private static final String FIELD_LABEL_NAME = "label name";

  private LabelAccessValidator() {
    // Utility class - prevent instantiation
  }

  /** Validates inputs for query enhancement operations */
  public static void validateQueryEnhancementInputs(
      String query, Map<String, Set<String>> labelConstraints) {
    validateQuery(query);
    validateLabelConstraints(labelConstraints);
  }

  /** Validates a query string */
  public static void validateQuery(String query) {
    ValidationUtils.validateNotNullOrEmpty(query, FIELD_QUERY);
  }

  /** Validates label constraints map */
  public static void validateLabelConstraints(Map<String, Set<String>> labelConstraints) {
    ValidationUtils.validateNotNullOrEmpty(labelConstraints, FIELD_LABEL_CONSTRAINTS);

    if (labelConstraints.isEmpty()) {
      log.warn("Empty label constraints provided - no security enforcement will be applied");
    }

    // Validate individual constraints
    labelConstraints
        .entrySet()
        .forEach(
            entry -> {
              validateLabelName(entry.getKey());
              validateLabelValues(entry.getValue());
            });
  }

  /** Validates a label name */
  public static void validateLabelName(String labelName) {
    ValidationUtils.validateNotNullOrEmpty(labelName, FIELD_LABEL_NAME);
    ValidationUtils.validateLabelNameCharacters(labelName);

    // Check for reserved names
    if (LabelPatternUtils.CONFIGURATION_KEYS.contains(labelName)) {
      log.debug("Configuration key used as label name: {}", labelName);
    }
  }

  /** Validates label values set */
  public static void validateLabelValues(Set<String> labelValues) {
    if (labelValues == null) {
      throw new IllegalArgumentException("Label values cannot be null");
    }

    // Empty set is valid (no values allowed)
    if (labelValues.isEmpty()) {
      log.debug("Empty label values set provided");
      return;
    }

    // Validate individual values
    labelValues.forEach(LabelAccessValidator::validateLabelValue);
  }

  /** Validates a single label value */
  public static void validateLabelValue(String labelValue) {
    if (labelValue == null) {
      throw new IllegalArgumentException("Label value cannot be null");
    }

    // Empty string is valid
    if (labelValue.isEmpty()) {
      return;
    }

    // Check for wildcard patterns
    if (LabelPatternUtils.isWildcardPattern(labelValue)) {
      log.debug("Wildcard pattern detected in label value: {}", labelValue);
    }

    // Check for regex patterns
    if (LabelPatternUtils.isRegexPattern(labelValue)) {
      log.debug("Regex pattern detected in label value: {}", labelValue);
    }
  }

  /** Validates that a user has access to a specific label */
  public static boolean isLabelAccessAllowed(
      Map<String, Set<String>> labelConstraints, String labelName) {
    return isLabelAccessAllowed(labelConstraints, labelName, Set.of());
  }

  /** Validates that a user has access to a specific label, considering exclusions */
  public static boolean isLabelAccessAllowed(
      Map<String, Set<String>> labelConstraints, String labelName, Set<String> excludedLabels) {
    if (labelConstraints == null || labelName == null) {
      return false;
    }

    // Check if label is explicitly excluded
    if (excludedLabels != null && excludedLabels.contains(labelName)) {
      log.debug("LabelAccessValidator: Label '{}' is explicitly excluded", labelName);
      return false;
    }

    // Check if label is explicitly allowed
    Set<String> allowedLabels = labelConstraints.get("labels");
    if (allowedLabels != null && !allowedLabels.isEmpty()) {
      if (allowedLabels.contains(LabelPatternUtils.WILDCARD_ASTERISK)) {
        return true; // Wildcard access (and not excluded)
      }
      return allowedLabels.contains(labelName);
    }

    // If no explicit labels constraint, deny access (secure by default)
    log.warn(
        "LabelAccessValidator: No 'labels' key in constraints for label '{}' - denying access",
        labelName);
    return false;
  }

  /**
   * Validates that a user has access to a specific label value.
   *
   * <p>This checks value constraints directly from the constraint map (label-name →
   * allowed-values). Unlike {@link #isLabelAccessAllowed}, this does NOT require a "labels" key in
   * the map, because it is used during query enhancement where constraints are keyed by label name
   * directly.
   */
  public static boolean isLabelValueAccessAllowed(
      Map<String, Set<String>> labelConstraints, String labelName, String value) {
    if (labelConstraints == null || labelName == null) {
      return false;
    }

    Set<String> allowedValues = labelConstraints.get(labelName);
    if (allowedValues == null || allowedValues.isEmpty()) {
      return true; // No constraints means all values are allowed
    }

    if (allowedValues.contains(LabelPatternUtils.WILDCARD_ASTERISK)
        || allowedValues.contains(LabelPatternUtils.REGEX_ANY_CHARS)
        || allowedValues.contains(LabelPatternUtils.REGEX_ONE_OR_MORE)
        || allowedValues.contains(LabelPatternUtils.REGEX_EMPTY_GROUP)) {
      return true; // Wildcard access
    }

    // Check for exact match first
    if (allowedValues.contains(value)) {
      return true;
    }

    // Check for wildcard pattern matches
    for (String allowedValue : allowedValues) {
      if (isWildcardPatternMatch(allowedValue, value)) {
        return true;
      }
    }

    return false;
  }

  /** Checks if a value matches a wildcard pattern */
  private static boolean isWildcardPatternMatch(String pattern, String value) {
    if (pattern == null || value == null) {
      return false;
    }

    // Handle common wildcard patterns
    if (pattern.startsWith(LabelPatternUtils.WILDCARD_ASTERISK)) {
      // Pattern like "*order-service" should match "microservices-order-service"
      String suffix = pattern.substring(1);
      return value.endsWith(suffix);
    }

    if (pattern.endsWith(LabelPatternUtils.WILDCARD_ASTERISK)) {
      // Pattern like "order-service*" should match "order-service-v2"
      String prefix = pattern.substring(0, pattern.length() - 1);
      return value.startsWith(prefix);
    }

    if (pattern.contains(LabelPatternUtils.WILDCARD_ASTERISK)) {
      // Pattern like "order*service" should match "order-microservice"
      String[] parts = pattern.split("\\" + LabelPatternUtils.WILDCARD_ASTERISK, 2);
      if (parts.length == 2) {
        return value.startsWith(parts[0]) && value.endsWith(parts[1]);
      }
    }

    return false;
  }
}
