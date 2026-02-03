package com.evoila.janus.thanos;

import com.evoila.janus.common.config.ProxyConfigFactory;
import com.evoila.janus.common.config.ServiceType;
import com.evoila.janus.common.controller.BaseProxyController;
import com.evoila.janus.common.service.RequestProcessingService;
import com.evoila.janus.security.config.OAuthToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Controller for proxying requests to Thanos Handles PromQL queries and label operations with
 * security enforcement
 */
@Slf4j
@RestController
@RequestMapping("/api")
@Profile({"thanos", "all"})
public class ThanosController extends BaseProxyController {
  public ThanosController(
      RequestProcessingService requestProcessingService, ProxyConfigFactory proxyConfigFactory) {
    super(requestProcessingService, proxyConfigFactory);
  }

  @GetMapping("/**")
  public Mono<ResponseEntity<String>> handleGet(
      ServerWebExchange exchange, @AuthenticationPrincipal OAuthToken token) {

    log.debug("ThanosController: Handling GET request to: {}", exchange.getRequest().getURI());

    return handleGetRequest(exchange, token, ServiceType.THANOS);
  }

  @PostMapping(value = "/**", consumes = "application/x-www-form-urlencoded")
  public Mono<ResponseEntity<String>> handlePost(
      ServerWebExchange exchange, @AuthenticationPrincipal OAuthToken token) {

    log.debug("ThanosController: Handling POST request to: {}", exchange.getRequest().getURI());

    return handlePostRequest(exchange, token, ServiceType.THANOS);
  }
}
