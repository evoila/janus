package com.evoila.janus.common.enforcement.label;

import com.evoila.janus.common.enforcement.model.dto.LabelExpression;
import com.evoila.janus.common.enforcement.model.dto.QuerySyntax;
import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import com.evoila.janus.common.enforcement.utils.StringParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses label section strings into structured {@link LabelExpression} objects.
 *
 * <p>This class is the single entry point for converting raw label strings (e.g.,
 * "namespace=\"demo\",service=~\"order.*\"") into a list of typed expressions. Parsing happens
 * exactly once; all subsequent pipeline stages operate on the structured representation.
 */
@Slf4j
public final class LabelParser {

  private LabelParser() {}

  /**
   * Parses a label section string into a list of {@link LabelExpression} objects.
   *
   * @param labels The raw label section (e.g., "namespace=\"demo\",service=\"order\"")
   * @param syntax The query syntax configuration for language-specific parsing
   * @return Ordered list of parsed expressions, preserving duplicates
   */
  public static List<LabelExpression> parse(String labels, QuerySyntax syntax) {
    if (labels == null || labels.trim().isEmpty()) {
      return List.of();
    }

    String processed = LabelPatternUtils.fixUrlDecodingIssues(labels);

    // Strip braces if present
    if (processed.startsWith("{") && processed.endsWith("}")) {
      processed = processed.substring(1, processed.length() - 1);
    }

    List<String> pairs = splitPairs(processed, syntax);

    return pairs.stream().map(pair -> parsePair(pair, syntax)).filter(Objects::nonNull).toList();
  }

  /**
   * Splits a label section into individual label pair strings.
   *
   * @param labels The label section to split
   * @param syntax The query syntax configuration
   * @return List of individual label pair strings
   */
  static List<String> splitPairs(String labels, QuerySyntax syntax) {
    if (labels == null || labels.trim().isEmpty()) {
      return List.of();
    }

    if (" && ".equals(syntax.separator())) {
      return splitOnTraceQLSeparator(labels);
    }
    return StringParser.parseIntoPairs(labels, ',', false);
  }

  /**
   * Parses a single label pair string into a {@link LabelExpression}.
   *
   * @param pair The label pair string (e.g., "namespace=\"demo\"")
   * @param syntax The query syntax configuration
   * @return The parsed expression, or null if parsing fails
   */
  static LabelExpression parsePair(String pair, QuerySyntax syntax) {
    try {
      // Check for invalid syntax: ! at the beginning with operators
      if (pair.startsWith("!") && (pair.contains("=") || pair.contains("~"))) {
        log.warn("LabelParser: Invalid syntax - ! at beginning with operator: {}", pair);
        return null;
      }

      for (String operator : syntax.operatorPrecedence()) {
        int idx = pair.indexOf(operator);
        if (idx > -1) {
          return buildExpression(pair, operator, idx, syntax);
        }
      }

      // Check if this is a passthrough keyword (e.g., TraceQL "true", "false")
      String trimmed = pair.trim();
      if (syntax.isPassthroughKeyword(trimmed)) {
        return LabelExpression.passthrough(trimmed);
      }

      log.warn("LabelParser: No valid operator found in label pair: {}", pair);
      return null;

    } catch (Exception e) {
      log.warn("LabelParser: Failed to parse label pair: {}", pair, e);
      return null;
    }
  }

  private static LabelExpression buildExpression(
      String pair, String operator, int operatorIndex, QuerySyntax syntax) {
    String name = pair.substring(0, operatorIndex).trim();
    String rawValue = pair.substring(operatorIndex + operator.length()).trim();
    boolean quoted = rawValue.startsWith("\"");
    String value = LabelPatternUtils.extractValue(rawValue, operator);

    // Skip label name validation for intrinsic attributes (e.g. TraceQL's status, duration)
    if (syntax.isIntrinsicAttribute(name)) {
      return new LabelExpression(name, operator, value, quoted, pair, true);
    }

    LabelAccessValidator.validateLabelName(name);

    String resolvedOperator = maybeConvertToRegexOperator(operator, value);
    return new LabelExpression(name, resolvedOperator, value, quoted, pair);
  }

  /** Converts = or != to =~ or !~ when the value is a regex pattern. */
  private static String maybeConvertToRegexOperator(String operator, String value) {
    if (!LabelPatternUtils.isRegexPattern(value)) {
      return operator;
    }
    if (LabelPatternUtils.EQUALS_OPERATOR.equals(operator)) {
      log.debug("LabelParser: Converting operator from '=' to '=~' for regex pattern: {}", value);
      return LabelPatternUtils.REGEX_MATCH_OPERATOR;
    }
    if (LabelPatternUtils.NOT_EQUALS_OPERATOR.equals(operator)) {
      log.debug("LabelParser: Converting operator from '!=' to '!~' for regex pattern: {}", value);
      return LabelPatternUtils.REGEX_NOT_MATCH_OPERATOR;
    }
    return operator;
  }

  /** Splits a TraceQL labels string on "&&" separator, respecting quoted values. */
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
        i += 2;
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
}
