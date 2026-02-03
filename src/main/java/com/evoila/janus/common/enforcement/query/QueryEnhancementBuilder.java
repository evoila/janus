package com.evoila.janus.common.enforcement.query;

import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import com.evoila.janus.common.enforcement.utils.LabelProcessingUtils;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the building and generation of label constraints for query enhancement.
 *
 * <p>Extracted from LabelProcessor and LabelPatternUtils to separate constraint building logic from
 * other concerns. Used specifically in the query enhancement pipeline to enforce security policies
 * by adding missing constraints.
 */
@Slf4j
public final class QueryEnhancementBuilder {

  private QueryEnhancementBuilder() {
    // Utility class - prevent instantiation
  }

  /**
   * Builds missing constraints for labels that aren't already present in the query
   *
   * @param existingLabelNames Labels already present in the query
   * @param labelConstraints Available label constraints from security context
   * @return List of constraint strings to be added to the query
   */
  public static List<String> buildMissingConstraints(
      Set<String> existingLabelNames, Map<String, Set<String>> labelConstraints) {
    Set<String> labelsToProcess = LabelProcessingUtils.getLabelsToProcess(labelConstraints);

    return labelsToProcess.stream()
        .filter(labelName -> !existingLabelNames.contains(labelName))
        .filter(
            labelName -> {
              Set<String> allowedValues = labelConstraints.get(labelName);
              return allowedValues != null && !allowedValues.isEmpty();
            })
        .filter(
            labelName -> {
              // Filter out wildcard constraints - they should not be added as missing constraints
              Set<String> allowedValues = labelConstraints.get(labelName);
              return allowedValues != null
                  && !allowedValues.isEmpty()
                  && !LabelPatternUtils.containsWildcardValues(allowedValues);
            })
        .map(
            labelName -> {
              Set<String> allowedValues = labelConstraints.get(labelName);
              return buildSafeConstraint(labelName, allowedValues);
            })
        .toList();
  }

  /**
   * Builds a safe constraint expression for a label
   *
   * @param labelName The label name
   * @param allowedValues The allowed values
   * @return The constraint expression
   */
  public static String buildSafeConstraint(String labelName, Set<String> allowedValues) {
    return LabelPatternUtils.buildConstraintFromValues(labelName, allowedValues);
  }

  /** Builds a constraint query for labels endpoint */
  public static String buildLabelsConstraintQuery(Map<String, Set<String>> labelConstraints) {
    log.debug("Building labels constraint query for constraints: {}", labelConstraints);

    List<String> constraints = buildConstraints(labelConstraints, true);
    log.debug("Filtered essential constraints: {}", constraints);

    if (constraints.isEmpty()) {
      log.debug("No essential constraints found, returning empty query");
      return "";
    }

    String result = "{" + String.join(", ", constraints) + "}";
    log.debug("Built labels constraint query: '{}'", result);
    return result;
  }

  /** Builds a constraint query for label values endpoint (comprehensive constraints) */
  public static String buildLabelValuesConstraintQuery(Map<String, Set<String>> labelConstraints) {
    log.debug("Building label values constraint query for constraints: {}", labelConstraints);

    List<String> constraints = buildConstraints(labelConstraints, false);

    if (constraints.isEmpty()) {
      log.debug("No specific constraints found, returning empty query");
      return "";
    }

    String result = "{" + String.join(", ", constraints) + "}";
    log.debug("Built label values constraint query: '{}'", result);
    return result;
  }

  /**
   * Builds constraints list from label constraints
   *
   * @param labelConstraints The label constraints map
   * @param labelsOnly If true, only include essential security constraints (for labels endpoint)
   * @return List of constraint strings
   */
  private static List<String> buildConstraints(
      Map<String, Set<String>> labelConstraints, boolean labelsOnly) {
    List<String> constraints = new ArrayList<>();

    // Always filter out the 'labels' key and apply appropriate filtering
    Map<String, Set<String>> filteredConstraints;
    if (labelsOnly) {
      // For labels endpoint: only essential constraints (non-wildcards)
      filteredConstraints = filterEssentialConstraints(labelConstraints);
    } else {
      // For label values endpoint: all constraints except 'labels' key, including wildcards
      filteredConstraints =
          labelConstraints.entrySet().stream()
              .filter(e -> !"labels".equals(e.getKey()))
              // Skip labels whose only value is a wildcard
              .filter(
                  e -> {
                    Set<String> vals = e.getValue();
                    return vals != null
                        && !vals.isEmpty()
                        && !(vals.size() == 1
                            && LabelPatternUtils.isWildcardPattern(vals.iterator().next()));
                  })
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    filteredConstraints.forEach(
        (labelName, allowedValues) -> {
          log.debug("Processing label '{}' with values: {}", labelName, allowedValues);

          if (allowedValues != null && !allowedValues.isEmpty()) {
            String pattern = buildPattern(allowedValues);
            constraints.add(labelName + "=~\"" + pattern + "\"");
            log.debug(
                "Added constraint for label '{}': {} =~ \"{}\"", labelName, labelName, pattern);
          } else if (!labelsOnly) {
            // For label values endpoint, include wildcard constraints (like "*")
            if (allowedValues != null
                && allowedValues.contains(LabelPatternUtils.WILDCARD_ASTERISK)) {
              constraints.add(
                  labelName
                      + LabelPatternUtils.REGEX_MATCH_OPERATOR
                      + "\""
                      + LabelPatternUtils.REGEX_ANY_CHARS
                      + "\"");
              log.debug(
                  "Added wildcard constraint for label '{}': {} =~ \"{}\"",
                  labelName,
                  labelName,
                  LabelPatternUtils.REGEX_ANY_CHARS);
            } else {
              log.debug(
                  "Skipping label '{}' - no specific constraints: {}", labelName, allowedValues);
            }
          } else {
            log.debug(
                "Skipping label '{}' for labels endpoint - wildcard or empty: {}",
                labelName,
                allowedValues);
          }
        });

    log.debug("Built {} constraints: {}", constraints.size(), constraints);
    return constraints;
  }

  /**
   * Builds a regex pattern from allowed values Shared utility method for building regex patterns
   * from allowed values
   */
  public static String buildPattern(Set<String> allowedValues) {
    return allowedValues.stream()
        .filter(v -> !v.isEmpty()) // Filter out empty strings
        .map(
            value -> {
              // Check if value is a full regex pattern (with anchors, character classes, etc.)
              if (LabelPatternUtils.isFullRegexPattern(value)) {
                // Value is a full regex pattern, use as-is
                return value;
              } else if (value.contains(LabelPatternUtils.REGEX_ANY_CHARS)
                  || value.contains(LabelPatternUtils.REGEX_ESCAPED_DOT)
                  || value.contains("\\+")
                  || value.contains("\\*")) {
                // Value already contains regex patterns, use as-is
                return value;
              } else if (value.contains(LabelPatternUtils.WILDCARD_ASTERISK)) {
                // Convert * to .* for simple wildcard patterns
                // Only escape dots if they're part of a wildcard pattern
                return LabelPatternUtils.convertWildcardToRegex(value);
              } else {
                // For literal values like IP addresses, don't escape dots
                // This prevents double-escaping when used in regex context
                return value;
              }
            })
        .collect(Collectors.joining("|"));
  }

  /** Filters essential constraints (non-wildcard constraints) for labels endpoint */
  private static Map<String, Set<String>> filterEssentialConstraints(
      Map<String, Set<String>> labelConstraints) {
    return labelConstraints.entrySet().stream()
        .filter(e -> !"labels".equals(e.getKey()))
        .filter(
            e -> {
              Set<String> vals = e.getValue();
              return vals != null
                  && !vals.isEmpty()
                  && !LabelPatternUtils.containsWildcardValues(vals);
            })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
