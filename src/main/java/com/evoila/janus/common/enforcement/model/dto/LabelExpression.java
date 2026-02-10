package com.evoila.janus.common.enforcement.model.dto;

/**
 * Unified intermediate representation for a single label expression in a query.
 *
 * <p>Carries a label through the entire processing pipeline (parse → normalize → enhance → validate
 * → serialize) without requiring string round-trips between stages.
 *
 * @param name The label name (e.g., "namespace", "resource.service.name")
 * @param operator The operator (e.g., "=", "!=", "=~", "!~", "<", ">")
 * @param value The label value (e.g., "demo", ".*", "order-service")
 * @param quoted Whether the value was originally quoted
 * @param originalText The original unparsed text, used for pass-through when no modification is
 *     needed. Set to null when the expression has been modified.
 * @param passthrough If true, this expression is preserved as-is without enforcement (e.g.,
 *     TraceQL intrinsics or passthrough keywords like "true", "false")
 */
public record LabelExpression(
    String name,
    String operator,
    String value,
    boolean quoted,
    String originalText,
    boolean passthrough) {

  /** Creates a standard label expression from parsed components. */
  public LabelExpression(String name, String operator, String value, boolean quoted,
      String originalText) {
    this(name, operator, value, quoted, originalText, false);
  }

  /** Creates a passthrough expression that will not be enforced. */
  public static LabelExpression passthrough(String originalText) {
    return new LabelExpression(originalText, "", "", false, originalText, true);
  }

  /**
   * Serializes the expression back to string form. If nothing was modified (originalText is
   * present), the original text is returned to preserve formatting.
   */
  public String serialize() {
    if (originalText != null) {
      return originalText;
    }
    String q = quoted ? "\"" : "";
    return name + operator + q + value + q;
  }

  /** Returns a copy with a new operator and value. Clears originalText to force serialization. */
  public LabelExpression withOperatorAndValue(String newOperator, String newValue) {
    return new LabelExpression(name, newOperator, newValue, quoted, null, passthrough);
  }

  /** Returns a copy with a new value. Clears originalText to force serialization. */
  public LabelExpression withValue(String newValue) {
    return new LabelExpression(name, operator, newValue, quoted, null, passthrough);
  }

  /** Returns a copy with a new operator. Clears originalText to force serialization. */
  public LabelExpression withOperator(String newOperator) {
    return new LabelExpression(name, newOperator, value, quoted, null, passthrough);
  }
}
