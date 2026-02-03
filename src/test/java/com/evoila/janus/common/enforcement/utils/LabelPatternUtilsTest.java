package com.evoila.janus.common.enforcement.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LabelPatternUtils Tests")
class LabelPatternUtilsTest {

  // ========================================================================
  // processConfigValue tests
  // ========================================================================

  @Nested
  @DisplayName("processConfigValue Tests")
  class ProcessConfigValueTests {

    @Test
    @DisplayName("Should strip ~ prefix from regex patterns")
    void testProcessConfigValue_withTildePrefix() {
      assertEquals("^demo.*$", LabelPatternUtils.processConfigValue("~^demo.*$"));
      assertEquals(".*-service$", LabelPatternUtils.processConfigValue("~.*-service$"));
      assertEquals("[a-z]+-service", LabelPatternUtils.processConfigValue("~[a-z]+-service"));
    }

    @Test
    @DisplayName("Should return value unchanged without ~ prefix")
    void testProcessConfigValue_withoutTildePrefix() {
      assertEquals("demo", LabelPatternUtils.processConfigValue("demo"));
      assertEquals("demo-*", LabelPatternUtils.processConfigValue("demo-*"));
      assertEquals("*", LabelPatternUtils.processConfigValue("*"));
    }

    @Test
    @DisplayName("Should handle null input")
    void testProcessConfigValue_nullInput() {
      assertNull(LabelPatternUtils.processConfigValue(null));
    }

    @Test
    @DisplayName("Should handle empty string")
    void testProcessConfigValue_emptyString() {
      assertEquals("", LabelPatternUtils.processConfigValue(""));
    }

    @Test
    @DisplayName("Should handle single ~ character")
    void testProcessConfigValue_singleTilde() {
      assertEquals("", LabelPatternUtils.processConfigValue("~"));
    }
  }

  // ========================================================================
  // hasExplicitRegexPrefix tests
  // ========================================================================

  @Nested
  @DisplayName("hasExplicitRegexPrefix Tests")
  class HasExplicitRegexPrefixTests {

    @Test
    @DisplayName("Should return true for values with ~ prefix")
    void testHasExplicitRegexPrefix_withTilde() {
      assertTrue(LabelPatternUtils.hasExplicitRegexPrefix("~^demo.*$"));
      assertTrue(LabelPatternUtils.hasExplicitRegexPrefix("~.*"));
      assertTrue(LabelPatternUtils.hasExplicitRegexPrefix("~"));
    }

    @Test
    @DisplayName("Should return false for values without ~ prefix")
    void testHasExplicitRegexPrefix_withoutTilde() {
      assertFalse(LabelPatternUtils.hasExplicitRegexPrefix("demo"));
      assertFalse(LabelPatternUtils.hasExplicitRegexPrefix("demo-*"));
      assertFalse(LabelPatternUtils.hasExplicitRegexPrefix("^demo.*$")); // No ~ prefix
    }

    @Test
    @DisplayName("Should return false for null input")
    void testHasExplicitRegexPrefix_nullInput() {
      assertFalse(LabelPatternUtils.hasExplicitRegexPrefix(null));
    }

    @Test
    @DisplayName("Should return false for empty string")
    void testHasExplicitRegexPrefix_emptyString() {
      assertFalse(LabelPatternUtils.hasExplicitRegexPrefix(""));
    }
  }

  // ========================================================================
  // isFullRegexPattern tests
  // ========================================================================

  @Nested
  @DisplayName("isFullRegexPattern Tests")
  class IsFullRegexPatternTests {

    @Test
    @DisplayName("Should return true for patterns with anchors")
    void testIsFullRegexPattern_withAnchors() {
      assertTrue(LabelPatternUtils.isFullRegexPattern("^demo.*$"));
      assertTrue(LabelPatternUtils.isFullRegexPattern("^demo"));
      assertTrue(LabelPatternUtils.isFullRegexPattern("demo$"));
    }

    @Test
    @DisplayName("Should return true for patterns with character classes")
    void testIsFullRegexPattern_withCharacterClasses() {
      assertTrue(LabelPatternUtils.isFullRegexPattern("[a-z]+-service"));
      assertTrue(LabelPatternUtils.isFullRegexPattern("[0-9]+"));
    }

    @Test
    @DisplayName("Should return true for patterns with groups and alternation")
    void testIsFullRegexPattern_withGroupsAndAlternation() {
      assertTrue(LabelPatternUtils.isFullRegexPattern("(foo|bar)"));
      assertTrue(LabelPatternUtils.isFullRegexPattern("a|b"));
    }

    @Test
    @DisplayName("Should return true for patterns with escape sequences")
    void testIsFullRegexPattern_withEscapeSequences() {
      assertTrue(LabelPatternUtils.isFullRegexPattern("demo\\.service"));
    }

    @Test
    @DisplayName("Should return false for simple wildcards")
    void testIsFullRegexPattern_simpleWildcards() {
      assertFalse(LabelPatternUtils.isFullRegexPattern("demo-*"));
      assertFalse(LabelPatternUtils.isFullRegexPattern(".*"));
      assertFalse(LabelPatternUtils.isFullRegexPattern(".+"));
    }

    @Test
    @DisplayName("Should return false for literal values")
    void testIsFullRegexPattern_literalValues() {
      assertFalse(LabelPatternUtils.isFullRegexPattern("demo"));
      assertFalse(LabelPatternUtils.isFullRegexPattern("demo-service"));
    }

    @Test
    @DisplayName("Should return false for null input")
    void testIsFullRegexPattern_nullInput() {
      assertFalse(LabelPatternUtils.isFullRegexPattern(null));
    }

    @Test
    @DisplayName("Should return false for empty string")
    void testIsFullRegexPattern_emptyString() {
      assertFalse(LabelPatternUtils.isFullRegexPattern(""));
    }
  }

  // ========================================================================
  // Integration scenarios
  // ========================================================================

  @Nested
  @DisplayName("Config Value Processing Integration")
  class ConfigValueProcessingIntegration {

    @Test
    @DisplayName("Should detect and process explicit regex patterns correctly")
    void testExplicitRegexWorkflow() {
      String rawValue = "~^demo.*$";

      // Step 1: Check if it has the prefix
      assertTrue(LabelPatternUtils.hasExplicitRegexPrefix(rawValue));

      // Step 2: Process to strip the prefix
      String processedValue = LabelPatternUtils.processConfigValue(rawValue);
      assertEquals("^demo.*$", processedValue);

      // Step 3: Verify it's a full regex pattern
      assertTrue(LabelPatternUtils.isFullRegexPattern(processedValue));
    }

    @Test
    @DisplayName("Should handle wildcard patterns without ~ prefix")
    void testWildcardWorkflow() {
      String rawValue = "demo-*";

      // Step 1: Check it doesn't have the prefix
      assertFalse(LabelPatternUtils.hasExplicitRegexPrefix(rawValue));

      // Step 2: Process returns unchanged
      String processedValue = LabelPatternUtils.processConfigValue(rawValue);
      assertEquals("demo-*", processedValue);

      // Step 3: Verify it's not a full regex pattern (it's a wildcard)
      assertFalse(LabelPatternUtils.isFullRegexPattern(processedValue));

      // Step 4: But it should be detected as a regex pattern by existing method
      assertTrue(
          LabelPatternUtils.isRegexPattern(
              LabelPatternUtils.convertWildcardToRegex(processedValue)));
    }

    @Test
    @DisplayName("Should handle literal values correctly")
    void testLiteralValueWorkflow() {
      String rawValue = "demo";

      assertFalse(LabelPatternUtils.hasExplicitRegexPrefix(rawValue));
      assertEquals("demo", LabelPatternUtils.processConfigValue(rawValue));
      assertFalse(LabelPatternUtils.isFullRegexPattern(rawValue));
      assertFalse(LabelPatternUtils.isRegexPattern(rawValue));
    }
  }
}
