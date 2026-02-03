package com.evoila.janus.common.enforcement.utils;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** Utility class for common label processing operations. */
@Slf4j
public final class LabelProcessingUtils {

  private static final String LABELS_KEY = "labels";

  private LabelProcessingUtils() {
    // Utility class - prevent instantiation
  }

  /**
   * Gets the set of labels that should be processed
   *
   * @param labelConstraints The full set of label constraints
   * @return Set of label names to process
   */
  public static Set<String> getLabelsToProcess(Map<String, Set<String>> labelConstraints) {
    Set<String> allowedLabelNames = labelConstraints.get(LABELS_KEY);

    // If we have wildcard access to labels (no specific labels or wildcard in labels),
    // we should add constraints for all non-configuration labels that have specific values
    if (allowedLabelNames == null
        || allowedLabelNames.isEmpty()
        || allowedLabelNames.contains(LabelPatternUtils.WILDCARD_ASTERISK)) {
      return labelConstraints.entrySet().stream()
          .filter(
              entry ->
                  !LabelPatternUtils.CONFIGURATION_KEYS.contains(entry.getKey())
                      && !LABELS_KEY.equals(entry.getKey()))
          .filter(
              entry ->
                  entry.getValue() != null
                      && !entry.getValue().isEmpty()
                      && !LabelPatternUtils.containsWildcardValues(entry.getValue()))
          .map(Map.Entry::getKey)
          .collect(Collectors.toSet());
    }

    // Only process the labels that are explicitly allowed and have specific values
    return allowedLabelNames.stream()
        .filter(
            name ->
                !LabelPatternUtils.CONFIGURATION_KEYS.contains(name) && !LABELS_KEY.equals(name))
        .filter(
            name -> {
              Set<String> values = labelConstraints.get(name);
              return values != null
                  && !values.isEmpty()
                  && !LabelPatternUtils.containsWildcardValues(values);
            })
        .collect(Collectors.toSet());
  }
}
