package com.evoila.janus.app.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"thanos", "all"})
@ComponentScan(
    basePackages = {
      "com.evoila.janus.thanos",
      "com.evoila.janus.security",
      "com.evoila.janus.proxy",
      "com.evoila.janus.common"
    })
public class ThanosConfiguration {}
