package com.evoila.janus.common.enforcement.utils;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure utility class for label pattern operations. Contains only utility methods with no business
 * logic dependencies.
 */
@Slf4j
public final class LabelPatternUtils {

  private LabelPatternUtils() {
    // Utility class - prevent instantiation
  }

  // ============================================================================
  // CONSTANTS
  // ============================================================================

  // Operator constants
  public static final String EQUALS_OPERATOR = "=";
  public static final String NOT_EQUALS_OPERATOR = "!=";
  public static final String REGEX_MATCH_OPERATOR = "=~";
  public static final String REGEX_NOT_MATCH_OPERATOR = "!~";

  // Wildcard character constants
  public static final String WILDCARD_ASTERISK = "*";

  // Regex transformation constants
  public static final String REGEX_ANY_CHARS = ".*";
  public static final String REGEX_ONE_OR_MORE = ".+";
  public static final String REGEX_ESCAPED_DOT = "\\.";
  public static final String REGEX_EMPTY_GROUP = "()";

  // Explicit regex marker prefix (used in config files)
  public static final String EXPLICIT_REGEX_PREFIX = "~";

  // Wildcard patterns (using constants)
  public static final Set<String> WILDCARD_PATTERNS =
      Set.of(WILDCARD_ASTERISK, REGEX_ANY_CHARS, REGEX_ONE_OR_MORE, REGEX_EMPTY_GROUP);

  protected static final String[] OPERATOR_PRECEDENCE = {
    REGEX_NOT_MATCH_OPERATOR, REGEX_MATCH_OPERATOR, NOT_EQUALS_OPERATOR, EQUALS_OPERATOR
  };

  /** Returns the operator precedence array */
  public static String[] getOperatorPrecedence() {
    return OPERATOR_PRECEDENCE.clone();
  }

  // Label section detection is handled by StringParser.findLabelSections() (quote-aware)

  public static final Pattern LABEL_MATCHER_PATTERN =
      Pattern.compile("\\s*([^=!~\\s,]+)\\s*([=!~]+)\"([^\"]*)\"");

  // Configuration keys that should be preserved
  public static final Set<String> CONFIGURATION_KEYS =
      Set.of("labels", "groups", "roles", "permissions", "__ignore_usage__");

  // URL decoding fixes
  public static final String URL_DECODING_FIX_FROM = ". ";
  public static final String URL_DECODING_FIX_TO = ".+";

  // Default patterns
  public static final String DEFAULT_WILDCARD_PATTERN = ".+";

  // ============================================================================
  // PURE UTILITY METHODS (no business logic dependencies)
  // ============================================================================

  /** Checks if a value is empty or represents a wildcard pattern */
  public static boolean isEmptyOrWildcard(String value) {
    return value == null || value.isEmpty() || WILDCARD_PATTERNS.contains(value);
  }

  /** Checks if a collection contains wildcard values */
  public static boolean containsWildcardValues(Collection<String> values) {
    return values != null && values.stream().anyMatch(LabelPatternUtils::isWildcardPattern);
  }

  /** Checks if a value is a wildcard pattern */
  public static boolean isWildcardPattern(String value) {
    return value != null && WILDCARD_PATTERNS.contains(value);
  }

  /** Checks if a value is a regex pattern or wildcard pattern */
  public static boolean isRegexPattern(String value) {
    return value != null
        && (value.contains(REGEX_ANY_CHARS)
            || value.contains(REGEX_ONE_OR_MORE)
            || value.contains("|")
            || value.contains("(")
            || WILDCARD_PATTERNS.contains(value));
  }

  /** Converts a wildcard pattern to a regex pattern */
  public static String convertWildcardToRegex(String value) {
    if (value == null) {
      return null;
    }

    // Handle common wildcard patterns
    if (WILDCARD_ASTERISK.equals(value)) {
      return REGEX_ANY_CHARS;
    }

    if (REGEX_ANY_CHARS.equals(value)) {
      return REGEX_ANY_CHARS;
    }

    if (REGEX_ONE_OR_MORE.equals(value)) {
      return REGEX_ONE_OR_MORE;
    }

    // For other patterns, escape dots and convert * to .*
    return value.replace(".", REGEX_ESCAPED_DOT).replace("*", REGEX_ANY_CHARS);
  }

  /**
   * Processes a configuration value by stripping the explicit regex prefix (~) if present.
   *
   * @param value The configuration value to process
   * @return The value with ~ prefix stripped, or the original value if no prefix
   */
  public static String processConfigValue(String value) {
    if (value != null && value.startsWith(EXPLICIT_REGEX_PREFIX)) {
      return value.substring(1);
    }
    return value;
  }

  /**
   * Checks if a raw configuration value starts with the explicit regex prefix (~). This should be
   * called BEFORE processConfigValue() to determine if the value was intended to be a regex
   * pattern.
   *
   * @param value The raw value to check (before processing)
   * @return true if the value starts with ~
   */
  public static boolean hasExplicitRegexPrefix(String value) {
    return value != null && value.startsWith(EXPLICIT_REGEX_PREFIX);
  }

  /**
   * Checks if a value (after processing) is a full regex pattern. This detects patterns that use
   * regex metacharacters beyond simple wildcards. Only use this for values that were originally
   * prefixed with ~.
   *
   * @param value The processed value to check
   * @return true if the value contains regex-specific syntax
   */
  public static boolean isFullRegexPattern(String value) {
    return value != null
        && (value.contains("^")
            || value.contains("$")
            || value.contains("[")
            || value.contains("(")
            || value.contains("|")
            || value.contains("\\"));
  }

  /** Fixes URL decoding issues in label patterns */
  public static String fixUrlDecodingIssues(String labels) {
    if (labels == null) {
      return null;
    }

    // Fix common URL decoding issues
    return labels.replace(URL_DECODING_FIX_FROM, URL_DECODING_FIX_TO);
  }

  /** Extracts the value part from a label expression */
  public static String extractValue(String valuePart, String operator) {
    if (valuePart == null || operator == null) {
      return valuePart;
    }

    // Remove quotes if present
    String value = valuePart.trim();
    if (value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }

    return value;
  }

  /** Checks if a value is a wildcard pattern that should be enhanced */
  public static boolean isWildcardPatternForEnhancement(String value, String operator) {
    // For !~ operator, we should always handle it in the operator strategy, not as a wildcard
    // pattern
    if (REGEX_NOT_MATCH_OPERATOR.equals(operator)) {
      return false;
    }
    return isEmptyOrWildcard(value) && REGEX_MATCH_OPERATOR.equals(operator);
  }

  /** Handles wildcard pattern for enhancement */
  public static Optional<String> handleWildcardPattern(
      String labelName, Set<String> allowedValues) {
    if (allowedValues == null || allowedValues.isEmpty()) {
      // When there are no constraints, preserve the original wildcard pattern as regex
      // This handles user-provided wildcards like pod=\".+\" or pod=\".*\"
      return Optional.of(labelName + "=~\"" + REGEX_ANY_CHARS + "\"");
    }

    if (allowedValues.size() == 1) {
      String value = allowedValues.iterator().next();
      // If the single allowed value is a wildcard, convert it to regex
      if (isWildcardPattern(value)) {
        return Optional.of(labelName + "=~\"" + convertWildcardToRegex(value) + "\"");
      }
      return Optional.of(labelName + "=\"" + value + "\"");
    }

    String pattern =
        allowedValues.stream()
            .map(
                value -> {
                  // Don't escape dots in full regex patterns
                  if (isFullRegexPattern(value)
                      || value.contains(REGEX_ANY_CHARS)
                      || value.contains(REGEX_ONE_OR_MORE)) {
                    return value;
                  }
                  return value.replace(".", "\\.");
                })
            .collect(Collectors.joining("|"));

    return Optional.of(labelName + "=~\"" + pattern + "\"");
  }

  /**
   * Builds a constraint string from allowed values for a label.
   *
   * @param labelName The name of the label
   * @param allowedValues The set of allowed values for the label
   * @return A constraint string in the format "labelName=~\"pattern\""
   */
  public static String buildConstraintFromValues(String labelName, Set<String> allowedValues) {
    if (allowedValues == null || allowedValues.isEmpty()) {
      return labelName + REGEX_MATCH_OPERATOR + "\"" + REGEX_ANY_CHARS + "\"";
    }

    if (allowedValues.size() == 1) {
      String value = allowedValues.iterator().next();
      return labelName + "=~\"" + value + "\"";
    } else {
      String pattern =
          allowedValues.stream()
              .map(
                  v -> {
                    // Check if value is a full regex pattern (with anchors, character classes,
                    // etc.)
                    // or already contains regex patterns - use as-is
                    if (isFullRegexPattern(v)
                        || v.contains(REGEX_ANY_CHARS)
                        || v.contains(REGEX_ONE_OR_MORE)) {
                      return v;
                    } else if (v.contains(WILDCARD_ASTERISK)) {
                      // Convert simple wildcard patterns like demo-* to demo-.*
                      return convertWildcardToRegex(v);
                    } else {
                      // For literal values like IP addresses, don't escape dots
                      return v;
                    }
                  })
              .collect(Collectors.joining("|"));
      return labelName + "=~\"" + pattern + "\"";
    }
  }
}
