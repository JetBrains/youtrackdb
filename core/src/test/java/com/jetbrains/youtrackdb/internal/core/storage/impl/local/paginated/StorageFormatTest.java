package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import org.junit.Test;

public class StorageFormatTest {

  /** Exactly layouts 27 and 28 describe admitted images. */
  @Test
  public void supportedStoredLayoutsResolveExactly() {
    assertThat(StorageFormat.resolve(27)).isSameAs(StorageFormat.LAYOUT_27);
    assertThat(StorageFormat.resolve(27).layoutVersion()).isEqualTo(27);
    assertThat(StorageFormat.resolve(28)).isSameAs(StorageFormat.LAYOUT_28);
    assertThat(StorageFormat.resolve(28).layoutVersion()).isEqualTo(28);
    assertThat(StorageFormat.resolve(27)).isNotSameAs(StorageFormat.resolve(28));
  }

  /** Both supported descriptors have independent persisted discriminators. */
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
    assertThat(StorageConfiguration.CURRENT_VERSION).isEqualTo(27);
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

  /** Old layouts require export and import, future and unknown layouts have distinct advice. */
  @Test
  public void obsoleteLayoutsRequireExportAndImport() {
    for (var version : new int[] {25, 26}) {
      assertThatThrownBy(() -> StorageFormat.resolve(version))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unsupported storage layout version: " + version
              + ". Export the database with the older YouTrackDB version."
              + " Import that export with the new YouTrackDB version.");
    }
  }

  /** Newer layouts explain their origin, including the largest stored integer. */
  @Test
  public void newerLayoutsNameTheCreatingVersion() {
    for (var version : new int[] {29, 99, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> StorageFormat.resolve(version))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unsupported storage layout version: " + version
              + ". A newer YouTrackDB version created this database. Open it with that version.");
    }
  }

  /** Stored disagreements name both authorities, including a missing configuration value. */
  @Test
  public void disagreementsDoNotGiveImageAdmissionAdvice() {
    for (var version : new Integer[] {24, 25, 26, 28, null}) {
      assertThat(StorageFormat.layoutVersionDisagreement("stored value", version, "image", 27))
          .isEqualTo("The stored value reports storage layout version "
              + (version == null ? "<missing>" : version)
              + ". The image reports storage layout version 27."
              + " The stored storage layout versions disagree.")
          .doesNotContain("Export", "Import", "A newer YouTrackDB version");
    }
  }

  /** Other values, including zero and the smallest integer, receive a generic refusal. */
  @Test
  public void unknownLayoutsDoNotSuggestMigration() {
    for (var version : new int[] {Integer.MIN_VALUE, -1, 0, 24}) {
      assertThatThrownBy(() -> StorageFormat.resolve(version))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Unsupported storage layout version: " + version
              + ". This build supports storage layout versions 27 and 28 only.");
    }
  }
}
