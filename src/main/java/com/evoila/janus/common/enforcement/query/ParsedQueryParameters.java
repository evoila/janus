package com.evoila.janus.common.enforcement.query;

import java.util.List;
import java.util.Map;

/** Immutable record holding parsed query parameters to avoid repeated parsing */
public record ParsedQueryParameters(
    Map<String, List<String>> parameters,
    String enforcementParamValue,
    List<String> matchSelectors,
    String originalQuery) {

  public static ParsedQueryParameters empty() {
    return new ParsedQueryParameters(Map.of(), "", List.of(), "");
  }

  public boolean isEmpty() {
    return originalQuery == null || originalQuery.isEmpty();
  }

  public String getFirstValue(String paramName) {
    List<String> values = parameters.get(paramName);
    return values != null && !values.isEmpty() ? values.get(0) : "";
  }
}
