package com.evoila.janus.common.enforcement.labels;

import com.evoila.janus.loki.enforcement.LokiQueryEnhancer;
import com.evoila.janus.tempo.enforcement.TempoQueryEnhancer;
import com.evoila.janus.thanos.enforcement.ThanosQueryEnhancer;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class LabelValuesEnhancerRegistry {
  private final Map<String, LabelValuesEnhancer> enhancers;
  private final ThanosQueryEnhancer thanosEnhancer;

  public LabelValuesEnhancerRegistry(
      ThanosQueryEnhancer thanos, LokiQueryEnhancer loki, TempoQueryEnhancer tempo) {
    this.thanosEnhancer = thanos;
    this.enhancers =
        Map.of(
            "thanos", thanos,
            "loki", loki,
            "tempo", tempo);
  }

  public Optional<LabelValuesEnhancer> getEnhancer(String serviceName) {
    return Optional.ofNullable(enhancers.get(serviceName.toLowerCase()));
  }

  /** Get the Thanos query enhancer directly for series endpoint handling. */
  public ThanosQueryEnhancer getThanosEnhancer() {
    return thanosEnhancer;
  }
}
