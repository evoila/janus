package com.evoila.janus.common.enforcement.label;

import com.evoila.janus.common.enforcement.core.OperatorHandlers;
import com.evoila.janus.common.enforcement.model.dto.EnhancementData;
import com.evoila.janus.common.enforcement.model.dto.LabelConstraintInfo;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.dto.QuerySyntax;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import com.evoila.janus.common.enforcement.utils.StringParser;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Static utility class for processing and enhancing label expressions with security constraints.
 *
 * <p>This class provides comprehensive functionality for: - Parsing label sections from queries
 * (e.g., "namespace='demo',service='*'") - Normalizing wildcard patterns and complex expressions -
 * Applying security constraints to individual labels - Validating label values against allowed
 * constraints - Building enhanced label sections with proper syntax
 *
 * <p>The processor supports various operators (=, !=, =~, !~) and handles complex scenarios like
 * wildcard patterns, regex expressions, and nested structures.
 */
@Slf4j
public final class LabelProcessor {

  // Static operator strategy factories to avoid recreation
  private static final Map<
          String, Function<Map<String, Set<String>>, Function<EnhancementData, Optional<String>>>>
      STRATEGY_FACTORIES =
          Map.of(
              LabelPatternUtils.EQUALS_OPERATOR, labelConstraints -> OperatorHandlers::handleEquals,
              LabelPatternUtils.NOT_EQUALS_OPERATOR,
                  labelConstraints -> OperatorHandlers::handleNotEquals,
              LabelPatternUtils.REGEX_MATCH_OPERATOR,
                  labelConstraints -> OperatorHandlers::handleRegexMatch,
              LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR,
                  labelConstraints -> OperatorHandlers::handleRegexNotMatch);

  private LabelProcessor() {
    // Utility class - prevent instantiation
  }

  /**
   * Enhances a labels section with security constraints using PromQL/LogQL syntax (default).
   *
   * <p>Delegates to the language-aware overload with PromQL syntax for backward compatibility.
   *
   * @param existingLabels The existing labels section to enhance
   * @param labelConstraints The security constraints to apply (label name -> allowed values)
   * @return EnhancementResult containing the enhanced labels section and any added constraints
   */
  public static EnhancementResult enhanceLabelsSection(
      String existingLabels, Map<String, Set<String>> labelConstraints) {
    return enhanceLabelsSection(
        existingLabels,
        labelConstraints,
        QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL));
  }

  /**
   * Enhances a labels section with security constraints using the given query syntax.
   *
   * <p>This method is the main entry point for label enhancement. It: - Parses existing labels from
   * the input string - Normalizes wildcard patterns and complex expressions - Applies security
   * constraints to each label - Validates enhanced labels against allowed values - Combines results
   * into a final enhanced labels section
   *
   * @param existingLabels The existing labels section to enhance
   * @param labelConstraints The security constraints to apply (label name -> allowed values)
   * @param syntax The query syntax configuration for language-specific parsing
   * @return EnhancementResult containing the enhanced labels section and any added constraints
   */
  public static EnhancementResult enhanceLabelsSection(
      String existingLabels, Map<String, Set<String>> labelConstraints, QuerySyntax syntax) {
    if (existingLabels == null || existingLabels.trim().isEmpty()) {
      // No existing labels, build constraints from labelConstraints
      return buildConstraintsFromLabelConstraints(labelConstraints, syntax);
    }

    log.debug("LabelProcessor: Enhancing labels section: '{}'", existingLabels);

    try {
      // Parse the existing labels (list preserves duplicates, map for lookups)
      List<Map.Entry<String, LabelConstraintInfo>> parsedEntries =
          parseLabelsSectionAsList(existingLabels, syntax);
      Map<String, LabelConstraintInfo> parsedConstraints =
          parsedEntries.stream()
              .collect(
                  Collectors.toMap(
                      Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));
      Set<String> existingLabelNames = parsedConstraints.keySet();

      // Normalize wildcard patterns
      String normalizedLabels =
          normalizeWildcardPatterns(
              existingLabels, parsedConstraints, existingLabelNames, labelConstraints);

      // Re-parse after normalization if needed
      if (!normalizedLabels.equals(existingLabels)) {
        parsedEntries = parseLabelsSectionAsList(normalizedLabels, syntax);
        parsedConstraints =
            parsedEntries.stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));
        existingLabelNames = parsedConstraints.keySet();
      }

      // Process all existing labels in batch (using list to preserve duplicates)
      EnhancementResult existingLabelsResult =
          processLabelsBatch(parsedEntries, labelConstraints, syntax);

      // If processing existing labels failed, return the failure
      if (!existingLabelsResult.isSuccess()) {
        return existingLabelsResult;
      }

      // Build additional constraints from labelConstraints (but skip wildcard ones)
      EnhancementResult additionalConstraintsResult =
          buildConstraintsFromLabelConstraints(labelConstraints, syntax);

      // Combine the results
      List<String> allLabels = new ArrayList<>();
      List<String> allAddedConstraints = new ArrayList<>();

      // Add existing labels
      if (!existingLabelsResult.getEnhancedQuery().isEmpty()) {
        allLabels.add(existingLabelsResult.getEnhancedQuery());
      }

      // Add additional constraints (but only for labels that don't already exist)
      if (additionalConstraintsResult.isSuccess()
          && !additionalConstraintsResult.getEnhancedQuery().isEmpty()) {
        String additionalConstraints = additionalConstraintsResult.getEnhancedQuery();
        // Only add constraints for labels that don't already exist
        String filteredAdditionalConstraints =
            filterConstraintsForExistingLabels(additionalConstraints, existingLabelNames, syntax);
        if (!filteredAdditionalConstraints.isEmpty()) {
          allLabels.add(filteredAdditionalConstraints);
        }
      }

      String finalLabels = String.join(syntax.separator(), allLabels);
      log.debug("LabelProcessor: Final enhanced labels section: '{}'", finalLabels);

      return EnhancementResult.success(finalLabels, allAddedConstraints);

    } catch (SecurityException e) {
      throw e;
    } catch (Exception e) {
      log.error("LabelProcessor: Failed to enhance labels section: {}", existingLabels, e);
      return EnhancementResult.failure("Failed to enhance labels section");
    }
  }

  /** Filters out constraints for labels that already exist */
  private static String filterConstraintsForExistingLabels(
      String additionalConstraints, Set<String> existingLabelNames, QuerySyntax syntax) {
    if (additionalConstraints.isEmpty()) {
      return "";
    }

    List<String> filteredConstraints = new ArrayList<>();
    List<String> constraintParts = parseLabelPairs(additionalConstraints, syntax);

    for (String constraint : constraintParts) {
      String labelName = extractLabelNameFromConstraint(constraint);
      if (labelName != null && !existingLabelNames.contains(labelName)) {
        filteredConstraints.add(constraint);
      }
    }

    return String.join(syntax.separator(), filteredConstraints);
  }

  /** Extracts the label name from a constraint string */
  private static String extractLabelNameFromConstraint(String constraint) {
    if (constraint == null || constraint.trim().isEmpty()) {
      return null;
    }

    String trimmed = constraint.trim();
    int operatorIndex = -1;

    // Find the first operator
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == '=' || c == '!' || c == '~') {
        operatorIndex = i;
        break;
      }
    }

    if (operatorIndex > 0) {
      return trimmed.substring(0, operatorIndex).trim();
    }

    return null;
  }

  /** Builds constraints from labelConstraints when there are no existing labels */
  private static EnhancementResult buildConstraintsFromLabelConstraints(
      Map<String, Set<String>> labelConstraints, QuerySyntax syntax) {
    List<String> builtConstraints = new ArrayList<>();

    for (Map.Entry<String, Set<String>> entry : labelConstraints.entrySet()) {
      String labelName = entry.getKey();
      Set<String> allowedValues = entry.getValue();

      // Skip configuration keys (e.g., "labels", "groups") — these are not Prometheus labels
      if (LabelPatternUtils.CONFIGURATION_KEYS.contains(labelName) || "labels".equals(labelName)) {
        continue;
      }

      // Skip wildcard constraints
      if (allowedValues != null
          && !allowedValues.isEmpty()
          && !LabelPatternUtils.containsWildcardValues(allowedValues)) {

        // Build constraint for this label
        String constraint = buildConstraintFromValues(labelName, allowedValues);
        builtConstraints.add(constraint);
      }
    }

    String finalConstraints = String.join(syntax.separator(), builtConstraints);
    log.debug("LabelProcessor: Built constraints from labelConstraints: '{}'", finalConstraints);

    return EnhancementResult.success(finalConstraints, builtConstraints);
  }

  /** Builds a constraint string from allowed values */
  private static String buildConstraintFromValues(String labelName, Set<String> allowedValues) {
    return LabelPatternUtils.buildConstraintFromValues(labelName, allowedValues);
  }

  /** Filters out wildcard constraints that should be skipped */
  private static Map<String, LabelConstraintInfo> filterOutWildcardConstraints(
      Map<String, LabelConstraintInfo> parsedConstraints) {
    // Don't filter out existing parsed constraints - they should all be processed
    // The wildcard filtering should only apply when building new constraints
    return parsedConstraints;
  }

  /**
   * Processes all labels in a batch to avoid individual enhancer creation
   *
   * @param parsedEntries List of label entries (preserves duplicate label names)
   * @param labelConstraints The security constraints to apply
   * @param syntax The query syntax configuration
   * @return EnhancementResult with enhanced labels
   */
  private static EnhancementResult processLabelsBatch(
      List<Map.Entry<String, LabelConstraintInfo>> parsedEntries,
      Map<String, Set<String>> labelConstraints,
      QuerySyntax syntax) {
    List<String> enhancedLabels = new ArrayList<>();
    List<String> addedConstraints = new ArrayList<>();

    // Create static operator strategies once
    Map<String, Function<EnhancementData, Optional<String>>> operatorStrategies =
        createStaticOperatorStrategies(labelConstraints);

    for (Map.Entry<String, LabelConstraintInfo> entry : parsedEntries) {
      String labelName = entry.getKey();
      LabelConstraintInfo constraint = entry.getValue();

      // TraceQL passthrough keywords (e.g. "true", "false"): preserve in original position
      if (syntax.isPassthroughKeyword(labelName)) {
        enhancedLabels.add(labelName);
        continue;
      }

      // TraceQL intrinsic attributes: pass through without enforcement
      if (syntax.isIntrinsicAttribute(labelName)) {
        enhancedLabels.add(preserveOriginalOrReconstruct(constraint));
        continue;
      }

      Set<String> allowedValues = labelConstraints.get(labelName);

      // No enforcement constraints defined for this label: preserve original text.
      // Exceptions (must still go through the enhancement pipeline):
      //  - wildcard/empty values need normalization (e.g., service="*" → service=~".*")
      //  - regex-pattern values trigger operator conversion (= → =~) during parsing,
      //    so the originalText still has the old operator and must not be used as-is
      if (allowedValues == null
          && !LabelPatternUtils.isEmptyOrWildcard(constraint.value())
          && !LabelPatternUtils.isRegexPattern(constraint.value())) {
        enhancedLabels.add(preserveOriginalOrReconstruct(constraint));
        continue;
      }

      log.debug(
          "LabelProcessor: Processing label '{}' with constraint: {} and allowed values: {}",
          labelName,
          constraint,
          allowedValues);

      Optional<String> enhancedLabel =
          enhanceSingleLabelWithStaticStrategies(
              labelName, constraint, allowedValues, operatorStrategies);

      enhancedLabel.ifPresent(enhancedLabels::add);
    }

    // If no labels were enhanced, return failure
    if (enhancedLabels.isEmpty()) {
      log.debug("LabelProcessor: No labels were enhanced, returning failure");
      return EnhancementResult.failure("No labels could be enhanced");
    }

    // Filter and validate labels
    List<String> filteredLabels = filterAndValidateLabels(enhancedLabels, labelConstraints);

    String finalLabels = String.join(syntax.separator(), filteredLabels);
    log.debug("LabelProcessor: Final enhanced labels section: '{}'", finalLabels);

    return EnhancementResult.success(finalLabels, addedConstraints);
  }

  /** Preserves original label pair text if available, otherwise reconstructs from components. */
  private static String preserveOriginalOrReconstruct(LabelConstraintInfo constraint) {
    if (constraint.originalText() != null) {
      return constraint.originalText();
    }
    String q = constraint.quoted() ? "\"" : "";
    return constraint.operator() + q + constraint.value() + q;
  }

  /** Creates static operator strategies to avoid recreation */
  private static Map<String, Function<EnhancementData, Optional<String>>>
      createStaticOperatorStrategies(Map<String, Set<String>> labelConstraints) {
    Map<String, Function<EnhancementData, Optional<String>>> strategies = new HashMap<>();

    STRATEGY_FACTORIES.forEach(
        (operator, factory) -> strategies.put(operator, factory.apply(labelConstraints)));

    log.debug("LabelProcessor: Created static operator strategies: {}", strategies.keySet());
    return strategies;
  }

  /** Enhances a single label using static operator strategies */
  private static Optional<String> enhanceSingleLabelWithStaticStrategies(
      String labelName,
      LabelConstraintInfo constraintInfo,
      Set<String> allowedValues,
      Map<String, Function<EnhancementData, Optional<String>>> operatorStrategies) {
    String value = constraintInfo.value();
    String operator = constraintInfo.operator();

    // Pre-compute wildcard checks
    boolean isValueWildcard = LabelPatternUtils.isEmptyOrWildcard(value);
    boolean hasWildcardConstraints =
        allowedValues != null && LabelPatternUtils.containsWildcardValues(allowedValues);
    boolean hasSpecificConstraints =
        allowedValues != null && !allowedValues.isEmpty() && !hasWildcardConstraints;

    log.debug(
        "LabelProcessor: Enhancing label '{}' with operator '{}', value '{}', allowedValues: {}, isValueWildcard: {}, hasWildcardConstraints: {}, hasSpecificConstraints: {}",
        labelName,
        operator,
        value,
        allowedValues,
        isValueWildcard,
        hasWildcardConstraints,
        hasSpecificConstraints);

    EnhancementData enhancementData =
        new EnhancementData(
            labelName,
            value,
            operator,
            allowedValues,
            isValueWildcard,
            hasWildcardConstraints,
            hasSpecificConstraints,
            constraintInfo.quoted(),
            constraintInfo.originalText());

    // Handle special cases first, but NOT for !~ operators
    if (!LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR.equals(operator)
        && LabelPatternUtils.isWildcardPatternForEnhancement(value, operator)) {
      log.debug("LabelProcessor: Handling wildcard pattern for enhancement");
      return LabelPatternUtils.handleWildcardPattern(labelName, allowedValues);
    }

    // Use static strategy pattern
    Function<EnhancementData, Optional<String>> strategy = operatorStrategies.get(operator);
    if (strategy != null) {
      log.debug("LabelProcessor: Using strategy for operator: {}", operator);
      Optional<String> result = strategy.apply(enhancementData);
      log.debug("LabelProcessor: Enhanced label result for '{}': {}", labelName, result);
      return result;
    }

    log.warn("LabelProcessor: Unknown operator: {}", operator);
    return Optional.empty();
  }

  /** Filters and validates enhanced labels */
  private static List<String> filterAndValidateLabels(
      List<String> enhancedLabels, Map<String, Set<String>> labelConstraints) {
    log.debug(
        "LabelProcessor: Filtering {} enhanced labels: {}", enhancedLabels.size(), enhancedLabels);

    return enhancedLabels.stream()
        .filter(
            label -> {
              // Extract label name and value for validation
              Matcher matcher = LabelPatternUtils.LABEL_MATCHER_PATTERN.matcher(label);
              if (matcher.find()) {
                String labelName = matcher.group(1);
                String labelValue = matcher.group(3); // group 3 is the value inside quotes

                // Skip validation for regex patterns
                if (label.contains("=~") || label.contains("!~")) {
                  log.debug("LabelProcessor: Skipping validation for pattern: {}", label);
                  return true;
                }

                // Special handling for != constraints - preserve them as they are user constraints
                if (label.contains("!=")) {
                  log.debug("LabelProcessor: Preserving != constraint: {}", label);
                  return true;
                }

                // Validate specific values
                boolean isAllowed =
                    LabelAccessValidator.isLabelValueAccessAllowed(
                        labelConstraints, labelName, labelValue);
                log.debug(
                    "LabelProcessor: Label '{}' with value '{}' is allowed: {}",
                    labelName,
                    labelValue,
                    isAllowed);
                return isAllowed;
              }
              return true; // Keep labels that don't match pattern
            })
        .toList();
  }

  /**
   * Parses a labels section string into a map of label constraints using PromQL/LogQL syntax.
   *
   * @param labels The labels section to parse
   * @return Map of label name to LabelConstraintInfo
   */
  public static Map<String, LabelConstraintInfo> parseLabelsSection(String labels) {
    return parseLabelsSection(labels, QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL));
  }

  /**
   * Parses a labels section string into a map of label constraints using the given syntax. Note:
   * duplicate label names are deduplicated (last wins). Use {@link #parseLabelsSectionAsList} when
   * duplicate labels must be preserved.
   *
   * @param labels The labels section to parse
   * @param syntax The query syntax configuration
   * @return Map of label name to LabelConstraintInfo
   */
  public static Map<String, LabelConstraintInfo> parseLabelsSection(
      String labels, QuerySyntax syntax) {
    List<Map.Entry<String, LabelConstraintInfo>> entries = parseLabelsSectionAsList(labels, syntax);
    return entries.stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));
  }

  /**
   * Parses a labels section string into an ordered list of label entries, preserving duplicate
   * label names (e.g., resource.service.name="grafana" AND resource.service.name != nil).
   */
  private static List<Map.Entry<String, LabelConstraintInfo>> parseLabelsSectionAsList(
      String labels, QuerySyntax syntax) {
    if (labels == null || labels.trim().isEmpty()) {
      return List.of();
    }

    // Fix URL decoding issues
    String processedLabels = LabelPatternUtils.fixUrlDecodingIssues(labels);

    // Strip braces if present
    if (processedLabels.startsWith("{") && processedLabels.endsWith("}")) {
      processedLabels = processedLabels.substring(1, processedLabels.length() - 1);
    }

    // Parse label pairs properly, respecting quoted values
    List<String> labelPairs = parseLabelPairs(processedLabels, syntax);

    return labelPairs.stream()
        .map(pair -> parseLabelPair(pair, syntax))
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Parses a labels section into individual label pairs using PromQL/LogQL syntax
   * (comma-separated).
   *
   * @param labels The labels section to parse
   * @return List of label pair strings
   */
  public static List<String> parseLabelPairs(String labels) {
    return parseLabelPairs(labels, QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL));
  }

  /**
   * Parses a labels section into individual label pairs using the given syntax.
   *
   * @param labels The labels section to parse
   * @param syntax The query syntax configuration
   * @return List of label pair strings
   */
  public static List<String> parseLabelPairs(String labels, QuerySyntax syntax) {
    if (labels == null || labels.trim().isEmpty()) {
      return List.of();
    }

    if (" && ".equals(syntax.separator())) {
      return splitOnTraceQLSeparator(labels);
    }
    return StringParser.parseIntoPairs(labels, ',', false);
  }

  /**
   * Splits a TraceQL labels string on "&&" separator, respecting quoted values. Handles "&&" with
   * or without surrounding spaces (e.g., "a && b", "a&& b", "a &&b", "a&&b").
   *
   * @param labels The labels string to split
   * @return List of individual label pair strings
   */
  private static List<String> splitOnTraceQLSeparator(String labels) {
    List<String> pairs = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    int i = 0;

    while (i < labels.length()) {
      char c = labels.charAt(i);
      if (c == '"' && (i == 0 || labels.charAt(i - 1) != '\\')) {
        inQuotes = !inQuotes;
      }
      if (!inQuotes && c == '&' && i + 1 < labels.length() && labels.charAt(i + 1) == '&') {
        addNonEmptyPair(pairs, current);
        current.setLength(0);
        i += 2; // skip past &&
      } else {
        current.append(c);
        i++;
      }
    }
    addNonEmptyPair(pairs, current);
    return pairs;
  }

  private static void addNonEmptyPair(List<String> pairs, StringBuilder current) {
    String pair = current.toString().trim();
    if (!pair.isEmpty()) {
      pairs.add(pair);
    }
  }

  /**
   * Parses a single label pair (e.g., "name=value" or "name=~regex") using the given syntax.
   *
   * @param pair The label pair string to parse
   * @param syntax The query syntax configuration for operator precedence
   * @return Map entry with label name and constraint info, or null if parsing fails
   */
  private static Map.Entry<String, LabelConstraintInfo> parseLabelPair(
      String pair, QuerySyntax syntax) {
    try {
      // Check for invalid syntax: ! at the beginning with operators
      if (pair.startsWith("!") && (pair.contains("=") || pair.contains("~"))) {
        log.warn("LabelProcessor: Invalid syntax - ! at beginning with operator: {}", pair);
        return null;
      }

      for (String operator : syntax.operatorPrecedence()) {
        int idx = pair.indexOf(operator);
        if (idx > -1) {
          return buildLabelEntry(pair, operator, idx, syntax);
        }
      }

      // Check if this is a passthrough keyword (e.g., TraceQL "true", "false")
      String trimmed = pair.trim();
      if (syntax.isPassthroughKeyword(trimmed)) {
        return Map.entry(trimmed, new LabelConstraintInfo(trimmed, "", false, trimmed));
      }

      log.warn("LabelProcessor: No valid operator found in label pair: {}", pair);
      return null;

    } catch (Exception e) {
      log.warn("LabelProcessor: Failed to parse label pair: {}", pair, e);
      return null;
    }
  }

  /** Builds a label entry from a parsed operator match within a label pair. */
  private static Map.Entry<String, LabelConstraintInfo> buildLabelEntry(
      String pair, String operator, int operatorIndex, QuerySyntax syntax) {
    String name = pair.substring(0, operatorIndex).trim();
    String rawValue = pair.substring(operatorIndex + operator.length()).trim();
    boolean quoted = rawValue.startsWith("\"");
    String value = extractValue(rawValue, operator);

    // Skip label name validation for intrinsic attributes (e.g. TraceQL's status, duration)
    if (!syntax.isIntrinsicAttribute(name)) {
      LabelAccessValidator.validateLabelName(name);
    }

    String resolvedOperator = maybeConvertToRegexOperator(operator, value);
    return Map.entry(name, new LabelConstraintInfo(value, resolvedOperator, quoted, pair));
  }

  /** Converts = or != to =~ or !~ when the value is a regex pattern. */
  private static String maybeConvertToRegexOperator(String operator, String value) {
    if (!LabelPatternUtils.isRegexPattern(value)) {
      return operator;
    }
    if (LabelPatternUtils.EQUALS_OPERATOR.equals(operator)) {
      log.debug(
          "LabelProcessor: Converting operator from '=' to '=~' for regex pattern: {}", value);
      return LabelPatternUtils.REGEX_MATCH_OPERATOR;
    }
    if (LabelPatternUtils.NOT_EQUALS_OPERATOR.equals(operator)) {
      log.debug(
          "LabelProcessor: Converting operator from '!=' to '!~' for regex pattern: {}", value);
      return LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR;
    }
    return operator;
  }

  /**
   * Extracts and processes the value part of a label pair
   *
   * @param valuePart The value part of the label pair
   * @param operator The operator used
   * @return The processed value
   */
  private static String extractValue(String valuePart, String operator) {
    return LabelPatternUtils.extractValue(valuePart, operator);
  }

  /** Normalizes wildcard patterns in a labels section */
  public static String normalizeWildcardPatterns(
      String labels,
      Map<String, LabelConstraintInfo> parsedConstraints,
      Set<String> existingLabelNames,
      Map<String, Set<String>> labelConstraints) {
    String normalized = labels;

    for (String labelName : existingLabelNames) {
      if (shouldProcessLabel(labelName)) {
        LabelConstraintInfo constraint = parsedConstraints.get(labelName);
        if (shouldNormalizeConstraint(constraint)) {
          normalized =
              processConstraintNormalization(normalized, labelName, constraint, labelConstraints);
        }
      }
    }

    return normalized;
  }

  /** Determines if a label should be processed for normalization */
  private static boolean shouldProcessLabel(String labelName) {
    return labelName != null && !LabelPatternUtils.CONFIGURATION_KEYS.contains(labelName);
  }

  /** Determines if a constraint should be normalized */
  private static boolean shouldNormalizeConstraint(LabelConstraintInfo constraint) {
    return constraint != null
        && constraint.value() != null
        && !LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR.equals(constraint.operator())
        && (LabelPatternUtils.isWildcardPattern(constraint.value())
            || constraint.value().isEmpty());
  }

  /** Processes constraint normalization for a specific label */
  private static String processConstraintNormalization(
      String normalized,
      String labelName,
      LabelConstraintInfo constraint,
      Map<String, Set<String>> labelConstraints) {
    // Handle empty string with = operator
    if (constraint.value() != null
        && constraint.value().isEmpty()
        && LabelPatternUtils.EQUALS_OPERATOR.equals(constraint.operator())) {
      Set<String> allowedValues = labelConstraints.get(labelName);
      if (shouldReplaceWithSingleValue(allowedValues)) {
        return replaceWithSingleAllowedValue(normalized, labelName, constraint, allowedValues);
      }
      return normalizeToWildcardPattern(normalized, labelName, constraint);
    }

    // Handle empty string with != operator (preserve as-is, e.g. namespace!="")
    if (constraint.value() != null
        && constraint.value().isEmpty()
        && LabelPatternUtils.NOT_EQUALS_OPERATOR.equals(constraint.operator())) {
      return normalized;
    }

    // All other cases (including =~"") normalize to wildcard pattern
    return normalizeToWildcardPattern(normalized, labelName, constraint);
  }

  /** Determines if constraint should be replaced with a single value */
  private static boolean shouldReplaceWithSingleValue(Set<String> allowedValues) {
    return allowedValues != null && allowedValues.size() == 1;
  }

  /** Replaces constraint with single allowed value */
  private static String replaceWithSingleAllowedValue(
      String normalized,
      String labelName,
      LabelConstraintInfo constraint,
      Set<String> allowedValues) {
    String singleValue = allowedValues.iterator().next();
    String pattern = labelName + constraint.operator() + "\"" + constraint.value() + "\"";
    String replacement = labelName + "=\"" + singleValue + "\"";

    log.debug("Replaced with single allowed value: '{}' -> '{}'", pattern, replacement);
    return normalized.replace(pattern, replacement);
  }

  /** Normalizes to a wildcard pattern */
  private static String normalizeToWildcardPattern(
      String normalized, String labelName, LabelConstraintInfo constraint) {
    String pattern = labelName + constraint.operator() + "\"" + constraint.value() + "\"";

    // For empty strings with = operator, preserve the = operator and use .+
    // For other wildcard patterns, convert to =~ operator and use .*
    String wildcardPattern =
        constraint.value() != null
                && constraint.value().isEmpty()
                && LabelPatternUtils.EQUALS_OPERATOR.equals(constraint.operator())
            ? LabelPatternUtils.DEFAULT_WILDCARD_PATTERN
            : LabelPatternUtils.REGEX_ANY_CHARS;

    // Preserve the original operator for empty strings with = operator
    String operator =
        constraint.value() != null
                && constraint.value().isEmpty()
                && LabelPatternUtils.EQUALS_OPERATOR.equals(constraint.operator())
            ? constraint.operator()
            : "=~";

    String replacement = labelName + operator + "\"" + wildcardPattern + "\"";

    log.debug("Normalized to wildcard pattern: '{}' -> '{}'", pattern, replacement);
    return normalized.replace(pattern, replacement);
  }

  /**
   * Checks if a query has label selectors
   *
   * @param query The query to check
   * @return true if the query contains label selectors
   */
  public static boolean hasLabelSelectors(String query) {
    return query != null && !StringParser.findLabelSections(query).isEmpty();
  }
}
