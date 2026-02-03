package com.evoila.janus.common.enforcement.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;

/**
 * Robust string parser utility for splitting strings by separators while respecting quotes and
 * braces.
 *
 * <p>This class provides efficient parsing for different contexts: - URL query parameters
 * (separator: '&', respectBraces: true) - Label selectors (separator: ',', respectBraces: false) -
 * Complex nested structures with braces
 *
 * <p>Key features: - Quote-aware splitting (single and double quotes) - Optional brace counting for
 * nested structures - Escape sequence handling - Robust error handling with validation - Early
 * termination on invalid input - Protection against DoS attacks (max length, max pairs)
 */
@Slf4j
public final class StringParser {

  private static final int MAX_INPUT_LENGTH = 10000; // Prevent DoS attacks
  private static final int MAX_PAIRS = 1000; // Prevent memory exhaustion

  private StringParser() {
    // Utility class - prevent instantiation
  }

  /**
   * Represents a label section ({...}) found in a query string.
   *
   * @param start Index of the opening '{' in the original query (inclusive)
   * @param end Index one past the closing '}' in the original query (exclusive)
   * @param innerContent The content between '{' and '}' (not including the braces)
   */
  public record LabelSection(int start, int end, String innerContent) {}

  /**
   * Finds all top-level label sections ({...}) in a query string in a quote-aware manner.
   *
   * <p>Unlike a naive regex like {@code \{([^}]*)\}}, this method correctly handles curly braces
   * inside quoted strings, escaped quotes, and nested structures.
   *
   * @param query The query string to scan
   * @return List of LabelSection records in order of appearance; empty if none found or input is
   *     invalid
   */
  public static List<LabelSection> findLabelSections(String query) {
    if (!isValidInput(query)) {
      return List.of();
    }
    return new LabelSectionScanner(query).scan();
  }

  /**
   * Replaces all label sections in a query by applying a transformation function to each section's
   * inner content. Sections are processed from last to first to preserve character indices.
   *
   * @param query The original query string
   * @param transformer Function that transforms the inner content of a label section
   * @return The query with all label sections replaced
   */
  public static String replaceLabelSections(String query, UnaryOperator<String> transformer) {
    List<LabelSection> sections = findLabelSections(query);
    if (sections.isEmpty()) {
      return query;
    }

    StringBuilder result = new StringBuilder(query);
    for (int i = sections.size() - 1; i >= 0; i--) {
      LabelSection section = sections.get(i);
      String transformed = transformer.apply(section.innerContent());
      result.replace(section.start(), section.end(), "{" + transformed + "}");
    }
    return result.toString();
  }

  /**
   * Replaces only the first label section in a query.
   *
   * @param query The original query string
   * @param transformer Function that transforms the inner content
   * @return The query with the first label section replaced, or unchanged if none found
   */
  public static String replaceFirstLabelSection(String query, UnaryOperator<String> transformer) {
    List<LabelSection> sections = findLabelSections(query);
    if (sections.isEmpty()) {
      return query;
    }
    LabelSection first = sections.getFirst();
    String transformed = transformer.apply(first.innerContent());
    return query.substring(0, first.start())
        + "{"
        + transformed
        + "}"
        + query.substring(first.end());
  }

  /**
   * Parses a string into pairs based on a separator, respecting quotes and optionally braces.
   *
   * <p>This method handles complex parsing scenarios: - Splits by the specified separator character
   * - Respects quoted strings (single and double quotes) - Optionally counts braces for nested
   * structures - Handles escape sequences within quotes - Validates input and provides error
   * handling
   *
   * <p>Examples: - parseIntoPairs("a=1&b=2", '&', true) → ["a=1", "b=2"] -
   * parseIntoPairs("name='John Doe',age=30", ',', false) → ["name='John Doe'", "age=30"]
   *
   * @param input The input string to parse
   * @param separator The separator character (e.g., '&' for query params, ',' for labels)
   * @param respectBraces Whether to count braces for nesting (true for query params, false for
   *     labels)
   * @return List of parsed pairs, or empty list if parsing fails
   */
  public static List<String> parseIntoPairs(String input, char separator, boolean respectBraces) {
    // Input validation
    if (!isValidInput(input)) {
      log.warn("StringParser: Invalid input provided, returning empty list");
      return List.of();
    }

    try {
      return parseValidInput(input, separator, respectBraces);
    } catch (Exception e) {
      log.error("StringParser: Error parsing input: {}", e.getMessage(), e);
      return List.of();
    }
  }

  /** Validates input string for parsing. */
  private static boolean isValidInput(String input) {
    if (input == null || input.isEmpty()) {
      return false;
    }

    if (input.length() > MAX_INPUT_LENGTH) {
      log.warn(
          "StringParser: Input too long ({} chars), max allowed: {}",
          input.length(),
          MAX_INPUT_LENGTH);
      return false;
    }

    return true;
  }

  /** Parses valid input string into pairs. */
  private static List<String> parseValidInput(String input, char separator, boolean respectBraces) {
    List<String> pairs = new ArrayList<>();
    ParserState state = new ParserState(respectBraces);

    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);

      // Check if this is a separator and we should split
      if (c == separator && state.shouldSplit()) {
        state.addCurrentPair(pairs);
        if (pairs.size() > MAX_PAIRS) {
          log.warn("StringParser: Too many pairs ({}), max allowed: {}", pairs.size(), MAX_PAIRS);
          return List.of();
        }
        continue; // Skip adding the separator to the current pair
      }

      if (!state.processCharacter(c)) {
        log.warn("StringParser: Invalid character at position {}, stopping parse", i);
        return List.of(); // Return empty on error
      }
    }

    // Add final pair
    state.addCurrentPair(pairs);

    // Validate final state
    if (!state.isValidFinalState()) {
      log.warn("StringParser: Invalid final state - unclosed quotes or braces");
      return List.of();
    }

    return pairs;
  }

  /** Encapsulates scanning state for finding label sections in a quote-aware manner. */
  private static class LabelSectionScanner {
    private final List<LabelSection> sections = new ArrayList<>();
    private final String query;
    private boolean inQuotes = false;
    private char quoteChar = 0;
    private boolean escaped = false;
    private int braceStart = -1;
    private int braceDepth = 0;

    LabelSectionScanner(String query) {
      this.query = query;
    }

    List<LabelSection> scan() {
      for (int i = 0; i < query.length(); i++) {
        processCharacter(i, query.charAt(i));
      }
      if (braceDepth != 0) {
        log.warn("StringParser: Unclosed braces in query");
      }
      return sections;
    }

    private void processCharacter(int index, char c) {
      if (escaped) {
        escaped = false;
        return;
      }
      if (c == '\\') {
        escaped = true;
        return;
      }
      if (c == '"' || c == '\'') {
        handleQuote(c);
        return;
      }
      if (!inQuotes) {
        handleUnquotedChar(index, c);
      }
    }

    private void handleQuote(char c) {
      if (!inQuotes) {
        inQuotes = true;
        quoteChar = c;
      } else if (c == quoteChar) {
        inQuotes = false;
        quoteChar = 0;
      }
    }

    private void handleUnquotedChar(int index, char c) {
      if (c == '{') {
        handleOpenBrace(index);
      } else if (c == '}') {
        handleCloseBrace(index);
      }
    }

    private void handleOpenBrace(int index) {
      if (braceDepth == 0) {
        braceStart = index;
      }
      braceDepth++;
    }

    private void handleCloseBrace(int index) {
      braceDepth--;
      if (braceDepth == 0 && braceStart >= 0) {
        sections.add(
            new LabelSection(braceStart, index + 1, query.substring(braceStart + 1, index)));
        braceStart = -1;
      }
      if (braceDepth < 0) {
        log.warn("StringParser: Unmatched closing brace at position {}", index);
        braceDepth = 0;
        braceStart = -1;
      }
    }
  }

  /** Encapsulates parsing state and logic for better separation of concerns. */
  private static class ParserState {
    private final boolean respectBraces;
    private final StringBuilder currentPair = new StringBuilder();
    private int braceCount = 0;
    private boolean inQuotes = false;
    private char quoteChar = 0;
    private boolean escaped = false;

    public ParserState(boolean respectBraces) {
      this.respectBraces = respectBraces;
    }

    /** Processes a single character and returns false if an error occurs. */
    public boolean processCharacter(char c) {
      if (escaped) {
        return handleEscapedCharacter(c);
      } else if (c == '\\') {
        return handleEscapeCharacter();
      } else if (isQuoteCharacter(c)) {
        return handleQuoteCharacter(c);
      } else if (respectBraces && isBraceCharacter(c)) {
        return handleBraceCharacter(c);
      } else {
        currentPair.append(c);
        return true;
      }
    }

    private boolean handleEscapedCharacter(char c) {
      currentPair.append(c);
      escaped = false;
      return true;
    }

    private boolean handleEscapeCharacter() {
      escaped = true;
      currentPair.append('\\');
      return true;
    }

    private boolean handleQuoteCharacter(char c) {
      if (!inQuotes) {
        inQuotes = true;
        quoteChar = c;
      } else if (c == quoteChar) {
        inQuotes = false;
        quoteChar = 0;
      }
      currentPair.append(c);
      return true;
    }

    private boolean handleBraceCharacter(char c) {
      if (!inQuotes) {
        if (c == '{') {
          braceCount++;
        } else if (c == '}') {
          braceCount--;
          if (braceCount < 0) {
            log.warn("StringParser: Unmatched closing brace");
            return false;
          }
        }
      }
      currentPair.append(c);
      return true;
    }

    public boolean shouldSplit() {
      return !inQuotes && (!respectBraces || braceCount == 0);
    }

    public void addCurrentPair(List<String> pairs) {
      String pair = currentPair.toString().trim();
      if (!pair.isEmpty()) {
        pairs.add(pair);
      }
      currentPair.setLength(0);
    }

    public boolean isValidFinalState() {
      return !inQuotes && (!respectBraces || braceCount == 0);
    }

    private boolean isQuoteCharacter(char c) {
      return c == '"' || c == '\'';
    }

    private boolean isBraceCharacter(char c) {
      return c == '{' || c == '}';
    }
  }
}
