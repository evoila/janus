package com.evoila.janus.common.labelstore;

import lombok.Getter;
import org.springframework.stereotype.Component;

/** Factory for creating label stores Uses the ConfigMapLabelStore for all services */
@Component
@Getter
public class ConfigMapLabelStoreFactory {

  private final ConfigMapLabelStore configMapLabelStore;

  public ConfigMapLabelStoreFactory(ConfigMapLabelStore configMapLabelStore) {
    this.configMapLabelStore = configMapLabelStore;
  }

  /** Creates a service-aware label store for the given service */
  public LabelStore createServiceAwareLabelStore() {
    return new ServiceAwareLabelStore(configMapLabelStore);
  }
}
