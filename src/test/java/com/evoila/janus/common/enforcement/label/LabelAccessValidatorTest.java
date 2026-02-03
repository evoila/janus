package com.evoila.janus.common.enforcement.label;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LabelAccessValidator Tests")
class LabelAccessValidatorTest {

  // ========================================================================
  // validateQueryEnhancementInputs
  // ========================================================================

  @Nested
  @DisplayName("validateQueryEnhancementInputs")
  class ValidateQueryEnhancementInputs {

    @Test
    @DisplayName("Should accept valid inputs")
    void validInputs() {
      assertDoesNotThrow(
          () ->
              LabelAccessValidator.validateQueryEnhancementInputs(
                  "up{}", Map.of("namespace", Set.of("demo"))));
    }

    @Test
    @DisplayName("Should reject null query")
    void nullQuery() {
      Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));
      assertThrows(
          IllegalArgumentException.class,
          () -> LabelAccessValidator.validateQueryEnhancementInputs(null, constraints));
    }

    @Test
    @DisplayName("Should reject null constraints")
    void nullConstraints() {
      assertThrows(
          IllegalArgumentException.class,
          () -> LabelAccessValidator.validateQueryEnhancementInputs("up{}", null));
    }
  }

  // ========================================================================
  // validateLabelName
  // ========================================================================

  @Nested
  @DisplayName("validateLabelName")
  class ValidateLabelName {

    @Test
    @DisplayName("Should accept valid label name")
    void validName() {
      assertDoesNotThrow(() -> LabelAccessValidator.validateLabelName("namespace"));
    }

    @Test
    @DisplayName("Should accept configuration key as label name")
    void configurationKey() {
      assertDoesNotThrow(() -> LabelAccessValidator.validateLabelName("labels"));
    }

    @Test
    @DisplayName("Should reject null label name")
    void nullName() {
      assertThrows(
          IllegalArgumentException.class, () -> LabelAccessValidator.validateLabelName(null));
    }

    @Test
    @DisplayName("Should reject empty label name")
    void emptyName() {
      assertThrows(
          IllegalArgumentException.class, () -> LabelAccessValidator.validateLabelName(""));
    }
  }

  // ========================================================================
  // validateLabelValues
  // ========================================================================

  @Nested
  @DisplayName("validateLabelValues")
  class ValidateLabelValues {

    @Test
    @DisplayName("Should accept valid label values")
    void validValues() {
      assertDoesNotThrow(
          () -> LabelAccessValidator.validateLabelValues(Set.of("demo", "observability")));
    }

    @Test
    @DisplayName("Should accept empty set")
    void emptySet() {
      assertDoesNotThrow(() -> LabelAccessValidator.validateLabelValues(Set.of()));
    }

    @Test
    @DisplayName("Should accept wildcard values")
    void wildcardValues() {
      assertDoesNotThrow(() -> LabelAccessValidator.validateLabelValues(Set.of("*", ".+")));
    }

    @Test
    @DisplayName("Should accept empty string value")
    void emptyStringValue() {
      assertDoesNotThrow(() -> LabelAccessValidator.validateLabelValues(Set.of("")));
    }

    @Test
    @DisplayName("Should reject null set")
    void nullSet() {
      assertThrows(
          IllegalArgumentException.class, () -> LabelAccessValidator.validateLabelValues(null));
    }
  }

  // ========================================================================
  // isLabelAccessAllowed
  // ========================================================================

  @Nested
  @DisplayName("isLabelAccessAllowed")
  class IsLabelAccessAllowed {

    @Test
    @DisplayName("Should allow access with wildcard labels")
    void wildcardAccess() {
      Map<String, Set<String>> constraints = Map.of("labels", Set.of("*"));
      assertTrue(LabelAccessValidator.isLabelAccessAllowed(constraints, "any_label"));
    }

    @Test
    @DisplayName("Should allow access for explicitly listed label")
    void explicitLabel() {
      Map<String, Set<String>> constraints = Map.of("labels", Set.of("namespace", "service"));
      assertTrue(LabelAccessValidator.isLabelAccessAllowed(constraints, "namespace"));
    }

    @Test
    @DisplayName("Should deny access for unlisted label")
    void unlistedLabel() {
      Map<String, Set<String>> constraints = Map.of("labels", Set.of("namespace"));
      assertFalse(LabelAccessValidator.isLabelAccessAllowed(constraints, "secret"));
    }

    @Test
    @DisplayName("Should deny access when no labels key exists")
    void noLabelsKey() {
      Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));
      assertFalse(LabelAccessValidator.isLabelAccessAllowed(constraints, "namespace"));
    }

    @Test
    @DisplayName("Should deny access for null constraints")
    void nullConstraints() {
      assertFalse(LabelAccessValidator.isLabelAccessAllowed(null, "namespace"));
    }

    @Test
    @DisplayName("Should deny access for null label name")
    void nullLabelName() {
      assertFalse(LabelAccessValidator.isLabelAccessAllowed(Map.of("labels", Set.of("*")), null));
    }

    @Test
    @DisplayName("Should deny access for excluded label even with wildcard")
    void excludedLabel() {
      Map<String, Set<String>> constraints = Map.of("labels", Set.of("*"));
      Set<String> excluded = Set.of("secret_label");
      assertFalse(LabelAccessValidator.isLabelAccessAllowed(constraints, "secret_label", excluded));
    }

    @Test
    @DisplayName("Should allow non-excluded label with wildcard")
    void nonExcludedLabel() {
      Map<String, Set<String>> constraints = Map.of("labels", Set.of("*"));
      Set<String> excluded = Set.of("secret_label");
      assertTrue(LabelAccessValidator.isLabelAccessAllowed(constraints, "namespace", excluded));
    }
  }

  // ========================================================================
  // isLabelValueAccessAllowed
  // ========================================================================

  @Nested
  @DisplayName("isLabelValueAccessAllowed")
  class IsLabelValueAccessAllowed {

    @Test
    @DisplayName("Should allow any value when no constraints for label")
    void noConstraints() {
      Map<String, Set<String>> constraints = Map.of("other", Set.of("val"));
      assertTrue(
          LabelAccessValidator.isLabelValueAccessAllowed(constraints, "namespace", "anything"));
    }

    @Test
    @DisplayName("Should allow any value with wildcard asterisk")
    void wildcardAsterisk() {
      Map<String, Set<String>> constraints = Map.of("namespace", Set.of("*"));
      assertTrue(
          LabelAccessValidator.isLabelValueAccessAllowed(constraints, "namespace", "anything"));
    }

    @Test
    @DisplayName("Should allow any value with .* pattern")
    void dotStarPattern() {
      Map<String, Set<String>> constraints = Map.of("namespace", Set.of(".*"));
      assertTrue(
          LabelAccessValidator.isLabelValueAccessAllowed(constraints, "namespace", "anything"));
    }

    @Test
    @DisplayName("Should allow any value with .+ pattern")
    void dotPlusPattern() {
      Map<String, Set<String>> constraints = Map.of("namespace", Set.of(".+"));
      assertTrue(
          LabelAccessValidator.isLabelValueAccessAllowed(constraints, "namespace", "anything"));
    }

    @Test
    @DisplayName("Should allow exact match")
    void exactMatch() {
      Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo", "prod"));
      assertTrue(LabelAccessValidator.isLabelValueAccessAllowed(constraints, "namespace", "demo"));
    }

    @Test
    @DisplayName("Should deny value not in allowed set")
    void notAllowed() {
      Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo", "prod"));
      assertFalse(
          LabelAccessValidator.isLabelValueAccessAllowed(constraints, "namespace", "staging"));
    }

    @Test
    @DisplayName("Should allow suffix wildcard match")
    void suffixWildcard() {
      Map<String, Set<String>> constraints = Map.of("service", Set.of("*-service"));
      assertTrue(
          LabelAccessValidator.isLabelValueAccessAllowed(constraints, "service", "order-service"));
    }

    @Test
    @DisplayName("Should allow prefix wildcard match")
    void prefixWildcard() {
      Map<String, Set<String>> constraints = Map.of("service", Set.of("order-*"));
      assertTrue(
          LabelAccessValidator.isLabelValueAccessAllowed(constraints, "service", "order-service"));
    }

    @Test
    @DisplayName("Should allow middle wildcard match")
    void middleWildcard() {
      Map<String, Set<String>> constraints = Map.of("service", Set.of("order*service"));
      assertTrue(
          LabelAccessValidator.isLabelValueAccessAllowed(
              constraints, "service", "order-micro-service"));
    }

    @Test
    @DisplayName("Should deny when wildcard doesn't match")
    void wildcardNoMatch() {
      Map<String, Set<String>> constraints = Map.of("service", Set.of("order-*"));
      assertFalse(
          LabelAccessValidator.isLabelValueAccessAllowed(constraints, "service", "stock-service"));
    }

    @Test
    @DisplayName("Should deny for null constraints")
    void nullConstraints() {
      assertFalse(LabelAccessValidator.isLabelValueAccessAllowed(null, "ns", "demo"));
    }

    @Test
    @DisplayName("Should deny for null label name")
    void nullLabelName() {
      assertFalse(
          LabelAccessValidator.isLabelValueAccessAllowed(
              Map.of("ns", Set.of("demo")), null, "demo"));
    }
  }
}
