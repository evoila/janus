package com.evoila.janus.common.enforcement;

import com.evoila.janus.common.config.ServiceType;
import com.evoila.janus.common.config.ServiceTypeMapper;
import com.evoila.janus.common.enforcement.label.LabelAccessValidator;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Custom exception for query enhancement failures. Provides more specific error handling than
 * generic RuntimeException.
 */
class QueryEnhancementException extends RuntimeException {

  public QueryEnhancementException(String message) {
    super(message);
  }
}

/**
 * Main entry point for all label constraint enforcement operations.
 *
 * <p>This is the primary interface used by all enforcement services: - RequestProcessingService
 * (GET and POST requests) - Service-specific enhancers (Thanos, Loki, Tempo) - Tests and
 * specialized operations
 *
 * <p>Provides unified, traceable enforcement flow for all query types and services. Orchestrates
 * the complete enforcement process with clear logging and error handling. Supports multiple query
 * languages (PromQL, LogQL, TraceQL) and different service types.
 */
@Slf4j
@Component
public class QueryEnforcementFlow {

  /**
   * Enhances a query with explicit, traceable enforcement flow for production use.
   *
   * <p>This method provides a step-by-step enforcement process with detailed logging: - Validates
   * input parameters and constraints - Builds query context with appropriate language - Processes
   * query through enhancement pipeline - Handles success/failure with proper error handling
   *
   * @param query The original query to enhance (e.g., "up", "metric{namespace='demo'}")
   * @param serviceType The service type (loki, thanos, tempo)
   * @param labelConstraints The label constraints to apply (label name -> allowed values)
   * @return The enhanced query with security constraints
   * @throws QueryEnhancementException if enhancement fails
   */
  public String enhanceQueryWithExplicitFlow(
      String query, String serviceType, Map<String, Set<String>> labelConstraints) {
    log.debug("Query enforcement flow for {} - query: '{}'", serviceType.toUpperCase(), query);

    // Step 1: Input validation
    log.debug("Step 1: Validating inputs");
    LabelAccessValidator.validateQueryEnhancementInputs(query, labelConstraints);

    // Step 2: Query processing
    log.debug("Step 2: Processing query");
    QueryContext context = buildQueryContext(query, labelConstraints, serviceType);
    EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

    // Step 3: Handle result
    log.debug("Step 3: Handling result - success: {}", result.isSuccess());
    if (result.isSuccess()) {
      String enhancedQuery = result.getEnhancedQuery();
      log.debug("Enhanced query: '{}'", enhancedQuery);
      return enhancedQuery;
    } else {
      String errorMessage = "Failed to enhance query: " + result.getErrorMessage();
      log.error("Query enhancement failed: {}", errorMessage);
      throw new QueryEnhancementException(errorMessage);
    }
  }

  /**
   * Enhances a query using the security enforcement system for testing and specialized operations.
   *
   * <p>This method is designed for testing and specialized use cases where detailed enhancement
   * information is needed. It returns an EnhancementResult containing success status, enhanced
   * query, and any added constraints.
   *
   * @param context The query context containing the original query, label constraints, and language
   * @return EnhancementResult with detailed information about the enhancement process
   */
  public EnhancementResult enhanceQuery(QueryContext context) {
    log.info("QueryEnforcementFlow: Enhancing query '{}'", context.getOriginalQuery());
    EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);
    log.info("QueryEnforcementFlow: Query enhancement result success: {}", result.isSuccess());
    return result;
  }

  /**
   * Builds a QueryContext for the given service type and parameters.
   *
   * <p>This method determines the appropriate query language based on the service type and creates
   * a properly configured QueryContext for the enhancement process.
   *
   * @param query The original query to enhance
   * @param labelConstraints The label constraints to apply (label name -> allowed values)
   * @param serviceType The service type (loki, thanos, tempo)
   * @return Configured QueryContext with appropriate language and constraints
   */
  private QueryContext buildQueryContext(
      String query, Map<String, Set<String>> labelConstraints, String serviceType) {
    QueryContext.QueryLanguage language = determineQueryLanguage(serviceType);

    log.debug("Building QueryContext for service: {} with language: {}", serviceType, language);

    return QueryContext.builder()
        .originalQuery(query)
        .labelConstraints(labelConstraints)
        .language(language)
        .build();
  }

  /**
   * Determines the appropriate query language based on the service type.
   *
   * @param serviceName The service name (loki, thanos, tempo)
   * @return QueryLanguage enum value corresponding to the service type
   */
  private QueryContext.QueryLanguage determineQueryLanguage(String serviceName) {
    ServiceType serviceType = ServiceType.fromString(serviceName);
    return ServiceTypeMapper.getQueryLanguage(serviceType);
  }
}
