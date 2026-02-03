package com.evoila.janus.integration;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import reactor.core.publisher.Mono;

@TestConfiguration
@Profile("test")
public class MockJwtConfig {

  // Special token mappings for multi-group and no-access scenarios
  private static final Map<String, List<String>> SPECIAL_TOKEN_GROUPS = new HashMap<>();

  static {
    // Multi-group user: member of both order-service-team AND my-service-team
    SPECIAL_TOKEN_GROUPS.put(
        "test-token-multi-group", List.of("order-service-team", "my-service-team"));

    // Multi-group-user: used in permissive config where patterns are pre-combined
    SPECIAL_TOKEN_GROUPS.put("test-token-multi-group-user", List.of("multi-group-user"));

    // No-access user: has no group memberships
    SPECIAL_TOKEN_GROUPS.put("test-token-no-access", Collections.emptyList());

    // No-access user alternative format
    SPECIAL_TOKEN_GROUPS.put("test-token-no-access-user", Collections.emptyList());

    // Guest/anonymous user simulation
    SPECIAL_TOKEN_GROUPS.put("test-token-guest", Collections.emptyList());

    // Wildcard team for testing wildcard patterns
    SPECIAL_TOKEN_GROUPS.put("test-token-wildcard-team", List.of("wildcard-team"));

    // Exclude team for testing include/exclude patterns
    SPECIAL_TOKEN_GROUPS.put("test-token-exclude-team", List.of("exclude-team"));

    // Regex team for testing regex patterns
    SPECIAL_TOKEN_GROUPS.put("test-token-regex-team", List.of("regex-team"));
  }

  @Bean
  @Primary
  public ReactiveJwtDecoder mockJwtDecoder() {
    return token -> {
      if (token == null || token.isEmpty()) {
        return Mono.error(new InvalidBearerTokenException("No token provided"));
      }

      // Handle invalid tokens
      if ("invalid-token".equals(token)) {
        return Mono.error(new InvalidBearerTokenException("Invalid token"));
      }

      // Handle real JWT tokens (created by createMockJwtToken)
      if (token.contains(".") && token.split("\\.").length == 3) {
        try {
          // Parse the JWT token to extract groups
          String[] parts = token.split("\\.");
          if (parts.length == 3) {
            // For test purposes, we'll create a mock JWT with the groups from the token
            Map<String, Object> claims = new HashMap<>();
            claims.put("sub", "test-user");
            claims.put("email", "test@example.com");
            claims.put("name", "Test User");
            claims.put("groups", List.of("order-service-team"));

            Jwt jwt =
                Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .header("typ", "JWT")
                    .claim("sub", "test-user")
                    .claim("email", "test@example.com")
                    .claim("name", "Test User")
                    .claim("groups", List.of("order-service-team"))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();

            return Mono.just(jwt);
          }
        } catch (Exception _) {
          return Mono.error(new InvalidBearerTokenException("Invalid JWT format"));
        }
      }

      // Handle test-token-* format
      if (token.startsWith("test-token-")) {
        // Check for special token mappings first
        List<String> groups = SPECIAL_TOKEN_GROUPS.get(token);

        if (groups == null) {
          // Default behavior: extract group name from token
          String group = token.substring("test-token-".length());
          groups = List.of(group);
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "test-user");
        claims.put("email", "test@example.com");
        claims.put("name", "Test User");
        claims.put("groups", groups);

        Jwt jwt =
            Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .header("typ", "JWT")
                .claim("sub", "test-user")
                .claim("email", "test@example.com")
                .claim("name", "Test User")
                .claim("groups", groups)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        return Mono.just(jwt);
      }

      // Default case - invalid token
      return Mono.error(new InvalidBearerTokenException("Invalid token"));
    };
  }
}
