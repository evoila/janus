package com.evoila.janus.common.enforcement.utils;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for common validation patterns used throughout the codebase. Consolidates repeated
 * validation logic to improve maintainability and consistency.
 */
@Slf4j
public final class ValidationUtils {

  private ValidationUtils() {
    // Utility class - prevent instantiation
  }

  /**
   * Validates that a string is not null or empty (after trimming)
   *
   * @param value the string to validate
   * @param fieldName the name of the field for error messages
   * @throws IllegalArgumentException if the string is null or empty
   */
  public static void validateNotNullOrEmpty(String value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " cannot be null");
    }
    if (value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " cannot be empty");
    }
  }

  /**
   * Validates that a map is not null or empty
   *
   * @param map the map to validate
   * @param fieldName the name of the field for error messages
   * @throws IllegalArgumentException if the map is null or empty
   */
  public static void validateNotNullOrEmpty(Map<?, ?> map, String fieldName) {
    if (map == null) {
      throw new IllegalArgumentException(fieldName + " cannot be null");
    }
    if (map.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " cannot be empty");
    }
  }

  /**
   * Validates that a string is not null or empty (after trimming) Returns true if valid, false if
   * null or empty
   *
   * @param value the string to validate
   * @return true if the string is not null and not empty, false otherwise
   */
  public static boolean isNotNullOrEmpty(String value) {
    return value != null && !value.trim().isEmpty();
  }

  /**
   * Validates that a string contains valid characters for label names
   *
   * @param labelName the label name to validate
   * @throws IllegalArgumentException if the label name contains invalid characters
   */
  public static void validateLabelNameCharacters(String labelName) {
    if (labelName == null) {
      throw new IllegalArgumentException("Label name cannot be null");
    }

    if (labelName.contains(" ") || labelName.contains("\"") || labelName.contains("'")) {
      throw new IllegalArgumentException("Label name contains invalid characters: " + labelName);
    }
  }
}
