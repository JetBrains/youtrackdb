package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.exception.InvalidDatabaseNameException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Standalone unit tests for the package-visible static helpers on
 * {@link AbstractStorage}: {@code normalizeName}, {@code checkName},
 * {@code extractEngineAPIVersion}, and {@code getRidsGroupedByCollection}.
 *
 * <p>These methods are pure (no field access), so they are unit-testable
 * without bringing up a database. They drive the database-name validation
 * front door (URL/path stripping + character whitelist), the index-engine
 * external-ID encoding, and the per-collection RID grouping used during
 * commit. Each method's branches are exercised explicitly so that a future
 * refactor that changes the validation rule or the bit layout fails loudly.
 */
public class AbstractStorageStaticHelpersTest {

  // ---- normalizeName: strips the directory prefix from absolute / nested
  //                    paths so that "/var/db/Foo" becomes "Foo", regardless
  //                    of whether the separator is '/' or the platform-
  //                    specific File.separator. ----

  @Test
  public void normalizeName_returnsBareName_whenNoSeparator() {
    // A simple identifier with no path component must come back unchanged
    // — we are validating that the helper is a no-op in the trivial case.
    assertThat(AbstractStorage.normalizeName("plainName")).isEqualTo("plainName");
  }

  @Test
  public void normalizeName_stripsForwardSlashPrefix() {
    // Forward-slash form: the helper must keep the last segment only.
    assertThat(AbstractStorage.normalizeName("/var/lib/db/MyDb")).isEqualTo("MyDb");
    assertThat(AbstractStorage.normalizeName("relative/path/Foo")).isEqualTo("Foo");
  }

  @Test
  public void normalizeName_stripsPlatformSeparator() {
    // Platform-specific separator path: the helper must work on whatever
    // File.separator the JVM reports. We build a path with the live separator
    // so the test passes on both POSIX and Windows.
    var platformPath = "abc" + File.separator + "def" + File.separator + "Bar";
    assertThat(AbstractStorage.normalizeName(platformPath)).isEqualTo("Bar");
  }

  @Test
  public void normalizeName_keepsLastSegment_whenBothSeparatorsAppear() {
    // Mixed-separator path: max(forwardSlashIdx, platformSeparatorIdx)
    // wins. With "/" inside a Windows path or vice versa, the helper picks
    // whichever separator appears later, ensuring the bare name is captured.
    var mixed = "a/b" + File.separator + "Final";
    assertThat(AbstractStorage.normalizeName(mixed)).isEqualTo("Final");
  }

  @Test
  public void normalizeName_returnsEmpty_whenNameEndsWithSeparator() {
    // Defensive: a path that ends in a separator returns an empty bare name.
    // checkName() then rejects it via InvalidDatabaseNameException, so we are
    // pinning the boundary even though the result is a "bad" name.
    assertThat(AbstractStorage.normalizeName("/foo/")).isEmpty();
  }

  // ---- checkName: validates the bare database name against the
  //                 (letter)(letter|digit|_|$|-)* whitelist and throws
  //                 InvalidDatabaseNameException otherwise. ----

  @Test
  public void checkName_acceptsLetterStartedNamesWithAllowedChars() {
    // Smoke pin: a typical name with letters, digits, underscore, dash, '$'.
    var ok = "Test_db-1$x";
    assertThat(AbstractStorage.checkName(ok)).isEqualTo(ok);
  }

  @Test
  public void checkName_normalizesPathBeforeValidation() {
    // checkName() delegates to normalizeName() first, so a name supplied as a
    // full path is accepted as long as the bare segment is valid.
    assertThat(AbstractStorage.checkName("/var/lib/db/Bar")).isEqualTo("Bar");
  }

  @Test
  public void checkName_rejectsLeadingDigit() {
    // Names must start with a letter; a leading digit is invalid.
    assertThatThrownBy(() -> AbstractStorage.checkName("1bad"))
        .isInstanceOf(InvalidDatabaseNameException.class)
        .hasMessageContaining("1bad")
        .hasMessageContaining("letter");
  }

  @Test
  public void checkName_rejectsEmpty() {
    // Empty string fails the regex (no leading letter).
    assertThatThrownBy(() -> AbstractStorage.checkName(""))
        .isInstanceOf(InvalidDatabaseNameException.class);
  }

  @Test
  public void checkName_rejectsWhitespace() {
    // Whitespace is not in the whitelist.
    assertThatThrownBy(() -> AbstractStorage.checkName("bad name"))
        .isInstanceOf(InvalidDatabaseNameException.class);
  }

  @Test
  public void checkName_rejectsSpecialCharacters() {
    // Punctuation other than '_', '-', '$' is rejected.
    assertThatThrownBy(() -> AbstractStorage.checkName("name@host"))
        .isInstanceOf(InvalidDatabaseNameException.class);
    assertThatThrownBy(() -> AbstractStorage.checkName("name.with.dots"))
        .isInstanceOf(InvalidDatabaseNameException.class);
  }

  @Test
  public void checkName_rejectsPathWithEmptyBareName() {
    // After normalizeName(), a trailing-separator path becomes "" and fails.
    assertThatThrownBy(() -> AbstractStorage.checkName("/foo/"))
        .isInstanceOf(InvalidDatabaseNameException.class);
  }

  // ---- extractEngineAPIVersion: returns the top 5 bits of a 32-bit
  //                               external index id; mirrors the encoding in
  //                               generateIndexId() (top 5 bits = engine API
  //                               version, low 27 bits = internal id). ----

  @Test
  public void extractEngineAPIVersion_returnsZero_forZeroId() {
    // Smoke pin: a zero id encodes engine version 0 and internal id 0.
    assertThat(AbstractStorage.extractEngineAPIVersion(0)).isEqualTo(0);
  }

  @Test
  public void extractEngineAPIVersion_returnsZero_forSmallInternalIds() {
    // Internal ids in the low 27 bits decode to engine version 0
    // (the top 5 bits at positions 27..31 are zero).
    assertThat(AbstractStorage.extractEngineAPIVersion(1)).isEqualTo(0);
    assertThat(AbstractStorage.extractEngineAPIVersion(0x07_FF_FF_FF)).isEqualTo(0);
  }

  @Test
  public void extractEngineAPIVersion_returnsCorrectVersion_forKnownEncoding() {
    // generateIndexId(internalId, engineV) = (engineV << 27) | internalId
    // — reverse the same shift here to verify the helper.
    var internalId = 42;
    for (int v = 0; v < 32; v++) {
      var external = (v << 27) | internalId;
      assertThat(AbstractStorage.extractEngineAPIVersion(external))
          .as("engine API version round-trip for v=%d", v)
          .isEqualTo(v);
    }
  }

  @Test
  public void extractEngineAPIVersion_handlesNegativeIds_viaUnsignedShift() {
    // The helper uses >>> (logical right shift) so a negative external id
    // (high bit set) decodes the version without sign extension.
    // 0xFF_FF_FF_FF >>> 27 = 0x1F = 31 (max 5-bit value).
    assertThat(AbstractStorage.extractEngineAPIVersion(0xFF_FF_FF_FF))
        .isEqualTo(31);
    assertThat(AbstractStorage.extractEngineAPIVersion(Integer.MIN_VALUE))
        .isEqualTo(16); // 0x80_00_00_00 >>> 27 = 0x10
  }

  // ---- getRidsGroupedByCollection: groups RIDs by their cluster (collection)
  //                                  id; preserves order within each group. ----

  @Test
  public void getRidsGroupedByCollection_returnsEmptyMap_whenInputEmpty() {
    // Edge case: an empty input must return an empty (but non-null) map.
    Int2ObjectMap<List<RecordIdInternal>> grouped =
        AbstractStorage.getRidsGroupedByCollection(Collections.emptyList());
    assertThat(grouped).isEmpty();
  }

  @Test
  public void getRidsGroupedByCollection_groupsRidsByCollectionId() {
    // Multi-collection scenario: rids from collections 1, 2, and 1 again
    // should produce two groups with the right counts in each.
    var rids = new ArrayList<RecordIdInternal>();
    rids.add(new RecordId(1, 100));
    rids.add(new RecordId(2, 200));
    rids.add(new RecordId(1, 300));
    rids.add(new RecordId(2, 400));
    rids.add(new RecordId(3, 500));

    var grouped = AbstractStorage.getRidsGroupedByCollection(rids);

    assertThat(grouped.keySet()).containsExactlyInAnyOrder(1, 2, 3);
    assertThat(grouped.get(1)).hasSize(2);
    assertThat(grouped.get(2)).hasSize(2);
    assertThat(grouped.get(3)).hasSize(1);
  }

  @Test
  public void getRidsGroupedByCollection_preservesInsertionOrderWithinGroup() {
    // Within a group, the helper must preserve insertion order — callers rely
    // on this for deterministic locking and replay.
    var rids = new ArrayList<RecordIdInternal>();
    rids.add(new RecordId(1, 10));
    rids.add(new RecordId(1, 20));
    rids.add(new RecordId(1, 30));

    var grouped = AbstractStorage.getRidsGroupedByCollection(rids);

    var group1 = grouped.get(1);
    assertThat(group1).hasSize(3);
    assertThat(group1.get(0).getCollectionPosition()).isEqualTo(10L);
    assertThat(group1.get(1).getCollectionPosition()).isEqualTo(20L);
    assertThat(group1.get(2).getCollectionPosition()).isEqualTo(30L);
  }

  @Test
  public void getRidsGroupedByCollection_handlesSingleCollection() {
    // Single-collection input still produces one group keyed by that id.
    var rids = List.<RecordIdInternal>of(
        new RecordId(7, 1),
        new RecordId(7, 2));
    var grouped = AbstractStorage.getRidsGroupedByCollection(rids);

    assertThat(grouped).hasSize(1);
    assertThat(grouped.get(7)).hasSize(2);
  }
}
