package com.evoila.janus.proxy;

import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

public interface ForwardRequestClient {

  Mono<ResponseEntity<String>> forwardRequest(
      String fullUrl,
      HttpMethod method,
      Map<String, String> headers,
      MultiValueMap<String, String> formData);

  Mono<ResponseEntity<byte[]>> forwardRequestBinary(
      String fullUrl,
      HttpMethod method,
      Map<String, String> headers,
      MultiValueMap<String, String> formData);
}
