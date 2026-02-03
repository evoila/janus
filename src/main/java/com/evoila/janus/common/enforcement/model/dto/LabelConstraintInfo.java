package com.evoila.janus.common.enforcement.model.dto;

/**
 * Immutable record holding parsed label constraint information.
 *
 * <p>This record encapsulates a single label constraint with: - value: The label value (e.g.,
 * "demo", "*", "5..") - operator: The constraint operator (e.g., "=", "!=", "=~", "!~") - quoted:
 * Whether the value was originally quoted in the query - originalText: The original unparsed text
 * of the label pair, used to preserve formatting when no modification is needed
 *
 * <p>The quoted flag is critical for TraceQL support where intrinsic enum values (e.g., status=ok)
 * must remain unquoted, while string values (e.g., name="lets-go") must stay quoted.
 */
public record LabelConstraintInfo(
    String value, String operator, boolean quoted, String originalText) {
  public LabelConstraintInfo {
    if (value == null) value = "";
    if (operator == null) operator = "=";
  }

  /** Backward-compatible constructor — defaults to quoted=true (PromQL/LogQL behavior) */
  public LabelConstraintInfo(String value, String operator) {
    this(value, operator, true, null);
  }

  /** Constructor without originalText — defaults to null */
  public LabelConstraintInfo(String value, String operator, boolean quoted) {
    this(value, operator, quoted, null);
  }
}
