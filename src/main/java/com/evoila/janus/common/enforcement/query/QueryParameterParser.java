package com.evoila.janus.common.enforcement.query;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Optimized query parameter parser that extracts all needed data in one pass.
 *
 * <p>This class provides efficient parsing of query parameters with support for: - URL
 * encoding/decoding - Parameter extraction and manipulation - Query string reconstruction - Complex
 * parameter handling (TraceQL queries with && operators)
 */
@Slf4j
public class QueryParameterParser {

  // Constants for query parameter handling
  private static final String KEY_VALUE_SEPARATOR = "=";
  private static final String MATCH_SELECTOR_PARAMETER = "match[]";

  private QueryParameterParser() {
    // Utility class - prevent instantiation
  }

  /**
   * Parses query string once and extracts all needed parameters.
   *
   * @param rawQuery The raw query string to parse
   * @param enforcementParam The enforcement parameter name to extract
   * @return ParsedQueryParameters containing all extracted data
   */
  public static ParsedQueryParameters parseQuery(String rawQuery, String enforcementParam) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return ParsedQueryParameters.empty();
    }

    try {
      log.debug("=== PARSING QUERY PARAMETERS ===");
      log.debug("Raw query: '{}'", rawQuery);

      // Split on literal '&' in the raw encoded string. Parameter separators are
      // unencoded '&', while '&' inside values is encoded as '%26' and won't match.
      List<String> rawPairs = Arrays.asList(rawQuery.split("&"));
      Map<String, List<String>> parameters = parseAndDecodeKeyValuePairs(rawPairs);

      String enforcementParamValue = extractEnforcementParameterValue(parameters, enforcementParam);
      List<String> matchSelectors = extractMatchSelectors(parameters);

      log.debug("Final parameters: {}", parameters);
      log.debug("=== END PARSING QUERY PARAMETERS ===");

      return new ParsedQueryParameters(
          Map.copyOf(parameters), enforcementParamValue, List.copyOf(matchSelectors), rawQuery);

    } catch (Exception e) {
      log.error("Error parsing query parameters: {}", e.getMessage(), e);
      // Return empty result on parsing error
      return ParsedQueryParameters.empty();
    }
  }

  /**
   * Parses raw URL-encoded key-value pairs, decoding each key and value individually.
   *
   * <p>This approach avoids the bug where decoding the entire query string first would turn encoded
   * ampersands (%26) — used in TraceQL operators like && and &>> — into literal '&' characters that
   * get misinterpreted as parameter separators.
   *
   * @param rawPairs The list of raw URL-encoded key-value pair strings
   * @return Map of decoded parameter names to decoded values
   */
  private static Map<String, List<String>> parseAndDecodeKeyValuePairs(List<String> rawPairs) {
    Map<String, List<String>> parameters = new HashMap<>();

    rawPairs.stream()
        .map(pair -> pair.split(KEY_VALUE_SEPARATOR, 2))
        .filter(keyValue -> keyValue.length == 2)
        .forEach(
            keyValue -> {
              String key = decodeValue(keyValue[0].trim());
              String value = decodeValue(keyValue[1].trim());

              log.debug("Parsed parameter: '{}' = '{}'", key, value);

              parameters.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            });

    return parameters;
  }

  private static String decodeValue(String encoded) {
    try {
      return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.warn("Failed to decode value: {}", encoded, e);
      return encoded;
    }
  }

  /**
   * Extracts the enforcement parameter value from parsed parameters.
   *
   * @param parameters The parsed parameters map
   * @param enforcementParam The enforcement parameter name
   * @return The enforcement parameter value or empty string
   */
  private static String extractEnforcementParameterValue(
      Map<String, List<String>> parameters, String enforcementParam) {
    if (enforcementParam != null && parameters.containsKey(enforcementParam)) {
      return parameters.get(enforcementParam).get(0);
    }
    return "";
  }

  /**
   * Extracts match selectors from parsed parameters.
   *
   * @param parameters The parsed parameters map
   * @return List of match selectors
   */
  private static List<String> extractMatchSelectors(Map<String, List<String>> parameters) {
    List<String> matchSelectors = new ArrayList<>();
    if (parameters.containsKey(MATCH_SELECTOR_PARAMETER)) {
      matchSelectors.addAll(parameters.get(MATCH_SELECTOR_PARAMETER));
    }
    return matchSelectors;
  }
}
