package com.evoila.janus.security.config;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@Configuration
public class AuthenticationConfig {

  @Bean
  public Converter<Jwt, OAuthToken> jwtToOAuthTokenConverter() {
    return jwt -> {
      OAuthToken oauthToken = new OAuthToken();
      oauthToken.setPreferredUsername(jwt.getClaimAsString("preferred_username"));
      oauthToken.setEmail(jwt.getClaimAsString("email"));

      // First try to get groups from the 'groups' claim (Keycloak groups)
      List<String> groups = jwt.getClaimAsStringList("groups");
      if (groups != null && !groups.isEmpty()) {
        oauthToken.setGroups(groups);
      } else {
        // Fallback to realm_access.roles if groups claim is not available
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.containsKey("roles")) {
          @SuppressWarnings("unchecked")
          List<String> roles = (List<String>) realmAccess.get("roles");
          oauthToken.setGroups(roles);
        } else {
          oauthToken.setGroups(java.util.Collections.emptyList());
        }
      }

      return oauthToken;
    };
  }

  @Bean
  public Converter<Jwt, Mono<JwtAuthenticationToken>> jwtAuthenticationConverter(
      Converter<Jwt, OAuthToken> jwtToOAuthTokenConverter) {
    return jwt -> {
      OAuthToken oauthToken = jwtToOAuthTokenConverter.convert(jwt);
      return Mono.just(new JwtAuthenticationToken(jwt, oauthToken.getAuthorities()));
    };
  }

  @Bean
  public AuthenticationPrincipalResolver authenticationPrincipalResolver(
      Converter<Jwt, OAuthToken> jwtToOAuthTokenConverter) {
    return new AuthenticationPrincipalResolver(jwtToOAuthTokenConverter);
  }
}
