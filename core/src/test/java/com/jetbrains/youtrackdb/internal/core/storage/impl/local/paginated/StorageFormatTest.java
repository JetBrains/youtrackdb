package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class StorageFormatTest {

  /** Each supported persisted integer resolves to its own immutable, matching descriptor. */
  @Test
  public void supportedStoredLayoutsResolveExactly() {
    assertThat(StorageFormat.resolve(25)).isSameAs(StorageFormat.LAYOUT_25);
    assertThat(StorageFormat.resolve(25).layoutVersion()).isEqualTo(25);
    assertThat(StorageFormat.resolve(26)).isSameAs(StorageFormat.LAYOUT_26);
    assertThat(StorageFormat.resolve(26).layoutVersion()).isEqualTo(26);
    assertThat(StorageFormat.resolve(25)).isNotSameAs(StorageFormat.resolve(26));
  }

  /** The supported set contains exactly the two literal identities, not a version range. */
  @Test
  public void supportedSetContainsOnlyLayouts25And26() {
    assertThat(StorageFormat.values())
        .containsExactlyInAnyOrder(StorageFormat.LAYOUT_25, StorageFormat.LAYOUT_26);
    assertThat(StorageFormat.values()).extracting(StorageFormat::layoutVersion)
        .containsExactlyInAnyOrder(25, 26);
  }

  /** Ordinary creation remains on literal layout 25 even though layout 26 is supported. */
  @Test
  public void productionDefaultRemainsLayout25() {
    assertThat(StorageFormat.productionDefault()).isSameAs(StorageFormat.resolve(25));
    assertThat(StorageFormat.productionDefault().layoutVersion()).isEqualTo(25);
    assertThat(StorageFormat.productionDefault()).isNotSameAs(StorageFormat.resolve(26));
  }

  /** Historical layouts, unknown future values, and integer extremes reject without fallback. */
  @Test
  public void unsupportedStoredLayoutsAreExplicitlyRejected() {
    for (int version = -25; version < 25; version++) {
      assertUnsupported(version);
    }
    assertUnsupported(Integer.MIN_VALUE);
    assertUnsupported(27);
    assertUnsupported(28);
    assertUnsupported(Integer.MAX_VALUE);
  }

  private static void assertUnsupported(final int version) {
    assertThatThrownBy(() -> StorageFormat.resolve(version))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported storage layout version: " + version
            + ". Supported versions: 25, 26");
  }
}
