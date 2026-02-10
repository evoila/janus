package com.evoila.janus.common.enforcement.label;

import com.evoila.janus.common.enforcement.model.dto.LabelConstraintInfo;
import com.evoila.janus.common.enforcement.model.dto.LabelExpression;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.dto.QuerySyntax;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.utils.StringParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Facade for the label processing pipeline.
 *
 * <p>Orchestrates the linear flow: Parse → Normalize → Enhance → Validate → Serialize. Delegates
 * each step to a focused class ({@link LabelParser}, {@link LabelNormalizer}, {@link
 * LabelEnhancer}).
 */
@Slf4j
public final class LabelProcessor {

  private LabelProcessor() {}

  /**
   * Enhances a labels section with security constraints using PromQL/LogQL syntax (default).
   *
   * @param existingLabels The existing labels section to enhance
   * @param labelConstraints The security constraints to apply (label name → allowed values)
   * @return EnhancementResult containing the enhanced labels section
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
   * <p>Main entry point for label enhancement. Executes the pipeline:
   *
   * <ol>
   *   <li>Parse — string → {@link LabelExpression} list (once, no re-parse)
   *   <li>Normalize — wildcards, empty strings, operator conversions on the data structure
   *   <li>Enhance — apply security constraints per operator
   *   <li>Validate — filter out disallowed values
   *   <li>Add missing — append constraint labels not already present
   *   <li>Serialize — {@link LabelExpression} list → string (once, at the end)
   * </ol>
   *
   * @param existingLabels The existing labels section to enhance
   * @param labelConstraints The security constraints to apply (label name → allowed values)
   * @param syntax The query syntax configuration for language-specific parsing
   * @return EnhancementResult containing the enhanced labels section
   */
  public static EnhancementResult enhanceLabelsSection(
      String existingLabels, Map<String, Set<String>> labelConstraints, QuerySyntax syntax) {
    if (existingLabels == null || existingLabels.trim().isEmpty()) {
      // No existing labels — build constraints from scratch
      List<LabelExpression> missing =
          LabelEnhancer.addMissingConstraints(List.of(), labelConstraints);
      return toResult(missing, syntax);
    }

    log.debug("LabelProcessor: Enhancing labels section: '{}'", existingLabels);

    try {
      // 1. Parse: String → structured expressions (once, never again)
      List<LabelExpression> expressions = LabelParser.parse(existingLabels, syntax);

      // 2. Normalize: wildcards, empty strings, pattern conversions on data structure
      expressions = LabelNormalizer.normalize(expressions, labelConstraints);

      // 3. Enhance: apply security constraints per operator
      expressions = LabelEnhancer.enhance(expressions, labelConstraints);

      // If no expressions survived enhancement, return failure
      if (expressions.isEmpty()) {
        log.debug("LabelProcessor: No labels were enhanced, returning failure");
        return EnhancementResult.failure("No labels could be enhanced");
      }

      // 4. Validate: filter out disallowed values
      expressions = LabelEnhancer.validate(expressions, labelConstraints);

      // 5. Add missing: append constraint labels not already present
      expressions = LabelEnhancer.addMissingConstraints(expressions, labelConstraints);

      // 6. Serialize: structured expressions → string (once, at the end)
      String finalLabels = serialize(expressions, syntax);
      log.debug("LabelProcessor: Final enhanced labels section: '{}'", finalLabels);

      return EnhancementResult.success(finalLabels, List.of());

    } catch (SecurityException e) {
      throw e;
    } catch (Exception e) {
      log.error("LabelProcessor: Failed to enhance labels section: {}", existingLabels, e);
      return EnhancementResult.failure("Failed to enhance labels section");
    }
  }


  /**
   * Parses a labels section string into a map of label constraints (PromQL/LogQL syntax). Duplicate
   * label names are deduplicated (last wins).
   */
  public static Map<String, LabelConstraintInfo> parseLabelsSection(String labels) {
    return parseLabelsSection(labels, QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL));
  }

  /**
   * Parses a labels section string into a map of label constraints using the given syntax.
   * Duplicate label names are deduplicated (last wins).
   */
  public static Map<String, LabelConstraintInfo> parseLabelsSection(
      String labels, QuerySyntax syntax) {
    List<LabelExpression> expressions = LabelParser.parse(labels, syntax);
    return expressions.stream()
        .collect(
            Collectors.toMap(
                LabelExpression::name,
                e -> new LabelConstraintInfo(e.value(), e.operator(), e.quoted(), e.originalText()),
                (a, b) -> b,
                LinkedHashMap::new));
  }

  /**
   * Parses a labels section into individual label pair strings using PromQL/LogQL syntax.
   */
  public static List<String> parseLabelPairs(String labels) {
    return parseLabelPairs(labels, QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL));
  }

  /**
   * Parses a labels section into individual label pair strings using the given syntax.
   */
  public static List<String> parseLabelPairs(String labels, QuerySyntax syntax) {
    return LabelParser.splitPairs(labels, syntax);
  }

  /**
   * Normalizes wildcard patterns in a labels section. Kept for backward compatibility with existing
   * tests that call this method directly.
   */
  public static String normalizeWildcardPatterns(
      String labels,
      Map<String, LabelConstraintInfo> parsedConstraints,
      Set<String> existingLabelNames,
      Map<String, Set<String>> labelConstraints) {
    // Convert to LabelExpression, normalize, serialize back
    QuerySyntax syntax = QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL);
    List<LabelExpression> expressions = LabelParser.parse(labels, syntax);
    List<LabelExpression> normalized = LabelNormalizer.normalize(expressions, labelConstraints);
    return serialize(normalized, syntax);
  }

  /**
   * Checks if a query has label selectors.
   */
  public static boolean hasLabelSelectors(String query) {
    return query != null && !StringParser.findLabelSections(query).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  private static String serialize(List<LabelExpression> expressions, QuerySyntax syntax) {
    return expressions.stream()
        .map(LabelExpression::serialize)
        .collect(Collectors.joining(syntax.separator()));
  }

  private static EnhancementResult toResult(List<LabelExpression> expressions, QuerySyntax syntax) {
    String result = serialize(expressions, syntax);
    return EnhancementResult.success(result, List.of());
  }
}
