package com.evoila.janus.common.labelstore;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeaderFormatTest extends com.evoila.janus.base.BaseUnitTest {

  @BeforeEach
  void setUp() throws IOException {
    super.setUpBase();

    // Create test configuration with header format
    String configContent =
        """
            admin:
              labels:
                - "*"
              header:
                - "X-Tenant: admin"
                - "X-Scope-OrgID: admin-org"

            loki:
              tenant-header-constraints:
                users:
                  header:
                    - "X-Tenant: test"
                    - "X-Scope-OrgID: test-org"
              user-label-constraints:
                test-user:
                  labels:
                    - "service"
                    - "environment"
            """;

    File configFile = createTestConfigFile(configContent);
    setupConfigMapLabelStore(configFile);
  }

  @Test
  void shouldLoadHeaderFormatConfiguration() {
    assertNotNull(configMapLabelStore, "ConfigMapLabelStore should be initialized");
  }
}
