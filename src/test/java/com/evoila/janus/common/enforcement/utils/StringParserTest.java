package com.evoila.janus.common.enforcement.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("StringParser Tests")
class StringParserTest {

  // ========================================================================
  // Basic splitting
  // ========================================================================

  @Test
  @DisplayName("Should split simple pairs by comma")
  void parseIntoPairs_simpleCommaSeparated() {
    List<String> result = StringParser.parseIntoPairs("a=1,b=2,c=3", ',', false);

    assertEquals(3, result.size());
    assertEquals("a=1", result.get(0));
    assertEquals("b=2", result.get(1));
    assertEquals("c=3", result.get(2));
  }

  @Test
  @DisplayName("Should split by ampersand for query parameters")
  void parseIntoPairs_ampersandSeparated() {
    List<String> result = StringParser.parseIntoPairs("key=val&foo=bar", '&', true);

    assertEquals(2, result.size());
    assertEquals("key=val", result.get(0));
    assertEquals("foo=bar", result.get(1));
  }

  @Test
  @DisplayName("Should handle single pair without separator")
  void parseIntoPairs_singlePair() {
    List<String> result = StringParser.parseIntoPairs("namespace=\"demo\"", ',', false);

    assertEquals(1, result.size());
    assertEquals("namespace=\"demo\"", result.get(0));
  }

  // ========================================================================
  // Quote handling
  // ========================================================================

  @Test
  @DisplayName("Should respect double quotes when splitting")
  void parseIntoPairs_doubleQuotes() {
    List<String> result = StringParser.parseIntoPairs("name=\"a,b\",value=1", ',', false);

    assertEquals(2, result.size());
    assertEquals("name=\"a,b\"", result.get(0));
    assertEquals("value=1", result.get(1));
  }

  @Test
  @DisplayName("Should respect single quotes when splitting")
  void parseIntoPairs_singleQuotes() {
    List<String> result = StringParser.parseIntoPairs("name='a,b',value=1", ',', false);

    assertEquals(2, result.size());
    assertEquals("name='a,b'", result.get(0));
    assertEquals("value=1", result.get(1));
  }

  @Test
  @DisplayName("Should handle escape sequences in quotes")
  void parseIntoPairs_escapeInQuotes() {
    List<String> result = StringParser.parseIntoPairs("name=\"a\\\"b\",val=1", ',', false);

    assertEquals(2, result.size());
    assertEquals("name=\"a\\\"b\"", result.get(0));
    assertEquals("val=1", result.get(1));
  }

  @Test
  @DisplayName("Should return empty list for unclosed quotes")
  void parseIntoPairs_unclosedQuotes() {
    List<String> result = StringParser.parseIntoPairs("name=\"unclosed", ',', false);

    assertTrue(result.isEmpty());
  }

  // ========================================================================
  // Brace handling
  // ========================================================================

  @Test
  @DisplayName("Should respect braces when enabled")
  void parseIntoPairs_withBraces() {
    List<String> result = StringParser.parseIntoPairs("query={a=1,b=2}&start=123", '&', true);

    assertEquals(2, result.size());
    assertEquals("query={a=1,b=2}", result.get(0));
    assertEquals("start=123", result.get(1));
  }

  @Test
  @DisplayName("Should not respect braces when disabled")
  void parseIntoPairs_withoutBraces() {
    List<String> result = StringParser.parseIntoPairs("a=1,{b=2,c=3}", ',', false);

    assertEquals(3, result.size());
  }

  @Test
  @DisplayName("Should return empty list for unmatched closing brace")
  void parseIntoPairs_unmatchedClosingBrace() {
    List<String> result = StringParser.parseIntoPairs("a=1}&b=2", '&', true);

    assertTrue(result.isEmpty());
  }

  @Test
  @DisplayName("Should return empty list for unclosed brace")
  void parseIntoPairs_unclosedBrace() {
    List<String> result = StringParser.parseIntoPairs("a={1&b=2", '&', true);

    assertTrue(result.isEmpty());
  }

  // ========================================================================
  // Edge cases and validation
  // ========================================================================

  @Test
  @DisplayName("Should return empty list for null input")
  void parseIntoPairs_nullInput() {
    List<String> result = StringParser.parseIntoPairs(null, ',', false);

    assertTrue(result.isEmpty());
  }

  @Test
  @DisplayName("Should return empty list for empty input")
  void parseIntoPairs_emptyInput() {
    List<String> result = StringParser.parseIntoPairs("", ',', false);

    assertTrue(result.isEmpty());
  }

  @Test
  @DisplayName("Should return empty list for input exceeding max length")
  void parseIntoPairs_tooLongInput() {
    String longInput = "a".repeat(10001);
    List<String> result = StringParser.parseIntoPairs(longInput, ',', false);

    assertTrue(result.isEmpty());
  }

  @Test
  @DisplayName("Should trim whitespace from pairs")
  void parseIntoPairs_whitespace() {
    List<String> result = StringParser.parseIntoPairs("a=1 , b=2 , c=3", ',', false);

    assertEquals(3, result.size());
    assertEquals("a=1", result.get(0));
    assertEquals("b=2", result.get(1));
    assertEquals("c=3", result.get(2));
  }

  @Test
  @DisplayName("Should skip empty segments")
  void parseIntoPairs_emptySegments() {
    List<String> result = StringParser.parseIntoPairs("a=1,,b=2", ',', false);

    assertEquals(2, result.size());
    assertEquals("a=1", result.get(0));
    assertEquals("b=2", result.get(1));
  }

  @Test
  @DisplayName("Should handle nested braces in query parameters")
  void parseIntoPairs_nestedBraces() {
    List<String> result = StringParser.parseIntoPairs("q={a={b=1}}&t=1", '&', true);

    assertEquals(2, result.size());
    assertEquals("q={a={b=1}}", result.get(0));
    assertEquals("t=1", result.get(1));
  }

  // ========================================================================
  // findLabelSections
  // ========================================================================

  @Test
  @DisplayName("Should find single label section")
  void findLabelSections_singleSection() {
    var sections = StringParser.findLabelSections("metric{namespace=\"demo\"}");

    assertEquals(1, sections.size());
    assertEquals("namespace=\"demo\"", sections.getFirst().innerContent());
    assertEquals(6, sections.getFirst().start());
    assertEquals(24, sections.getFirst().end());
  }

  @Test
  @DisplayName("Should find multiple label sections")
  void findLabelSections_multipleSections() {
    var sections =
        StringParser.findLabelSections("rate(metric{ns=\"demo\"}[5m]) + other{job=\"test\"}");

    assertEquals(2, sections.size());
    assertEquals("ns=\"demo\"", sections.get(0).innerContent());
    assertEquals("job=\"test\"", sections.get(1).innerContent());
  }

  static Stream<Arguments> bracesInQuotedValues() {
    return Stream.of(
        Arguments.of(
            "metric{resource.service.name=~\".*{:namespace}.*\"}",
            "{:namespace}",
            "braces in double quotes (core bug fix)"),
        Arguments.of("metric{name='value{with}braces'}", "{with}", "braces in single quotes"),
        Arguments.of("metric{name=\"val\\\"ue{inner}\"}", "{inner}", "escaped quotes with braces"));
  }

  @ParameterizedTest(name = "Should handle {2}")
  @MethodSource("bracesInQuotedValues")
  void findLabelSections_bracesInQuotedValues(
      String query, String expectedSubstring, String description) {
    var sections = StringParser.findLabelSections(query);
    assertEquals(1, sections.size());
    assertTrue(sections.getFirst().innerContent().contains(expectedSubstring));
  }

  static Stream<Arguments> invalidInputsForFindLabelSections() {
    return Stream.of(
        Arguments.of(null, "null input"),
        Arguments.of("", "empty input"),
        Arguments.of("a".repeat(10001), "input exceeding max length"));
  }

  @ParameterizedTest(name = "Should return empty list for {1}")
  @MethodSource("invalidInputsForFindLabelSections")
  void findLabelSections_invalidInput(String input, String description) {
    assertTrue(StringParser.findLabelSections(input).isEmpty());
  }

  @Test
  @DisplayName("Should return empty list for query without braces")
  void findLabelSections_noBraces() {
    assertTrue(StringParser.findLabelSections("up").isEmpty());
  }

  @Test
  @DisplayName("Should handle empty label section")
  void findLabelSections_emptySection() {
    var sections = StringParser.findLabelSections("metric{}");

    assertEquals(1, sections.size());
    assertEquals("", sections.getFirst().innerContent());
  }

  @Test
  @DisplayName("Should handle complex query with braces in regex value")
  void findLabelSections_complexQueryWithQuotedBraces() {
    String query = "sum(rate(http_requests{namespace=\"demo\",path=~\".*{id}.*\"}[5m]))";
    var sections = StringParser.findLabelSections(query);

    assertEquals(1, sections.size());
    assertTrue(sections.getFirst().innerContent().contains("path=~\".*{id}.*\""));
    assertTrue(sections.getFirst().innerContent().contains("namespace=\"demo\""));
  }

  // ========================================================================
  // replaceLabelSections
  // ========================================================================

  @Test
  @DisplayName("Should replace all label sections")
  void replaceLabelSections_allSections() {
    String result =
        StringParser.replaceLabelSections(
            "a{x=\"1\"} + b{y=\"2\"}", inner -> inner + ",added=\"true\"");

    assertEquals("a{x=\"1\",added=\"true\"} + b{y=\"2\",added=\"true\"}", result);
  }

  @Test
  @DisplayName("Should not corrupt quoted braces during replacement")
  void replaceLabelSections_quotedBraces() {
    String result =
        StringParser.replaceLabelSections(
            "metric{name=~\".*{:ns}.*\"}", inner -> inner + ",namespace=\"demo\"");

    assertEquals("metric{name=~\".*{:ns}.*\",namespace=\"demo\"}", result);
  }

  @Test
  @DisplayName("Should return original query when no sections found")
  void replaceLabelSections_noSections() {
    assertEquals("up", StringParser.replaceLabelSections("up", inner -> inner));
  }

  // ========================================================================
  // replaceFirstLabelSection
  // ========================================================================

  @Test
  @DisplayName("Should replace only the first section")
  void replaceFirstLabelSection_onlyFirst() {
    String result =
        StringParser.replaceFirstLabelSection(
            "a{x=\"1\"} + b{y=\"2\"}", inner -> inner + ",z=\"3\"");

    assertTrue(result.startsWith("a{x=\"1\",z=\"3\"}"));
    assertTrue(result.contains("b{y=\"2\"}"));
  }

  @Test
  @DisplayName("Should return original query when no sections found")
  void replaceFirstLabelSection_noSections() {
    assertEquals("up", StringParser.replaceFirstLabelSection("up", inner -> inner));
  }
}
