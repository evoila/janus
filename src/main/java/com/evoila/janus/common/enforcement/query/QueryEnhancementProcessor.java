package com.evoila.janus.common.enforcement.query;

import com.evoila.janus.common.enforcement.label.LabelAccessValidator;
import com.evoila.janus.common.enforcement.label.LabelProcessor;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.dto.QuerySyntax;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.utils.StringParser;
import com.evoila.janus.common.enforcement.utils.ValidationUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles enhancement of complete queries with security constraints.
 *
 * <p>This processor applies security constraints to queries by: - Adding missing label constraints
 * to queries without labels - Validating and enhancing existing label selectors - Supporting
 * multiple query languages (PromQL, LogQL, TraceQL) - Ensuring queries are safe and comply with
 * access policies
 *
 * <p>The processor handles both simple queries (e.g., "up") and complex queries with existing label
 * selectors (e.g., "metric{namespace='demo'}").
 */
@Slf4j
public final class QueryEnhancementProcessor {

  // Matches a metric name directly followed by [ (range vector without labels).
  // In PromQL, an identifier followed by [ is always a metric with a range vector selector.
  // Functions (rate, sum, ...) are followed by ( instead, so they never match.
  private static final java.util.regex.Pattern METRIC_RANGE_PATTERN =
      java.util.regex.Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\[");

  private static final String LOG_QUERY_ENHANCEMENT = "Query enhancement: '{}' -> '{}'";

  private QueryEnhancementProcessor() {
    // Utility class - prevent instantiation
  }

  /**
   * Enhances a query with security constraints based on the provided context.
   *
   * <p>This method is the main entry point for query enhancement. It: - Validates the input query
   * and context - Determines whether the query has existing label selectors - Routes to appropriate
   * enhancement strategy - Handles errors gracefully with detailed logging
   *
   * @param context The query context containing the original query, label constraints, and language
   * @return EnhancementResult with the enhanced query and any added constraints
   * @throws SecurityException if security validation fails
   */
  public static EnhancementResult enhanceQuery(QueryContext context) {
    try {
      String originalQuery = context.getOriginalQuery();

      // Handle null or empty queries
      if (originalQuery == null) {
        log.warn("QueryEnhancementProcessor: Null query provided, returning safe empty query");
        // For null queries, add constraints to make them safe
        return enhanceQueryWithoutLabels(context);
      }

      if (!ValidationUtils.isNotNullOrEmpty(originalQuery)) {
        log.warn("QueryEnhancementProcessor: Empty query provided for enhancement");
        // For empty queries, add constraints to make them safe
        return enhanceQueryWithoutLabels(context);
      }

      LabelAccessValidator.validateQueryEnhancementInputs(
          originalQuery, context.getLabelConstraints());

      log.debug("QueryEnhancementProcessor: Enhancing query '{}' with constraints", originalQuery);

      if (LabelProcessor.hasLabelSelectors(originalQuery)) {
        EnhancementResult result = enhanceQueryWithExistingLabels(context);
        log.debug(
            "QueryEnhancementProcessor: Query enhancement with existing labels completed, success: {}",
            result.isSuccess());
        return result;
      } else {
        // For queries without label selectors, add constraints to make them safe
        EnhancementResult result = enhanceQueryWithoutLabels(context);
        log.debug(
            "QueryEnhancementProcessor: Query enhancement without labels completed, success: {}",
            result.isSuccess());
        return result;
      }

    } catch (SecurityException e) {
      // Re-throw SecurityException to maintain security behavior
      throw e;
    } catch (Exception e) {
      log.error(
          "QueryEnhancementProcessor: Failed to enhance query: {}", context.getOriginalQuery(), e);
      return EnhancementResult.failure("Failed to enhance query: " + e.getMessage());
    }
  }

  /**
   * Enhances a query that already contains label selectors by applying security constraints.
   *
   * <p>This method processes queries with existing labels by: - Parsing and validating existing
   * label selectors - Applying security constraints to each label - Adding missing constraints that
   * should be enforced - Combining enhanced labels into a final query
   *
   * @param context The query context containing the original query and label constraints
   * @return EnhancementResult with the enhanced query and any added constraints
   */
  private static EnhancementResult enhanceQueryWithExistingLabels(QueryContext context) {
    String query = context.getOriginalQuery();
    QuerySyntax syntax = QuerySyntax.forLanguage(context.getLanguage());
    List<String> addedConstraints = new ArrayList<>();

    log.debug(LOG_QUERY_ENHANCEMENT, query, query); // Will be updated after enhancement

    // Process existing labels and build enhanced query
    LabelProcessingResult labelResult =
        processExistingLabels(query, context.getLabelConstraints(), syntax);
    String enhancedQuery = labelResult.getEnhancedQuery();
    addedConstraints.addAll(labelResult.getAddedConstraints());

    // Add any missing constraints that should be enforced
    enhancedQuery =
        addMissingConstraints(
            enhancedQuery, context.getLabelConstraints(), addedConstraints, syntax);

    log.debug(LOG_QUERY_ENHANCEMENT, query, enhancedQuery);

    return EnhancementResult.success(enhancedQuery, addedConstraints);
  }

  /** Processes existing labels in the query and returns the enhanced result */
  private static LabelProcessingResult processExistingLabels(
      String query, Map<String, Set<String>> labelConstraints, QuerySyntax syntax) {
    List<String> addedConstraints = new ArrayList<>();
    Map<String, String> processedLabelsCache = new HashMap<>();

    String enhancedQuery =
        StringParser.replaceLabelSections(
            query,
            innerContent ->
                processSingleLabelSection(
                    innerContent,
                    labelConstraints,
                    processedLabelsCache,
                    addedConstraints,
                    syntax));

    return new LabelProcessingResult(enhancedQuery, addedConstraints);
  }

  /** Processes a single label section, using cache if available */
  private static String processSingleLabelSection(
      String existingLabels,
      Map<String, Set<String>> labelConstraints,
      Map<String, String> processedLabelsCache,
      List<String> addedConstraints,
      QuerySyntax syntax) {
    String enhancedLabels = processedLabelsCache.get(existingLabels);

    if (enhancedLabels == null) {
      enhancedLabels =
          enhanceLabelSection(existingLabels, labelConstraints, addedConstraints, syntax);
      processedLabelsCache.put(existingLabels, enhancedLabels);
    } else {
      log.debug(
          "QueryEnhancementProcessor: Using cached result for label section: {}", existingLabels);
    }

    return enhancedLabels;
  }

  /** Enhances a single label section with error handling */
  private static String enhanceLabelSection(
      String existingLabels,
      Map<String, Set<String>> labelConstraints,
      List<String> addedConstraints,
      QuerySyntax syntax) {
    try {
      EnhancementResult labelResult =
          LabelProcessor.enhanceLabelsSection(existingLabels, labelConstraints, syntax);

      if (labelResult.isSuccess()) {
        addedConstraints.addAll(labelResult.getAddedConstraints());
        return labelResult.getEnhancedQuery();
      } else {
        log.warn("QueryEnhancementProcessor: Failed to enhance labels section: {}", existingLabels);
        return existingLabels; // Keep original labels if enhancement fails
      }

    } catch (SecurityException e) {
      throw e;
    } catch (Exception e) {
      log.warn(
          "QueryEnhancementProcessor: Exception while enhancing labels section: {}",
          existingLabels,
          e);
      return existingLabels; // Keep original labels if enhancement fails
    }
  }

  /** Adds missing constraints to the enhanced query */
  private static String addMissingConstraints(
      String enhancedQuery,
      Map<String, Set<String>> labelConstraints,
      List<String> addedConstraints,
      QuerySyntax syntax) {
    List<String> missingConstraints = buildRequiredConstraints(labelConstraints);
    if (missingConstraints.isEmpty()) {
      return enhancedQuery;
    }

    List<String> constraintsToAdd = filterMissingConstraints(enhancedQuery, missingConstraints);
    if (!constraintsToAdd.isEmpty()) {
      String constraintsString = String.join(syntax.separator(), constraintsToAdd);

      enhancedQuery =
          StringParser.replaceFirstLabelSection(
              enhancedQuery,
              existingContent -> {
                String trimmedContent = existingContent.trim();
                if (trimmedContent.isEmpty()
                    || trimmedContent.endsWith(syntax.separator().trim())) {
                  return existingContent + constraintsString;
                } else {
                  return existingContent + syntax.separator() + constraintsString;
                }
              });

      addedConstraints.addAll(constraintsToAdd);
      log.debug(
          "QueryEnhancementProcessor: Added missing constraints to existing query: {}",
          constraintsToAdd);
    }

    return enhancedQuery;
  }

  /** Filters out constraints that are already present in the enhanced query */
  private static List<String> filterMissingConstraints(
      String enhancedQuery, List<String> missingConstraints) {
    return missingConstraints.stream()
        .filter(constraint -> !enhancedQuery.contains(constraint.split("=")[0] + "="))
        .toList();
  }

  /**
   * Enhances a query that doesn't have label selectors by adding constraints
   *
   * @param context The query context
   * @return EnhancementResult with the enhanced query
   */
  private static EnhancementResult enhanceQueryWithoutLabels(QueryContext context) {
    String query = context.getOriginalQuery();
    Map<String, Set<String>> labelConstraints = context.getLabelConstraints();

    log.debug(LOG_QUERY_ENHANCEMENT, query, query); // Will be updated after enhancement

    // Build constraints for labels that should be enforced
    List<String> requiredConstraints = buildRequiredConstraints(labelConstraints);

    if (requiredConstraints.isEmpty()) {
      // No constraints to add, return original query or {} for empty queries
      String resultQuery = (query == null || query.trim().isEmpty()) ? "{}" : query;
      log.debug("QueryEnhancementProcessor: No constraints to add, returning: {}", resultQuery);
      return EnhancementResult.success(resultQuery, List.of());
    }

    // For queries without labels, we need to insert constraints based on the query language
    String enhancedQuery =
        insertConstraintsInQuery(query, requiredConstraints, context.getLanguage());

    log.debug(LOG_QUERY_ENHANCEMENT, query, enhancedQuery);
    log.debug(
        "QueryEnhancementProcessor: Added constraints to query without labels: '{}' -> '{}'",
        query,
        enhancedQuery);

    return EnhancementResult.success(enhancedQuery, requiredConstraints);
  }

  /**
   * Inserts label constraints into a query based on the query language
   *
   * @param query The original query
   * @param constraints The constraints to insert
   * @param language The query language (PROMQL, LOGQL, TRACEQL)
   * @return The enhanced query with constraints inserted correctly
   */
  private static String insertConstraintsInQuery(
      String query, List<String> constraints, QueryContext.QueryLanguage language) {
    if (constraints.isEmpty()) {
      return query != null ? query : "";
    }

    QuerySyntax syntax = QuerySyntax.forLanguage(language);
    String constraintsString = String.join(syntax.separator(), constraints);

    // Handle null or empty query
    if (query == null || query.trim().isEmpty()) {
      if (constraints.isEmpty()) {
        return "{}";
      }
      return "{" + constraintsString + "}";
    }

    // Handle different query languages
    return switch (language) {
      case PROMQL -> insertConstraintsInPromQLQuery(query, constraints);
      case LOGQL, TRACEQL -> insertConstraintsInLogQLOrTraceQLQuery(query, constraints, syntax);
    };
  }

  /**
   * Inserts label constraints into LogQL or TraceQL queries at the correct position. For queries
   * without existing labels, constraints are added as a prefix selector.
   *
   * @param query The original query
   * @param constraints The constraints to insert
   * @param syntax The query syntax configuration
   * @return The enhanced query with constraints inserted correctly
   */
  private static String insertConstraintsInLogQLOrTraceQLQuery(
      String query, List<String> constraints, QuerySyntax syntax) {
    String preprocessed = preprocessQueryConstraints(query, constraints, syntax);

    // If preprocessing handled the case (empty constraints or null/empty query), return as-is
    if (constraints.isEmpty() || query == null || query.trim().isEmpty()) {
      return preprocessed;
    }

    // For LogQL/TraceQL queries without existing labels, add constraints as a prefix selector
    // This is the correct syntax: {constraints} query
    return "{" + preprocessed + "} " + query;
  }

  /**
   * Inserts label constraints into a PromQL query at the correct position. Handles queries with
   * 'by', 'group_left', 'group_right', etc. clauses.
   *
   * @param query The original query
   * @param constraints The constraints to insert
   * @return The enhanced query with constraints inserted correctly
   */
  private static String insertConstraintsInPromQLQuery(String query, List<String> constraints) {
    String preprocessed =
        preprocessQueryConstraints(
            query, constraints, QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL));

    // If preprocessing handled the case (empty constraints or null/empty query), return as-is
    if (constraints.isEmpty() || query == null || query.trim().isEmpty()) {
      return preprocessed;
    }

    // Try to find metric names followed by [ (range vector without labels).
    // Insert {constraints} between the metric name and [range] selector.
    // Example: rate(metric[5m]) -> rate(metric{constraints}[5m])
    java.util.regex.Matcher metricMatcher = METRIC_RANGE_PATTERN.matcher(query);
    if (metricMatcher.find()) {
      StringBuilder sb = new StringBuilder();
      metricMatcher.reset();
      while (metricMatcher.find()) {
        String replacement = metricMatcher.group(1) + "{" + preprocessed + "}[";
        metricMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
      }
      metricMatcher.appendTail(sb);
      return sb.toString();
    }

    // Try to insert constraints before clauses like 'by', 'group_left', etc.
    String result = insertConstraintsBeforeClause(query, preprocessed);
    if (result != null) {
      return result;
    }

    // For simple queries, check if it ends with closing parenthesis (function call)
    if (query.trim().endsWith(")")) {
      // Insert inside function call: metric() -> metric{constraints})
      return query.substring(0, query.length() - 1) + "{" + preprocessed + "})";
    } else {
      // Simple case: just append constraints
      return query + "{" + preprocessed + "}";
    }
  }

  /**
   * Attempts to insert constraints before specific clauses in PromQL queries
   *
   * @param query The original query
   * @param preprocessed The preprocessed constraints string
   * @return The modified query, or null if no clause was found
   */
  private static String insertConstraintsBeforeClause(String query, String preprocessed) {
    String[] clauses = {" by ", " group_left", " group_right", " offset "};

    for (String clause : clauses) {
      if (query.contains(clause)) {
        int clauseIndex = query.indexOf(clause);
        int closingParenIndex = query.lastIndexOf(")", clauseIndex);
        if (closingParenIndex > 0) {
          return query.substring(0, closingParenIndex)
              + "{"
              + preprocessed
              + "}"
              + query.substring(closingParenIndex);
        }
      }
    }

    return null;
  }

  /**
   * Handles common validation and preprocessing for query constraint insertion
   *
   * @param query The original query
   * @param constraints The constraints to insert
   * @param syntax The query syntax configuration
   * @return The constraints string, or the original query if no constraints to add
   */
  private static String preprocessQueryConstraints(
      String query, List<String> constraints, QuerySyntax syntax) {
    if (constraints.isEmpty()) {
      return query != null ? query : "";
    }

    String constraintsString = String.join(syntax.separator(), constraints);

    // Handle null or empty query - return constraints wrapped in braces
    if (query == null || query.trim().isEmpty()) {
      return "{" + constraintsString + "}";
    }

    // Return the constraints string for further processing
    return constraintsString;
  }

  /**
   * Builds required constraints for labels that should be enforced Delegates to ConstraintBuilder
   * for consistent constraint building logic
   *
   * @param labelConstraints The full set of label constraints
   * @return List of constraint expressions to add
   */
  private static List<String> buildRequiredConstraints(Map<String, Set<String>> labelConstraints) {
    // For queries without existing labels, we want ALL required constraints (pass empty set)
    Set<String> noExistingLabels = Set.of();
    List<String> constraints =
        QueryEnhancementBuilder.buildMissingConstraints(noExistingLabels, labelConstraints);

    log.debug(
        "QueryEnhancementProcessor: Built {} required constraints: {}",
        constraints.size(),
        constraints);
    return constraints;
  }

  /** Result class for label processing */
  private static class LabelProcessingResult {
    private final String enhancedQuery;
    private final List<String> addedConstraints;

    public LabelProcessingResult(String enhancedQuery, List<String> addedConstraints) {
      this.enhancedQuery = enhancedQuery;
      this.addedConstraints = addedConstraints;
    }

    public String getEnhancedQuery() {
      return enhancedQuery;
    }

    public List<String> getAddedConstraints() {
      return addedConstraints;
    }
  }
}
