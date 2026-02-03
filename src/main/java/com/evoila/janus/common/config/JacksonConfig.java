package com.evoila.janus.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfig {

  @Bean
  public JsonMapper jsonMapper() {
    return JsonMapper.builder().build();
  }
}
