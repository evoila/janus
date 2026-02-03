package com.evoila.janus.common.enforcement.model.dto;

import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * Context object that encapsulates all information needed for query processing.
 *
 * <p>This class provides a clean interface for passing data between different processing stages: -
 * Original query string to be enhanced - Label constraints defining security policies - Query
 * language for proper syntax handling
 *
 * <p>Supports multiple query languages (PromQL, LogQL, TraceQL) with appropriate syntax handling
 * for each service type (Thanos, Loki, Tempo).
 */
@Getter
@Builder
public class QueryContext {
  private final String originalQuery;
  private final Map<String, Set<String>> labelConstraints;
  private final QueryLanguage language;

  /**
   * Query languages supported by the enforcement system.
   *
   * <p>Each language has specific syntax requirements: - PROMQL: Prometheus Query Language for
   * metrics - LOGQL: Loki Query Language for logs - TRACEQL: Tempo Query Language for traces
   */
  @Getter
  public enum QueryLanguage {
    PROMQL("PromQL"),
    LOGQL("LogQL"),
    TRACEQL("TraceQL");

    private final String displayName;

    QueryLanguage(String displayName) {
      this.displayName = displayName;
    }
  }
}
