package com.evoila.janus.common.enforcement.label;

import com.evoila.janus.common.enforcement.model.dto.LabelExpression;
import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Normalizes parsed {@link LabelExpression} objects before enforcement.
 *
 * <p>Handles wildcard patterns, empty strings, and operator conversions directly on the data
 * structure, eliminating the need for string round-trips and re-parsing.
 */
@Slf4j
public final class LabelNormalizer {

  private LabelNormalizer() {}

  /**
   * Normalizes a list of label expressions.
   *
   * <p>Transformations applied:
   * <ul>
   *   <li>Empty strings with {@code =} operator → single allowed value or {@code .+} pattern</li>
   *   <li>Wildcard patterns ({@code *}, {@code .*}) → regex operator with proper pattern</li>
   *   <li>Preserves {@code !=} and {@code !~} with empty strings as-is</li>
   * </ul>
   *
   * @param expressions The parsed expressions to normalize
   * @param constraints The security constraints (label name → allowed values)
   * @return New list with normalized expressions
   */
  public static List<LabelExpression> normalize(
      List<LabelExpression> expressions, Map<String, Set<String>> constraints) {
    return expressions.stream()
        .map(expr -> normalizeSingle(expr, constraints))
        .toList();
  }

  static LabelExpression normalizeSingle(
      LabelExpression expr, Map<String, Set<String>> constraints) {
    // Passthrough expressions (intrinsics, keywords) are never normalized
    if (expr.passthrough()) {
      return expr;
    }

    // Skip configuration keys
    if (!shouldProcessLabel(expr.name())) {
      return expr;
    }

    if (!shouldNormalize(expr)) {
      return expr;
    }

    return processNormalization(expr, constraints);
  }

  private static boolean shouldProcessLabel(String labelName) {
    return labelName != null && !LabelPatternUtils.CONFIGURATION_KEYS.contains(labelName);
  }

  private static boolean shouldNormalize(LabelExpression expr) {
    if (expr.value() == null) {
      return false;
    }
    // Don't normalize !~ operators
    if (LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR.equals(expr.operator())) {
      return false;
    }
    return LabelPatternUtils.isWildcardPattern(expr.value()) || expr.value().isEmpty();
  }

  private static LabelExpression processNormalization(
      LabelExpression expr, Map<String, Set<String>> constraints) {
    String value = expr.value();
    String operator = expr.operator();

    // Handle empty string with = operator
    if (value.isEmpty() && LabelPatternUtils.EQUALS_OPERATOR.equals(operator)) {
      Set<String> allowedValues = constraints.get(expr.name());
      if (allowedValues != null && allowedValues.size() == 1) {
        // Replace with single allowed value
        String singleValue = allowedValues.iterator().next();
        log.debug("Normalized: replaced empty value with single allowed value '{}' for '{}'",
            singleValue, expr.name());
        return expr.withValue(singleValue);
      }
      // Normalize empty string to =~".+" pattern (operator must change since .+ is regex)
      log.debug("Normalized: empty value to =~\".+\" for '{}'", expr.name());
      return expr.withOperatorAndValue(
          LabelPatternUtils.REGEX_MATCH_OPERATOR, LabelPatternUtils.DEFAULT_WILDCARD_PATTERN);
    }

    // Handle empty string with != operator - preserve as-is
    if (value.isEmpty() && LabelPatternUtils.NOT_EQUALS_OPERATOR.equals(operator)) {
      return expr;
    }

    // All other cases (wildcard patterns): convert to regex operator with .* pattern
    String wildcardPattern = LabelPatternUtils.REGEX_ANY_CHARS;
    log.debug("Normalized: wildcard '{}' to =~\"{}\" for '{}'", value, wildcardPattern, expr.name());
    return expr.withOperatorAndValue(LabelPatternUtils.REGEX_MATCH_OPERATOR, wildcardPattern);
  }
}
