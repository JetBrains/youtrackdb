package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import org.junit.Test;

public class StorageFormatTest {

  /** Only layout 27 can describe a newly created or admitted image. */
  @Test
  public void onlyLayout27IsSupportedAndIsTheDefault() {
    assertThat(StorageFormat.values()).containsExactly(StorageFormat.LAYOUT_27);
    assertThat(StorageFormat.resolve(27)).isSameAs(StorageFormat.productionDefault());
    assertThat(StorageFormat.productionDefault().layoutVersion()).isEqualTo(27);
    assertThat(StorageConfiguration.CURRENT_VERSION).isEqualTo(27);
  }

  /** Old layouts require export with the older build and import with the new one. */
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
    for (var version : new int[] {28, 99, Integer.MAX_VALUE}) {
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
              + ". This build supports storage layout version 27 only.");
    }
  }
}
