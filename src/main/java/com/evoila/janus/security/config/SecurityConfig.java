package com.evoila.janus.security.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final AuthenticationConfig authenticationConfig;

  @Bean
  public ServerAuthenticationEntryPoint customAuthenticationEntryPoint() {
    return (exchange, ex) -> {
      // Set the response status to 401 Unauthorized for any authentication failure
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      exchange.getResponse().getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);

      // Create a proper JSON error response
      String errorMessage =
          "{\"error\":\"Unauthorized\",\"message\":\"Authentication required\",\"status\":401}";
      DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(errorMessage.getBytes());

      return exchange.getResponse().writeWith(Mono.just(buffer));
    };
  }

  @Bean
  public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
    http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(
            auth -> auth.pathMatchers("/actuator/**").permitAll().anyExchange().authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .authenticationEntryPoint(customAuthenticationEntryPoint())
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(
                                authenticationConfig.jwtAuthenticationConverter(
                                    authenticationConfig.jwtToOAuthTokenConverter()))))
        .exceptionHandling(
            exceptions -> exceptions.authenticationEntryPoint(customAuthenticationEntryPoint()));
    return http.build();
  }
}
