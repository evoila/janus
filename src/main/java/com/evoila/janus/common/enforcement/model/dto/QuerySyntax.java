package com.evoila.janus.common.enforcement.model.dto;

import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import java.util.List;
import java.util.Set;

/**
 * Language-specific query syntax configuration for label processing.
 *
 * <p>Encapsulates all syntax differences between query languages (PromQL/LogQL vs TraceQL) so that
 * the label processing pipeline can handle them generically without scattered language-specific
 * conditionals.
 *
 * @param separator The separator between label pairs ("," for PromQL/LogQL, " && " for TraceQL)
 * @param operatorPrecedence Operators in precedence order for parsing label pairs
 * @param intrinsicAttributes Label names that are intrinsic to the query language and should not be
 *     enforced
 * @param passthroughKeywords Standalone keywords (e.g. TraceQL's "true", "false") that should be
 *     preserved as-is without parsing
 */
public record QuerySyntax(
    String separator,
    List<String> operatorPrecedence,
    Set<String> intrinsicAttributes,
    Set<String> passthroughKeywords) {
  // TraceQL intrinsic attributes (not security-relevant, pass through unchanged)
  private static final Set<String> TRACEQL_INTRINSICS =
      Set.of(
          "status",
          "name",
          "kind",
          "duration",
          "childCount",
          "nestedSetParent",
          "nestedSetLeft",
          "nestedSetRight",
          "traceDuration",
          "rootName",
          "rootServiceName");

  // TraceQL operators include comparison operators
  private static final List<String> TRACEQL_OPERATORS =
      List.of("!~", "=~", "!=", ">=", "<=", "=", ">", "<");

  // TraceQL standalone keywords that are not label constraints
  private static final Set<String> TRACEQL_PASSTHROUGH_KEYWORDS = Set.of("true", "false");

  private static final QuerySyntax PROMQL_SYNTAX =
      new QuerySyntax(",", List.of(LabelPatternUtils.getOperatorPrecedence()), Set.of(), Set.of());

  private static final QuerySyntax TRACEQL_SYNTAX =
      new QuerySyntax(" && ", TRACEQL_OPERATORS, TRACEQL_INTRINSICS, TRACEQL_PASSTHROUGH_KEYWORDS);

  public static QuerySyntax forLanguage(QueryContext.QueryLanguage language) {
    return switch (language) {
      case TRACEQL -> TRACEQL_SYNTAX;
      case PROMQL, LOGQL -> PROMQL_SYNTAX;
    };
  }

  public boolean isIntrinsicAttribute(String labelName) {
    return intrinsicAttributes.contains(labelName);
  }

  public boolean isPassthroughKeyword(String token) {
    return passthroughKeywords.contains(token);
  }
}
