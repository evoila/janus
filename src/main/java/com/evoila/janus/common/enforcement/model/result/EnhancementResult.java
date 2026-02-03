package com.evoila.janus.common.enforcement.model.result;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Result object that encapsulates the outcome of query enhancement operations.
 *
 * <p>This class provides comprehensive information about enhancement results: - Success/failure
 * status with detailed error messages - Enhanced query string with applied constraints - Lists of
 * added and removed constraints for tracking - Factory methods for creating success and failure
 * results
 *
 * <p>Used throughout the enforcement pipeline to track enhancement progress and provide detailed
 * feedback for debugging and monitoring.
 */
@Getter
@Builder
public class EnhancementResult {
  private static final String DEFAULT_ENHANCED_QUERY = "";
  private static final List<String> DEFAULT_ADDED_CONSTRAINTS = new ArrayList<>();
  private static final List<String> DEFAULT_REMOVED_CONSTRAINTS = new ArrayList<>();
  private static final boolean DEFAULT_SUCCESS = true;
  private static final String DEFAULT_ERROR_MESSAGE = "";

  /**
   * -- GETTER -- Gets the enhanced query string with applied security constraints.
   *
   * @return The enhanced query string, or empty string if enhancement failed
   */
  private final String enhancedQuery;

  /**
   * -- GETTER -- Gets the list of constraints that were added during enhancement.
   *
   * @return List of added constraint strings, or empty list if none were added
   */
  private final List<String> addedConstraints;

  /**
   * -- GETTER -- Gets the list of constraints that were removed during enhancement.
   *
   * @return List of removed constraint strings, or empty list if none were removed
   */
  private final List<String> removedConstraints;

  /**
   * -- GETTER -- Checks if the enhancement operation was successful.
   *
   * @return true if enhancement succeeded, false otherwise
   */
  private final boolean success;

  /**
   * -- GETTER -- Gets the error message if enhancement failed.
   *
   * @return The error message describing the failure, or empty string if successful
   */
  private final String errorMessage;

  /**
   * Creates a successful enhancement result with the enhanced query and added constraints.
   *
   * @param enhancedQuery The enhanced query string with applied constraints
   * @param addedConstraints List of constraints that were added during enhancement
   * @return EnhancementResult with success status and enhancement details
   */
  public static EnhancementResult success(String enhancedQuery, List<String> addedConstraints) {
    return EnhancementResult.builder()
        .enhancedQuery(enhancedQuery != null ? enhancedQuery : DEFAULT_ENHANCED_QUERY)
        .addedConstraints(addedConstraints != null ? addedConstraints : DEFAULT_ADDED_CONSTRAINTS)
        .removedConstraints(DEFAULT_REMOVED_CONSTRAINTS)
        .success(DEFAULT_SUCCESS)
        .errorMessage(DEFAULT_ERROR_MESSAGE)
        .build();
  }

  /**
   * Creates a failed enhancement result with an error message.
   *
   * @param errorMessage The error message describing why enhancement failed
   * @return EnhancementResult with failure status and error details
   */
  public static EnhancementResult failure(String errorMessage) {
    return EnhancementResult.builder()
        .enhancedQuery(DEFAULT_ENHANCED_QUERY)
        .addedConstraints(DEFAULT_ADDED_CONSTRAINTS)
        .removedConstraints(DEFAULT_REMOVED_CONSTRAINTS)
        .success(false)
        .errorMessage(errorMessage != null ? errorMessage : DEFAULT_ERROR_MESSAGE)
        .build();
  }
}
