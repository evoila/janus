package com.evoila.janus.common.enforcement.label;

import com.evoila.janus.common.enforcement.model.dto.LabelExpression;
import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies security constraints to normalized {@link LabelExpression} objects.
 *
 * <p>This class handles:
 * <ul>
 *   <li>Operator-specific enforcement (=, !=, =~, !~)</li>
 *   <li>Wildcard expansion to allowed values</li>
 *   <li>Adding missing constraint labels</li>
 *   <li>Validation of enhanced expressions</li>
 * </ul>
 */
@Slf4j
public final class LabelEnhancer {

  private static final String UNAUTHORIZED_MSG = "Unauthorized label value: ";

  /** Operator prefixes that can be embedded in constraint values (check order: longest first). */
  private static final String[] VALUE_OPERATOR_PREFIXES = {
      LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR,  // !~
      LabelPatternUtils.REGEX_MATCH_OPERATOR,       // =~
      LabelPatternUtils.NOT_EQUALS_OPERATOR         // !=
  };

  private LabelEnhancer() {}

  /**
   * Applies security constraints to a list of label expressions.
   *
   * @param expressions The normalized expressions to enhance
   * @param constraints The security constraints (label name → allowed values)
   * @return New list with enhanced expressions; expressions that cannot be enhanced are removed
   */
  public static List<LabelExpression> enhance(
      List<LabelExpression> expressions, Map<String, Set<String>> constraints) {
    List<LabelExpression> result = new ArrayList<>();

    for (LabelExpression expr : expressions) {
      // Passthrough expressions are preserved as-is
      if (expr.passthrough()) {
        result.add(expr);
        continue;
      }

      Set<String> allowedValues = constraints.get(expr.name());

      // No enforcement constraints and no special handling needed: preserve original
      if (allowedValues == null
          && !LabelPatternUtils.isEmptyOrWildcard(expr.value())
          && !LabelPatternUtils.isRegexPattern(expr.value())) {
        result.add(expr);
        continue;
      }

      log.debug("LabelEnhancer: Processing '{}' op='{}' value='{}' allowed={}",
          expr.name(), expr.operator(), expr.value(), allowedValues);

      Optional<LabelExpression> enhanced = enhanceSingle(expr, allowedValues);
      enhanced.ifPresent(result::add);
    }

    return result;
  }

  /**
   * Adds constraint labels that are missing from the current expression list.
   *
   * @param expressions The current list of expressions
   * @param constraints The full set of security constraints
   * @return New list with missing constraints appended
   */
  public static List<LabelExpression> addMissingConstraints(
      List<LabelExpression> expressions, Map<String, Set<String>> constraints) {
    Set<String> existingNames = expressions.stream()
        .filter(e -> !e.passthrough())
        .map(LabelExpression::name)
        .collect(Collectors.toSet());

    List<LabelExpression> result = new ArrayList<>(expressions);

    for (Map.Entry<String, Set<String>> entry : constraints.entrySet()) {
      String labelName = entry.getKey();
      Set<String> allowedValues = entry.getValue();

      // Skip configuration keys and already existing labels
      if (LabelPatternUtils.CONFIGURATION_KEYS.contains(labelName)
          || "labels".equals(labelName)
          || existingNames.contains(labelName)) {
        continue;
      }

      // Skip wildcard constraints
      if (allowedValues == null || allowedValues.isEmpty()
          || LabelPatternUtils.containsWildcardValues(allowedValues)) {
        continue;
      }

      // Build constraint expression for this label (always =~ for missing constraints)
      LabelExpression constraint = buildMissingConstraintExpression(labelName, allowedValues);
      result.add(constraint);
      log.debug("LabelEnhancer: Added missing constraint: {}", constraint.serialize());
    }

    return result;
  }

  /**
   * Validates enhanced expressions against constraints. Filters out expressions with values that
   * are not allowed.
   *
   * @param expressions The enhanced expressions to validate
   * @param constraints The security constraints
   * @return New list with only valid expressions
   */
  public static List<LabelExpression> validate(
      List<LabelExpression> expressions, Map<String, Set<String>> constraints) {
    return expressions.stream()
        .filter(expr -> isValid(expr, constraints))
        .toList();
  }

  // ---------------------------------------------------------------------------
  // Single expression enhancement (replaces OperatorHandlers + STRATEGY_FACTORIES)
  // ---------------------------------------------------------------------------

  private static Optional<LabelExpression> enhanceSingle(
      LabelExpression expr, Set<String> allowedValues) {
    boolean isValueWildcard = LabelPatternUtils.isEmptyOrWildcard(expr.value());
    boolean hasWildcardConstraints =
        allowedValues != null && hasAnyWildcard(allowedValues);
    boolean hasSpecificConstraints =
        allowedValues != null && !allowedValues.isEmpty() && !hasWildcardConstraints;

    // Handle wildcard values for =~ operator (but NOT for !~)
    if (!LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR.equals(expr.operator())
        && LabelPatternUtils.isWildcardPatternForEnhancement(expr.value(), expr.operator())) {
      return handleWildcardPattern(expr, allowedValues);
    }

    return switch (expr.operator()) {
      case "=" -> handleEquals(expr, allowedValues, isValueWildcard, hasWildcardConstraints,
          hasSpecificConstraints);
      case "!=" -> handleNotEquals(expr, allowedValues, isValueWildcard, hasSpecificConstraints);
      case "=~" -> handleRegexMatch(expr, allowedValues, isValueWildcard, hasSpecificConstraints);
      case "!~" -> handleRegexNotMatch(expr, allowedValues, hasSpecificConstraints);
      default -> {
        log.warn("LabelEnhancer: Unknown operator: {}", expr.operator());
        yield Optional.empty();
      }
    };
  }

  // --- Equals (=) ---

  private static Optional<LabelExpression> handleEquals(
      LabelExpression expr, Set<String> allowedValues,
      boolean isValueWildcard, boolean hasWildcardConstraints, boolean hasSpecificConstraints) {
    if (isValueWildcard) {
      return expandWildcard(expr, allowedValues);
    }
    validateEqualsValue(expr, allowedValues, hasWildcardConstraints);
    return Optional.of(expr);
  }

  private static void validateEqualsValue(
      LabelExpression expr, Set<String> allowedValues, boolean hasWildcardConstraints) {
    if (allowedValues == null || allowedValues.isEmpty()) {
      return;
    }
    if (hasWildcardConstraints) {
      return;
    }
    if (allowedValues.contains(expr.value())) {
      return;
    }
    boolean matchesPattern = allowedValues.stream()
        .filter(LabelPatternUtils::isFullRegexPattern)
        .anyMatch(pattern -> matchesRegex(pattern, expr.value()));
    if (matchesPattern) {
      return;
    }
    throw new SecurityException(UNAUTHORIZED_MSG + expr.value());
  }

  // --- Not Equals (!=) ---

  private static Optional<LabelExpression> handleNotEquals(
      LabelExpression expr, Set<String> allowedValues,
      boolean isValueWildcard, boolean hasSpecificConstraints) {
    // Preserve != with empty string as-is
    if (expr.value() != null && expr.value().isEmpty()) {
      return Optional.of(expr);
    }
    if (isValueWildcard) {
      return expandWildcard(expr, allowedValues);
    }
    if (hasSpecificConstraints) {
      Set<String> remaining = allowedValues.stream()
          .filter(v -> !v.equals(expr.value()))
          .collect(Collectors.toSet());
      if (remaining.isEmpty()) {
        throw new SecurityException(UNAUTHORIZED_MSG + expr.value());
      }
      return Optional.of(buildConstraintExpression(expr.name(), remaining));
    }
    return Optional.of(expr);
  }

  // --- Regex Match (=~) ---

  private static Optional<LabelExpression> handleRegexMatch(
      LabelExpression expr, Set<String> allowedValues,
      boolean isValueWildcard, boolean hasSpecificConstraints) {
    if (isValueWildcard) {
      return expandWildcard(expr, allowedValues);
    }
    if (hasSpecificConstraints) {
      Set<String> matching = allowedValues.stream()
          .filter(v -> matchesRegex(expr.value(), v))
          .collect(Collectors.toSet());
      if (matching.isEmpty()) {
        throw new SecurityException(UNAUTHORIZED_MSG + expr.value());
      }
      return Optional.of(buildConstraintExpression(expr.name(), matching));
    }
    // No specific constraints: reconstruct (originalText may have old operator)
    return Optional.of(expr.withOperatorAndValue(
        LabelPatternUtils.REGEX_MATCH_OPERATOR, expr.value()));
  }

  // --- Regex Not Match (!~) ---

  private static Optional<LabelExpression> handleRegexNotMatch(
      LabelExpression expr, Set<String> allowedValues, boolean hasSpecificConstraints) {
    if (allowedValues == null) {
      // No constraints: reconstruct (originalText may have old operator)
      return Optional.of(expr.withOperatorAndValue(
          LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR, expr.value()));
    }
    if (allowedValues.isEmpty()) {
      return Optional.empty();
    }
    if (hasSpecificConstraints) {
      Set<String> remaining = allowedValues.stream()
          .filter(v -> !matchesRegex(expr.value(), v))
          .collect(Collectors.toSet());
      if (remaining.isEmpty()) {
        throw new SecurityException(UNAUTHORIZED_MSG + expr.value());
      }
      return Optional.of(buildConstraintExpression(expr.name(), remaining));
    }
    // Wildcard constraints: preserve original
    return Optional.of(expr.withOperatorAndValue(
        LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR, expr.value()));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Checks if any value in the set is a wildcard — either an exact wildcard pattern (*, .*, .+)
   * or a glob-style pattern containing * (e.g., *order-service).
   */
  private static boolean hasAnyWildcard(Set<String> allowedValues) {
    return allowedValues.stream()
        .anyMatch(v -> LabelPatternUtils.isWildcardPattern(v)
            || v.contains(LabelPatternUtils.WILDCARD_ASTERISK));
  }

  private static Optional<LabelExpression> expandWildcard(
      LabelExpression expr, Set<String> allowedValues) {
    if (allowedValues == null || allowedValues.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(buildConstraintExpression(expr.name(), allowedValues));
  }

  private static Optional<LabelExpression> handleWildcardPattern(
      LabelExpression expr, Set<String> allowedValues) {
    if (allowedValues == null || allowedValues.isEmpty()) {
      return Optional.of(expr.withOperatorAndValue("=~", LabelPatternUtils.REGEX_ANY_CHARS));
    }
    return Optional.of(buildConstraintExpression(expr.name(), allowedValues));
  }

  /**
   * Builds a constraint expression for missing labels (uses =~ operator by default). Constraint
   * values can encode operators as prefixes (e.g., "!~^kube-.*" → operator !~, value ^kube-.*).
   */
  private static LabelExpression buildMissingConstraintExpression(
      String labelName, Set<String> allowedValues) {
    if (allowedValues.size() == 1) {
      String value = allowedValues.iterator().next();
      String[] parsed = extractOperatorPrefix(value);
      if (parsed != null) {
        return new LabelExpression(labelName, parsed[0], parsed[1], true, null);
      }
      return new LabelExpression(labelName, "=~", value, true, null);
    }
    String pattern = allowedValues.stream()
        .map(v -> {
          if (LabelPatternUtils.isFullRegexPattern(v)
              || v.contains(LabelPatternUtils.REGEX_ANY_CHARS)
              || v.contains(LabelPatternUtils.REGEX_ONE_OR_MORE)) {
            return v;
          }
          return v;
        })
        .collect(Collectors.joining("|"));
    return new LabelExpression(labelName, "=~", pattern, true, null);
  }

  /**
   * Builds a constraint expression from operator handling (uses = for single values, =~ for
   * multiple). Constraint values can encode operators as prefixes.
   */
  private static LabelExpression buildConstraintExpression(
      String labelName, Set<String> allowedValues) {
    if (allowedValues.size() == 1) {
      String value = allowedValues.iterator().next();
      String[] parsed = extractOperatorPrefix(value);
      if (parsed != null) {
        return new LabelExpression(labelName, parsed[0], parsed[1], true, null);
      }
      if (LabelPatternUtils.isWildcardPattern(value)) {
        return new LabelExpression(labelName, "=~",
            LabelPatternUtils.convertWildcardToRegex(value), true, null);
      }
      return new LabelExpression(labelName, "=", value, true, null);
    }
    String pattern = allowedValues.stream()
        .map(v -> {
          if (LabelPatternUtils.isFullRegexPattern(v)
              || v.contains(LabelPatternUtils.REGEX_ANY_CHARS)
              || v.contains(LabelPatternUtils.REGEX_ONE_OR_MORE)) {
            return v;
          }
          return v;
        })
        .collect(Collectors.joining("|"));
    return new LabelExpression(labelName, "=~", pattern, true, null);
  }

  /**
   * Extracts an operator prefix from a constraint value. Constraint values can encode operators
   * as prefixes, e.g., "!~^kube-.*" → ["!~", "^kube-.*"].
   *
   * @return [operator, value] if prefix found, null otherwise
   */
  private static String[] extractOperatorPrefix(String value) {
    if (value != null) {
      for (String prefix : VALUE_OPERATOR_PREFIXES) {
        if (value.startsWith(prefix)) {
          return new String[]{prefix, value.substring(prefix.length())};
        }
      }
    }
    return null;
  }

  private static boolean matchesRegex(String regexPattern, String valueToTest) {
    try {
      Pattern pattern = Pattern.compile(regexPattern);
      return pattern.matcher(valueToTest).matches();
    } catch (PatternSyntaxException _) {
      return regexPattern.contains(valueToTest) || valueToTest.contains(regexPattern);
    }
  }

  private static boolean isValid(LabelExpression expr, Map<String, Set<String>> constraints) {
    if (expr.passthrough()) {
      return true;
    }

    // Regex and not-match operators skip value validation
    String op = expr.operator();
    if (op.contains("~") || "!=".equals(op)) {
      return true;
    }

    // Validate specific values against constraints
    Matcher matcher = LabelPatternUtils.LABEL_MATCHER_PATTERN.matcher(expr.serialize());
    if (matcher.find()) {
      String labelName = matcher.group(1);
      String labelValue = matcher.group(3);
      boolean allowed = LabelAccessValidator.isLabelValueAccessAllowed(
          constraints, labelName, labelValue);
      log.debug("LabelEnhancer: validate '{}' value='{}' allowed={}", labelName, labelValue,
          allowed);
      return allowed;
    }
    return true;
  }
}
