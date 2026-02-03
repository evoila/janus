package com.evoila.janus.common.enforcement.model.dto;

import java.util.Set;

/**
 * Data class for enhancement operations containing all information needed for label processing.
 *
 * <p>This record encapsulates complete label enhancement context: - labelName: The name of the
 * label being processed - value: The current value of the label - operator: The operator being
 * applied (=, !=, =~, !~) - allowedValues: Set of values allowed by security constraints -
 * isValueWildcard: Whether the current value is a wildcard - hasWildcardConstraints: Whether
 * constraints contain wildcards - hasSpecificConstraints: Whether specific constraints are defined
 *
 * <p>Used by OperatorHandlers to make enhancement decisions based on the complete context of the
 * label and its constraints.
 */
public record EnhancementData(
    String labelName,
    String value,
    String operator,
    Set<String> allowedValues,
    boolean isValueWildcard,
    boolean hasWildcardConstraints,
    boolean hasSpecificConstraints,
    boolean quoted,
    String originalText) {

  /**
   * Returns the original text if available, otherwise reconstructs the label expression from its
   * components. Use this in pass-through branches where the label is not being modified.
   */
  public String originalOrReconstructed() {
    if (originalText != null) {
      return originalText;
    }
    String q = quoted ? "\"" : "";
    return labelName + operator + q + value + q;
  }
}
