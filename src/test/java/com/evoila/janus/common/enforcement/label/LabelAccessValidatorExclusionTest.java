package com.evoila.janus.common.enforcement.label;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Test for LabelAccessValidator exclusion behavior */
class LabelAccessValidatorExclusionTest {

  @Test
  @DisplayName("Should deny access to excluded labels even with wildcard access")
  void testExcludedLabelWithWildcardAccess() {
    // Given: User has wildcard access but k8s_node_name is excluded
    Map<String, Set<String>> labelConstraints = Map.of("labels", Set.of("*"));
    Set<String> excludedLabels = Set.of("k8s_node_name");

    // When: Check access to excluded label
    boolean hasAccess =
        LabelAccessValidator.isLabelAccessAllowed(
            labelConstraints, "k8s_node_name", excludedLabels);

    // Then: Should deny access
    assertFalse(hasAccess, "Should deny access to excluded label even with wildcard");
  }

  @Test
  @DisplayName("Should allow access to non-excluded labels with wildcard access")
  void testNonExcludedLabelWithWildcardAccess() {
    // Given: User has wildcard access and namespace is not excluded
    Map<String, Set<String>> labelConstraints = Map.of("labels", Set.of("*"));
    Set<String> excludedLabels = Set.of("k8s_node_name");

    // When: Check access to non-excluded label
    boolean hasAccess =
        LabelAccessValidator.isLabelAccessAllowed(labelConstraints, "namespace", excludedLabels);

    // Then: Should allow access
    assertTrue(hasAccess, "Should allow access to non-excluded label with wildcard");
  }

  @Test
  @DisplayName("Should deny access to excluded labels with explicit label access")
  void testExcludedLabelWithExplicitAccess() {
    // Given: User has explicit access to namespace but k8s_node_name is excluded
    Map<String, Set<String>> labelConstraints = Map.of("labels", Set.of("namespace", "service"));
    Set<String> excludedLabels = Set.of("k8s_node_name");

    // When: Check access to excluded label
    boolean hasAccess =
        LabelAccessValidator.isLabelAccessAllowed(
            labelConstraints, "k8s_node_name", excludedLabels);

    // Then: Should deny access
    assertFalse(hasAccess, "Should deny access to excluded label even if explicitly allowed");
  }

  @Test
  @DisplayName("Should allow access to explicitly allowed non-excluded labels")
  void testExplicitlyAllowedNonExcludedLabel() {
    // Given: User has explicit access to namespace and namespace is not excluded
    Map<String, Set<String>> labelConstraints = Map.of("labels", Set.of("namespace", "service"));
    Set<String> excludedLabels = Set.of("k8s_node_name");

    // When: Check access to explicitly allowed label
    boolean hasAccess =
        LabelAccessValidator.isLabelAccessAllowed(labelConstraints, "namespace", excludedLabels);

    // Then: Should allow access
    assertTrue(hasAccess, "Should allow access to explicitly allowed non-excluded label");
  }

  @Test
  @DisplayName("Should deny access to non-allowed labels even if not excluded")
  void testNonAllowedNonExcludedLabel() {
    // Given: User has explicit access to namespace only, and job is not excluded
    Map<String, Set<String>> labelConstraints = Map.of("labels", Set.of("namespace"));
    Set<String> excludedLabels = Set.of("k8s_node_name");

    // When: Check access to non-allowed label
    boolean hasAccess =
        LabelAccessValidator.isLabelAccessAllowed(labelConstraints, "job", excludedLabels);

    // Then: Should deny access
    assertFalse(hasAccess, "Should deny access to non-allowed label even if not excluded");
  }

  @Test
  @DisplayName("Should work with empty exclusions set")
  void testEmptyExclusionsSet() {
    // Given: User has wildcard access and no exclusions
    Map<String, Set<String>> labelConstraints = Map.of("labels", Set.of("*"));
    Set<String> excludedLabels = Set.of();

    // When: Check access to any label
    boolean hasAccess =
        LabelAccessValidator.isLabelAccessAllowed(
            labelConstraints, "k8s_node_name", excludedLabels);

    // Then: Should allow access
    assertTrue(hasAccess, "Should allow access when no exclusions are present");
  }

  @Test
  @DisplayName("Should work with null exclusions set")
  void testNullExclusionsSet() {
    // Given: User has wildcard access and null exclusions
    Map<String, Set<String>> labelConstraints = Map.of("labels", Set.of("*"));
    Set<String> excludedLabels = null;

    // When: Check access to any label
    boolean hasAccess =
        LabelAccessValidator.isLabelAccessAllowed(
            labelConstraints, "k8s_node_name", excludedLabels);

    // Then: Should allow access
    assertTrue(hasAccess, "Should allow access when exclusions are null");
  }

  @Test
  @DisplayName("Should work with backward compatibility (no exclusions parameter)")
  void testBackwardCompatibility() {
    // Given: User has wildcard access
    Map<String, Set<String>> labelConstraints = Map.of("labels", Set.of("*"));

    // When: Check access using old method (no exclusions parameter)
    boolean hasAccess =
        LabelAccessValidator.isLabelAccessAllowed(labelConstraints, "k8s_node_name");

    // Then: Should allow access (backward compatibility)
    assertTrue(hasAccess, "Should maintain backward compatibility");
  }
}
