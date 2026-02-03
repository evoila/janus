package com.evoila.janus.loki.enforcement;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.strategy.AbstractSecurityEnforcementTest;
import org.junit.jupiter.api.Tag;

/**
 * Comprehensive test suite for LogQL (Loki) security enforcement Extends
 * AbstractSecurityEnforcementTest to ensure all common functionality works correctly
 */
@Tag("loki")
@Tag("logql")
@Tag("comprehensive")
class LogQLStrategyComprehensiveTest extends AbstractSecurityEnforcementTest {

  @Override
  protected QueryContext.QueryLanguage getQueryLanguage() {
    return QueryContext.QueryLanguage.LOGQL;
  }

  @Override
  protected String getStrategyName() {
    return "LogQL";
  }

  @Override
  protected String buildQueryWithConstraint(String name, String value) {

    return "{" + name + "=\"" + value + "\"}";
  }

  @Override
  protected String buildQueryWithNotEqualsConstraint(String name, String value) {
    return "{" + name + "!=\"" + value + "\"}";
  }

  @Override
  protected String buildEmptyQuery() {
    return "{}";
  }

  @Override
  protected void assertContainsConstraint(String result, String name, String value) {
    assertTrue(
        result.contains(name + "=\"" + value + "\""),
        "Result should contain constraint: " + name + "=\"" + value + "\"");
  }

  @Override
  protected void assertContainsNotEqualsConstraint(String result, String name, String value) {
    assertTrue(
        result.contains(name + "!=\"" + value + "\""),
        "Result should contain not-equals constraint: " + name + "!=\"" + value + "\"");
  }
}
