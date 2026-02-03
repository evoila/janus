package com.evoila.janus.security.config;

import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class AuthenticationPrincipalResolver implements HandlerMethodArgumentResolver {
  private final Converter<Jwt, OAuthToken> jwtToOAuthTokenConverter;

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType().equals(OAuthToken.class)
        && parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
  }

  @Override
  public Mono<Object> resolveArgument(
      MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
    return ReactiveSecurityContextHolder.getContext()
        .map(
            securityContext -> {
              Authentication authentication = securityContext.getAuthentication();
              if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
                Jwt jwt = jwtAuthenticationToken.getToken();
                return jwtToOAuthTokenConverter.convert(jwt);
              }
              return null;
            });
  }
}
