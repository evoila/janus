package com.evoila.janus.common.enforcement.labels;

import com.evoila.janus.common.enforcement.query.ParsedQueryParameters;
import java.util.Map;
import java.util.Set;

public interface LabelValuesEnhancer {
  String enhanceLabelValuesQuery(LabelValuesContext context);

  record LabelValuesContext(
      String rawQuery,
      String enforcementParam,
      Map<String, Set<String>> labelConstraints,
      ParsedQueryParameters parsedQuery) {}
}
