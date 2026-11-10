package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class StorageFormatTest {

  /** Each supported persisted integer resolves to its own immutable, matching descriptor. */
  @Test
  public void supportedStoredLayoutsResolveExactly() {
    assertThat(StorageFormat.resolve(27)).isSameAs(StorageFormat.LAYOUT_27);
    assertThat(StorageFormat.resolve(27).layoutVersion()).isEqualTo(27);
    assertThat(StorageFormat.resolve(28)).isSameAs(StorageFormat.LAYOUT_28);
    assertThat(StorageFormat.resolve(28).layoutVersion()).isEqualTo(28);
    assertThat(StorageFormat.resolve(27)).isNotSameAs(StorageFormat.resolve(28));
  }

  /** The supported set contains exactly the two literal identities, not a version range. */
  @Test
  public void supportedSetContainsOnlyLayouts27And28() {
    assertThat(StorageFormat.values())
        .containsExactlyInAnyOrder(StorageFormat.LAYOUT_27, StorageFormat.LAYOUT_28);
    assertThat(StorageFormat.values()).extracting(StorageFormat::layoutVersion)
        .containsExactlyInAnyOrder(27, 28);
  }

  /** Ordinary creation remains on literal layout 27 even though layout 28 is supported. */
  @Test
  public void productionDefaultRemainsLayout27() {
    assertThat(StorageFormat.productionDefault()).isSameAs(StorageFormat.resolve(27));
    assertThat(StorageFormat.productionDefault().layoutVersion()).isEqualTo(27);
    assertThat(StorageFormat.productionDefault()).isNotSameAs(StorageFormat.resolve(28));
  }

  /** Historical layouts, unknown future values, and integer extremes reject without fallback. */
  @Test
  public void unsupportedStoredLayoutsAreExplicitlyRejected() {
    for (int version = -25; version < 27; version++) {
      assertUnsupported(version);
    }
    assertUnsupported(Integer.MIN_VALUE);
    assertUnsupported(29);
    assertUnsupported(30);
    assertUnsupported(Integer.MAX_VALUE);
  }

  private static void assertUnsupported(final int version) {
    var guidance = version == 25 || version == 26
        ? ". Export the database with the older YouTrackDB version."
            + " Import that export with the new YouTrackDB version."
        : version > 28
            ? ". A newer YouTrackDB version created this database. Open it with that version."
            : ". This build supports storage layout versions 27 and 28 only.";
    assertThatThrownBy(() -> StorageFormat.resolve(version))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported storage layout version: " + version + guidance);
  }
}
