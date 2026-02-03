package com.evoila.janus.common.enforcement.utils;

import org.springframework.stereotype.Component;

/** Utility class for endpoint path operations */
@Component
public class EndpointPathUtils {

  /** Checks if the endpoint is a label values endpoint */
  public boolean isLabelValuesEndpoint(String endpointPath) {
    return endpointPath.matches(".*/label/[^/]+/values")
        || endpointPath.matches(".*/search/tag/[^/]+/values");
  }

  /** Checks if the endpoint is a labels endpoint */
  public boolean isLabelsEndpoint(String endpointPath) {
    return endpointPath.endsWith("/v1/labels");
  }

  /** Checks if the endpoint is a series endpoint (Thanos/Prometheus) */
  public boolean isSeriesEndpoint(String endpointPath) {
    return endpointPath.endsWith("/v1/series") || endpointPath.equals("v1/series");
  }

  /**
   * Extracts the label/tag name from a path like /api/v1/label/service/values or
   * /api/v2/search/tag/resource.service.name/values
   */
  public String extractLabelNameFromPath(String endpointPath) {
    String[] parts = endpointPath.split("/");
    return java.util.stream.IntStream.range(0, parts.length - 1)
        .filter(i -> "label".equals(parts[i]) || "tag".equals(parts[i]))
        .mapToObj(i -> parts[i + 1])
        .findFirst()
        .orElse("");
  }
}
