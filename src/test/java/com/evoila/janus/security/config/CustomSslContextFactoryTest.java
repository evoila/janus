package com.evoila.janus.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomSslContextFactoryTest {

  @TempDir static Path tempDir;

  private static Path certPath;

  // Self-signed test certificate generated via keytool
  private static final String TEST_CERT_PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIICwTCCAamgAwIBAgIIP+n+WILAhe8wDQYJKoZIhvcNAQEMBQAwDzENMAsGA1UE
      AxMEVGVzdDAeFw0yNjAxMjkxOTA3MzFaFw0yNjAxMzAxOTA3MzFaMA8xDTALBgNV
      BAMTBFRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCGXM18bhIE
      vzPsEnjubGGm+9Dfu3TcSXo5W1udzEfGbdQrAyLacqcAmxVVZfvAXbahyzqDwam4
      eOUyCyyYddI3d28Wotiju9QjrxphdJYE9NpCy12u8BHpJfEkNZRh5LYJ8Lq0y+6x
      K8iGsa8L1FcBBJ36Pg0wOVsMUSd4RXvWl2QES/mMRgysiF/ZVV5vZL82fPynIP1L
      Oj1pFcFuGb+8jxBEAvBwQWclqR+Og0RIMcbf7L8e5GxeMyxoxoOPp6vy2Bp1w4YJ
      ZuapG9HSn5cm+Bc//d0T0hl7jg3KfPp4Elwh4wYgreLuw2WmjLSBDc/iuttvavaO
      YpSWCeIhZCmLAgMBAAGjITAfMB0GA1UdDgQWBBR3FIIw5z+y8157tZQe1jPzJ8IQ
      FTANBgkqhkiG9w0BAQwFAAOCAQEAFYbd7wgjryH8yptAOeriBD62ujFbDj/Q5LUJ
      QESU9nnq/ighJHiKiizVI47TJr4fIsbdtCidvDHZvioKnSejm/Xb4NmiYhScC/vo
      3KwwbCS9NJQ9SLfKCaII2TKcb+o5BCK8PIZqKUerYJUdAK72+EWXLnckug5SCU6s
      /urPsQ2ZPlbtG/dOtv8lpf9m2/2xjb8dOzxwPkQijxp12W7aL+W5ryV3pCIsysBh
      WIS5dpU+JnwJSdB9EO+p4Xg4eOZOpaKDbALJLgPmxL6xDqZGlX7i6+bwIPjcbR5w
      umrdR225KBM/T45ScN2uMYbo9M2I/xtL8D1FNAmNZPrLUxonNQ==
      -----END CERTIFICATE-----
      """;

  @BeforeAll
  static void setUp() throws Exception {
    certPath = tempDir.resolve("test-ca.pem");
    Files.writeString(certPath, TEST_CERT_PEM);
  }

  @Test
  void loadCustomCaTrustManager_ShouldReturnTrustManagerFactory() throws Exception {
    TrustManagerFactory tmf = CustomSslContextFactory.loadCustomCaTrustManager(certPath);

    assertThat(tmf).isNotNull();
    assertThat(tmf.getTrustManagers()).isNotEmpty();
  }

  @Test
  void loadCustomCaTrustManager_ShouldThrowForInvalidPath() {
    Path invalidPath = tempDir.resolve("nonexistent.pem");

    assertThatThrownBy(() -> CustomSslContextFactory.loadCustomCaTrustManager(invalidPath))
        .isInstanceOf(Exception.class);
  }

  @Test
  void createHttpClientWithCustomCa_ShouldReturnHttpClient() throws Exception {
    var httpClient = CustomSslContextFactory.createHttpClientWithCustomCa(certPath);

    assertThat(httpClient).isNotNull();
  }

  @Test
  void isCustomCaAvailable_ShouldReturnTrueForExistingFile() {
    assertThat(CustomSslContextFactory.isCustomCaAvailable(certPath.toString())).isTrue();
  }

  @Test
  void isCustomCaAvailable_ShouldReturnFalseForNull() {
    assertThat(CustomSslContextFactory.isCustomCaAvailable(null)).isFalse();
  }

  @Test
  void isCustomCaAvailable_ShouldReturnFalseForEmptyString() {
    assertThat(CustomSslContextFactory.isCustomCaAvailable("  ")).isFalse();
  }

  @Test
  void isCustomCaAvailable_ShouldReturnFalseForNonExistentPath() {
    assertThat(CustomSslContextFactory.isCustomCaAvailable("/nonexistent/path/ca.pem")).isFalse();
  }
}
