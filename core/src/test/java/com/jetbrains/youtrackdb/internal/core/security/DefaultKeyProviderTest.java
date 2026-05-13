package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;
import org.junit.Test;

/**
 * Tests {@link DefaultKeyProvider}: holds a single 32-byte HMAC-SHA256 secret, returns the
 * same {@link javax.crypto.spec.SecretKeySpec} for every header (the header is ignored), and
 * exposes a constant {@code "default"} key id.
 */
public class DefaultKeyProviderTest {

  private static byte[] testSecret() {
    final var key = new byte[32];
    for (var i = 0; i < key.length; i++) {
      key[i] = (byte) i;
    }
    return key;
  }

  @Test
  public void shouldReturnSameKeyForAnyHeader() {
    // The provider ignores the header — both calls must return the same Key reference.
    final var provider = new DefaultKeyProvider(testSecret());
    final var first = provider.getKey(stubHeader("HS256"));
    final var second = provider.getKey(stubHeader("HS512"));
    assertThat(first).isSameAs(second);
  }

  @Test
  public void shouldExposeHmacSha256AlgorithmOnSecret() {
    // The Key returned wraps an HmacSHA256 SecretKeySpec; tests downstream rely on that
    // when initialising Mac.getInstance("HmacSHA256").
    final var provider = new DefaultKeyProvider(testSecret());
    final var key = provider.getKey(stubHeader("anything"));
    assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
  }

  @Test
  public void shouldExposeDefaultKeyName() {
    final var provider = new DefaultKeyProvider(testSecret());
    assertThat(provider.getDefaultKey()).isEqualTo("default");
    assertThat(provider.getKeys()).containsExactly("default");
  }

  private static TokenHeader stubHeader(final String alg) {
    return new TokenHeader() {
      @Override
      public String getAlgorithm() {
        return alg;
      }

      @Override
      public void setAlgorithm(String a) {
      }

      @Override
      public String getType() {
        return null;
      }

      @Override
      public void setType(String t) {
      }

      @Override
      public String getKeyId() {
        return null;
      }

      @Override
      public void setKeyId(String k) {
      }
    };
  }
}
