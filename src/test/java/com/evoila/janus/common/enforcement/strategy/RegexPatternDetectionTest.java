package com.evoila.janus.common.enforcement.strategy;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.enforcement.label.LabelProcessor;
import com.evoila.janus.common.enforcement.model.dto.LabelConstraintInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegexPatternDetectionTest {

  @Test
  void testRegexPatternDetectionInLabelParsing() {
    // Test the exact scenario from the logs
    String labelsSection =
        "namespace=\"(demo|observability)\",service=\".+\",pod=\".+\", status!~\"5..\"";

    Map<String, LabelConstraintInfo> parsedConstraints =
        LabelProcessor.parseLabelsSection(labelsSection);

    // Verify that the regex pattern was detected and converted to =~ operator
    LabelConstraintInfo namespaceConstraint = parsedConstraints.get("namespace");
    assertNotNull(namespaceConstraint, "namespace constraint should be parsed");
    assertEquals(
        "=~", namespaceConstraint.operator(), "namespace should use =~ operator for regex pattern");
    assertEquals(
        "(demo|observability)", namespaceConstraint.value(), "namespace value should be preserved");

    // Verify other constraints are parsed correctly
    LabelConstraintInfo serviceConstraint = parsedConstraints.get("service");
    assertNotNull(serviceConstraint, "service constraint should be parsed");
    assertEquals("=~", serviceConstraint.operator(), "service should use =~ operator");
    assertEquals(".+", serviceConstraint.value(), "service value should be preserved");

    LabelConstraintInfo podConstraint = parsedConstraints.get("pod");
    assertNotNull(podConstraint, "pod constraint should be parsed");
    assertEquals("=~", podConstraint.operator(), "pod should use =~ operator");
    assertEquals(".+", podConstraint.value(), "pod value should be preserved");

    LabelConstraintInfo statusConstraint = parsedConstraints.get("status");
    assertNotNull(statusConstraint, "status constraint should be parsed");
    assertEquals("!~", statusConstraint.operator(), "status should use !~ operator");
    assertEquals("5..", statusConstraint.value(), "status value should be preserved");
  }

  @Test
  void testRegexPatternDetectionWithParentheses() {
    // Test various regex patterns with parentheses
    String labelsSection = "namespace=\"(demo|observability)\",service=\"(order|stock)-service\"";

    Map<String, LabelConstraintInfo> parsedConstraints =
        LabelProcessor.parseLabelsSection(labelsSection);

    LabelConstraintInfo namespaceConstraint = parsedConstraints.get("namespace");
    assertEquals("=~", namespaceConstraint.operator(), "namespace should use =~ operator");
    assertEquals(
        "(demo|observability)", namespaceConstraint.value(), "namespace value should be preserved");

    LabelConstraintInfo serviceConstraint = parsedConstraints.get("service");
    assertEquals("=~", serviceConstraint.operator(), "service should use =~ operator");
    assertEquals(
        "(order|stock)-service", serviceConstraint.value(), "service value should be preserved");
  }

  @Test
  void testRegexPatternDetectionWithPipeOperator() {
    // Test regex patterns with pipe operator
    String labelsSection = "namespace=\"demo|observability|production\"";

    Map<String, LabelConstraintInfo> parsedConstraints =
        LabelProcessor.parseLabelsSection(labelsSection);

    LabelConstraintInfo namespaceConstraint = parsedConstraints.get("namespace");
    assertEquals("=~", namespaceConstraint.operator(), "namespace should use =~ operator");
    assertEquals(
        "demo|observability|production",
        namespaceConstraint.value(),
        "namespace value should be preserved");
  }

  @Test
  void testRegexPatternDetectionWithWildcards() {
    // Test regex patterns with wildcards
    String labelsSection = "service=\".*order.*\",namespace=\".+\"";

    Map<String, LabelConstraintInfo> parsedConstraints =
        LabelProcessor.parseLabelsSection(labelsSection);

    LabelConstraintInfo serviceConstraint = parsedConstraints.get("service");
    assertEquals("=~", serviceConstraint.operator(), "service should use =~ operator");
    assertEquals(".*order.*", serviceConstraint.value(), "service value should be preserved");

    LabelConstraintInfo namespaceConstraint = parsedConstraints.get("namespace");
    assertEquals("=~", namespaceConstraint.operator(), "namespace should use =~ operator");
    assertEquals(".+", namespaceConstraint.value(), "namespace value should be preserved");
  }

  @Test
  void testNonRegexPatternsNotConverted() {
    // Test that non-regex patterns are not converted
    String labelsSection = "namespace=\"demo\",service=\"order-service\"";

    Map<String, LabelConstraintInfo> parsedConstraints =
        LabelProcessor.parseLabelsSection(labelsSection);

    LabelConstraintInfo namespaceConstraint = parsedConstraints.get("namespace");
    assertEquals(
        "=", namespaceConstraint.operator(), "namespace should use = operator for literal value");
    assertEquals("demo", namespaceConstraint.value(), "namespace value should be preserved");

    LabelConstraintInfo serviceConstraint = parsedConstraints.get("service");
    assertEquals(
        "=", serviceConstraint.operator(), "service should use = operator for literal value");
    assertEquals("order-service", serviceConstraint.value(), "service value should be preserved");
  }

  @Test
  void testExplicitRegexOperatorsNotChanged() {
    // Test that explicit regex operators are not changed
    String labelsSection = "namespace=~\"demo|observability\",service!~\"order.*\"";

    Map<String, LabelConstraintInfo> parsedConstraints =
        LabelProcessor.parseLabelsSection(labelsSection);

    LabelConstraintInfo namespaceConstraint = parsedConstraints.get("namespace");
    assertEquals("=~", namespaceConstraint.operator(), "namespace should keep =~ operator");
    assertEquals(
        "demo|observability", namespaceConstraint.value(), "namespace value should be preserved");

    LabelConstraintInfo serviceConstraint = parsedConstraints.get("service");
    assertEquals("!~", serviceConstraint.operator(), "service should keep !~ operator");
    assertEquals("order.*", serviceConstraint.value(), "service value should be preserved");
  }
}
