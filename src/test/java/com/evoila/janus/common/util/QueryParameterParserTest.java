package com.evoila.janus.common.util;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.enforcement.query.ParsedQueryParameters;
import com.evoila.janus.common.enforcement.query.QueryParameterParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@Tag("fast")
class QueryParameterParserTest {

  @Test
  void testParseQueryWithTraceQLAndOperator() {
    // Test the problematic TraceQL query that contains && operator
    String rawQuery =
        "q=%7Bresource.service.name%3D%22janus%22%20%26%26%20name%3D%22GET%20%2Ftempo%2F**%22%7D&limit=20&spss=3&start=1753041038&end=1753044638";

    ParsedQueryParameters result = QueryParameterParser.parseQuery(rawQuery, "q");

    // Verify that the query parameter is parsed correctly
    assertNotNull(result);
    assertNotNull(result.parameters());

    // The 'q' parameter should contain the complete TraceQL query with && operator
    String qValue = result.getFirstValue("q");
    assertNotNull(qValue, "q parameter should not be null");

    String expectedValue = "{resource.service.name=\"janus\" && name=\"GET /tempo/**\"}";
    assertEquals(expectedValue, qValue, "q parameter should contain the complete TraceQL query");

    // Verify other parameters are parsed correctly
    assertEquals("20", result.getFirstValue("limit"));
    assertEquals("3", result.getFirstValue("spss"));
    assertEquals("1753041038", result.getFirstValue("start"));
    assertEquals("1753044638", result.getFirstValue("end"));

    // Verify that the q parameter contains the && operator (this is the key fix)
    assertTrue(qValue.contains(" && "), "q parameter should contain the && operator");
    assertTrue(
        qValue.contains("name=\"GET /tempo/**\""), "q parameter should contain the name condition");
  }

  @Test
  void testParseQueryWithSimpleParameters() {
    // Test simple query parameters without complex content
    String rawQuery = "param1=value1&param2=value2&param3=value3";

    ParsedQueryParameters result = QueryParameterParser.parseQuery(rawQuery, "param1");

    assertNotNull(result);
    assertEquals("value1", result.getFirstValue("param1"));
    assertEquals("value2", result.getFirstValue("param2"));
    assertEquals("value3", result.getFirstValue("param3"));
  }

  @Test
  void testParseQueryWithEmptyQuery() {
    ParsedQueryParameters result = QueryParameterParser.parseQuery("", "q");
    assertNotNull(result);
    assertTrue(result.parameters().isEmpty());
  }

  @Test
  void testParseQueryWithNullQuery() {
    ParsedQueryParameters result = QueryParameterParser.parseQuery(null, "q");
    assertNotNull(result);
    assertTrue(result.parameters().isEmpty());
  }

  @Test
  void testParseQueryWithComplexTraceQLQuery() {
    // Test another complex TraceQL query
    String rawQuery =
        "q=%7Bservice.name%3D%22order-service%22%20%26%26%20http.method%3D%22POST%22%7D&limit=10";

    ParsedQueryParameters result = QueryParameterParser.parseQuery(rawQuery, "q");

    assertNotNull(result);
    String qValue = result.getFirstValue("q");
    assertNotNull(qValue);

    String expectedValue = "{service.name=\"order-service\" && http.method=\"POST\"}";
    assertEquals(expectedValue, qValue);

    assertEquals("10", result.getFirstValue("limit"));
  }

  @Test
  void testParseQueryWithTraceQLAncestorOperator() {
    // Regression test: TraceQL &>> (ancestor) operator was split on & outside braces
    // because the parser decoded %26 to & before splitting parameters.
    // Raw query from production: the &>> and && operators are encoded as %26%3E%3E and %26%26
    String rawQuery =
        "q=(%7BnestedSetParent%3C0%20%26%26%20true%20%7D%20%26%3E%3E%20%7B%20kind%20%3D%20server%20%7D)%20%7C%7C%20(%7BnestedSetParent%3C0%20%26%26%20true%20%7D)%20%7C%20select(status%2C%20resource.service.name%2C%20name%2C%20nestedSetParent%2C%20nestedSetLeft%2C%20nestedSetRight)&limit=200&spss=20&start=1769933401&end=1769935201";

    ParsedQueryParameters result = QueryParameterParser.parseQuery(rawQuery, "q");

    assertNotNull(result);
    String qValue = result.getFirstValue("q");
    assertNotNull(qValue, "q parameter should not be null");

    // The full TraceQL query must be preserved intact, including &>> operator
    String expectedValue =
        "({nestedSetParent<0 && true } &>> { kind = server }) || ({nestedSetParent<0 && true }) | select(status, resource.service.name, name, nestedSetParent, nestedSetLeft, nestedSetRight)";
    assertEquals(expectedValue, qValue, "TraceQL query with &>> operator must not be split");

    // Other parameters must still parse correctly
    assertEquals("200", result.getFirstValue("limit"));
    assertEquals("20", result.getFirstValue("spss"));
    assertEquals("1769933401", result.getFirstValue("start"));
    assertEquals("1769935201", result.getFirstValue("end"));
  }
}
