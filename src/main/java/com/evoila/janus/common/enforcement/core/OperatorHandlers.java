package com.evoila.janus.common.enforcement.core;

import com.evoila.janus.common.enforcement.model.dto.EnhancementData;
import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the specific logic for different label operators (=, !=, =~, !~).
 *
 * <p>This class provides operator-specific logic for label enhancement: - Equals operator (=):
 * Exact value matching with security validation - Not equals operator (!=): Inverse matching with
 * constraint conversion - Regex match operator (=~): Pattern matching with allowed value filtering
 * - Regex not match operator (!~): Inverse pattern matching with constraint conversion
 *
 * <p>Each operator handler validates input against security constraints and converts operators to
 * appropriate constraint expressions when needed.
 */
@Slf4j
public final class OperatorHandlers {

  // Expression building constants
  private static final String QUOTE_CHAR = "\"";

  // Error message constants
  private static final String UNAUTHORIZED_LABEL_VALUE_EXCEPTION_MSG = "Unauthorized label value: ";

  private OperatorHandlers() {
    // Utility class - prevent instantiation
  }

  /**
   * Builds a constraint expression from a set of allowed values.
   *
   * <p>This method creates appropriate constraint expressions: - Single value: Uses exact match
   * (e.g., "namespace=\"demo\"") - Multiple values: Uses regex match (e.g.,
   * "namespace=~\"demo|observability\"")
   *
   * @param labelName The label name to build constraint for
   * @param values The set of allowed values for the label
   * @return Constraint expression string with appropriate operator
   */
  private static String buildConstraintFromValues(String labelName, Set<String> values) {
    if (values.size() == 1) {
      // Single value - use exact match
      String value = values.iterator().next();
      return labelName + "=\"" + value + "\"";
    } else {
      // Multiple values - use regex match
      String pattern = String.join("|", values);
      return labelName + "=~\"" + pattern + "\"";
    }
  }

  /**
   * Checks if a value matches a regex pattern with robust error handling.
   *
   * <p>This method safely tests regex patterns and handles invalid patterns gracefully: - Compiles
   * the regex pattern with error handling - Returns true if the value matches the pattern - Falls
   * back to substring matching for invalid regex patterns
   *
   * @param regexPattern The regex pattern to compile and test
   * @param valueToTest The value to test against the pattern
   * @return true if the value matches the pattern, false otherwise
   */
  private static boolean matchesRegexPattern(String regexPattern, String valueToTest) {
    try {
      Pattern pattern = Pattern.compile(regexPattern);
      return pattern.matcher(valueToTest).matches();
    } catch (PatternSyntaxException _) {
      // If regex is invalid, treat it as a literal string match
      return regexPattern.contains(valueToTest) || valueToTest.contains(regexPattern);
    }
  }

  /**
   * Handles the equals (=) operator for label enhancement.
   *
   * <p>This method processes equals operators by: - Validating the value against allowed
   * constraints - Handling wildcard values appropriately - Preserving exact matches when
   * constraints allow - Throwing SecurityException for unauthorized values
   *
   * @param data EnhancementData containing label information and constraints
   * @return Optional containing the enhanced label expression, or empty if no enhancement
   * @throws SecurityException if the value is not allowed by constraints
   */
  public static Optional<String> handleEquals(EnhancementData data) {
    if (data.isValueWildcard()) {
      return handleWildcardValue(data);
    }

    validateEqualsValue(data);

    return Optional.of(data.originalOrReconstructed());
  }

  private static void validateEqualsValue(EnhancementData data) {
    if (data.allowedValues() == null || data.allowedValues().isEmpty()) {
      log.debug(
          "OperatorHandlers: No constraints defined for label '{}', allowing value '{}'",
          data.labelName(),
          data.value());
      return;
    }

    if (hasWildcardConstraint(data.allowedValues())) {
      log.debug(
          "OperatorHandlers: Wildcard constraints found for label '{}', allowing value '{}'",
          data.labelName(),
          data.value());
      return;
    }

    if (data.allowedValues().contains(data.value())) {
      return;
    }

    boolean matchesPattern =
        data.allowedValues().stream()
            .filter(LabelPatternUtils::isFullRegexPattern)
            .anyMatch(pattern -> matchesRegexPattern(pattern, data.value()));

    if (matchesPattern) {
      log.debug(
          "OperatorHandlers: Value '{}' matches regex pattern in allowed values for label '{}'",
          data.value(),
          data.labelName());
      return;
    }

    throw new SecurityException(UNAUTHORIZED_LABEL_VALUE_EXCEPTION_MSG + data.value());
  }

  private static boolean hasWildcardConstraint(Set<String> allowedValues) {
    return allowedValues.stream()
        .anyMatch(
            v ->
                LabelPatternUtils.isWildcardPattern(v)
                    || v.contains(LabelPatternUtils.WILDCARD_ASTERISK));
  }

  /**
   * Handles the not equals (!=) operator for label enhancement.
   *
   * <p>This method processes not-equals operators by: - Converting to remaining allowed values when
   * constraints exist - Preserving original not-equals when no constraints are defined - Handling
   * empty string values specially for != operators - Throwing SecurityException when no valid
   * values remain
   *
   * @param data EnhancementData containing label information and constraints
   * @return Optional containing the enhanced label expression, or empty if no enhancement
   * @throws SecurityException if no valid values remain after filtering
   */
  public static Optional<String> handleNotEquals(EnhancementData data) {
    // Special case: for != operator with empty string, preserve it as-is
    // Empty strings should not be treated as wildcards for != operators
    if (data.value() != null
        && data.value().isEmpty()
        && LabelPatternUtils.NOT_EQUALS_OPERATOR.equals(data.operator())) {
      return Optional.of(data.originalOrReconstructed());
    }

    if (data.isValueWildcard()) {
      return handleWildcardValue(data);
    }

    // For not-equals operator, we need to convert it to the remaining allowed values
    if (data.hasSpecificConstraints()) {
      Set<String> remainingValues =
          data.allowedValues().stream()
              .filter(v -> !v.equals(data.value()))
              .collect(Collectors.toSet());

      if (remainingValues.isEmpty()) {
        // No remaining values - this should not happen as it would be caught by validation
        throw new SecurityException(UNAUTHORIZED_LABEL_VALUE_EXCEPTION_MSG + data.value());
      } else {
        return Optional.of(buildConstraintFromValues(data.labelName(), remainingValues));
      }
    } else {
      // No specific constraints, preserve original not-equals
      return Optional.of(data.originalOrReconstructed());
    }
  }

  /**
   * Handles the regex match (=~) operator for label enhancement.
   *
   * <p>This method processes regex match operators by: - Testing the regex pattern against allowed
   * values - Converting to matching allowed values when constraints exist - Preserving original
   * regex match when no constraints are defined - Throwing SecurityException when no values match
   * the pattern
   *
   * @param data EnhancementData containing label information and constraints
   * @return Optional containing the enhanced label expression, or empty if no enhancement
   * @throws SecurityException if no allowed values match the regex pattern
   */
  public static Optional<String> handleRegexMatch(EnhancementData data) {
    if (data.isValueWildcard()) {
      return handleWildcardValue(data);
    }

    // For regex operators, we need to check if the pattern matches any allowed values
    if (data.hasSpecificConstraints()) {
      // Find all allowed values that match the regex pattern
      Set<String> matchingValues =
          data.allowedValues().stream()
              .filter(allowedValue -> matchesRegexPattern(data.value(), allowedValue))
              .collect(Collectors.toSet());

      if (matchingValues.isEmpty()) {
        throw new SecurityException(UNAUTHORIZED_LABEL_VALUE_EXCEPTION_MSG + data.value());
      } else {
        return Optional.of(buildConstraintFromValues(data.labelName(), matchingValues));
      }
    }

    // Reconstruct explicitly — originalText may have a different operator (= converted to =~)
    String q = data.quoted() ? QUOTE_CHAR : "";
    return Optional.of(
        data.labelName() + LabelPatternUtils.REGEX_MATCH_OPERATOR + q + data.value() + q);
  }

  /**
   * Handles the regex not match (!~) operator for label enhancement.
   *
   * <p>This method processes regex not-match operators by: - Filtering out values that match the
   * regex pattern - Converting to remaining allowed values when constraints exist - Preserving
   * original regex not-match when no constraints are defined - Throwing SecurityException when all
   * values are excluded by the pattern
   *
   * @param data EnhancementData containing label information and constraints
   * @return Optional containing the enhanced label expression, or empty if no enhancement
   * @throws SecurityException if all allowed values are excluded by the regex pattern
   */
  public static Optional<String> handleRegexNotMatch(EnhancementData data) {
    // For !~ operator, we should always process the value as a regex pattern, even if it's a
    // wildcard

    // Handle null allowed values - no constraints defined for this label
    if (data.allowedValues() == null) {
      log.debug(
          "OperatorHandlers: No constraints defined for label '{}', preserving original !~ operator",
          data.labelName());
      // Reconstruct explicitly — originalText may have != (converted to !~)
      String q = data.quoted() ? QUOTE_CHAR : "";
      return Optional.of(
          data.labelName() + LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR + q + data.value() + q);
    }

    // Handle empty allowed values - return empty to indicate no enhancement possible
    if (data.allowedValues().isEmpty()) {
      log.debug(
          "OperatorHandlers: Empty allowed values for label '{}', returning empty",
          data.labelName());
      return Optional.empty();
    }

    // For regex not match (!~), we need to convert it to a regex match with remaining allowed
    // values
    if (data.hasSpecificConstraints()) {
      log.info(
          "OperatorHandlers: Processing !~ operator with pattern '{}' and allowed values: {}",
          data.value(),
          data.allowedValues());

      // Filter out values that match the regex pattern
      Set<String> remainingValues =
          data.allowedValues().stream()
              .filter(
                  allowedValue -> {
                    boolean matches = matchesRegexPattern(data.value(), allowedValue);
                    log.info(
                        "OperatorHandlers: Checking if '{}' matches pattern '{}': {}",
                        allowedValue,
                        data.value(),
                        matches);
                    return !matches;
                  })
              .collect(Collectors.toSet());

      log.info("OperatorHandlers: Remaining values after filtering: {}", remainingValues);

      if (remainingValues.isEmpty()) {
        // All values are excluded - this should not be allowed
        log.error(
            "OperatorHandlers: All values excluded by pattern '{}', throwing SecurityException",
            data.value());
        throw new SecurityException(UNAUTHORIZED_LABEL_VALUE_EXCEPTION_MSG + data.value());
      } else {
        return Optional.of(buildConstraintFromValues(data.labelName(), remainingValues));
      }
    } else {
      // No specific constraints, preserve original not-regex-match
      // Reconstruct explicitly — originalText may have != (converted to !~)
      String q = data.quoted() ? QUOTE_CHAR : "";
      return Optional.of(
          data.labelName() + LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR + q + data.value() + q);
    }
  }

  /**
   * Handles wildcard values for any operator type.
   *
   * <p>This method processes wildcard values by: - Expanding wildcards to all allowed values when
   * constraints exist - Returning empty when no constraints are defined - Building constraint
   * expressions from allowed value sets
   *
   * @param data EnhancementData containing label information and constraints
   * @return Optional containing the enhanced label expression, or empty if no constraints exist
   */
  private static Optional<String> handleWildcardValue(EnhancementData data) {
    if (data.allowedValues() == null || data.allowedValues().isEmpty()) {
      return Optional.empty();
    }

    // If we have specific constraints, expand wildcard to allowed values
    return Optional.of(buildConstraintFromValues(data.labelName(), data.allowedValues()));
  }
}
