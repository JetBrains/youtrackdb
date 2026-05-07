package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexUnique;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for {@link UniqueIndexEngineValidator}.
 *
 * <p>The validator is invoked by {@link
 * com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine#validatedPut}
 * to enforce uniqueness. It has four observable branches:
 * <ol>
 *   <li>oldValue is {@code null} — fresh insert, validator returns newValue's identity.</li>
 *   <li>oldValue == newValue (same RID) — update of the same record; returns
 *       {@link IndexEngineValidator#IGNORE}.</li>
 *   <li>oldValue != newValue, metadata says MERGE_KEYS=true — multi-RID merge allowed;
 *       returns newValue's identity.</li>
 *   <li>oldValue != newValue, no mergeSameKey or false — duplicate detected; throws
 *       {@link RecordDuplicatedException}.</li>
 * </ol>
 */
public class UniqueIndexEngineValidatorTest {

  // Persistent RIDs: cluster > 0 and position > 0 makes isPersistent() true
  private static final RecordId RID_A = new RecordId(10, 1);
  private static final RecordId RID_B = new RecordId(10, 2);
  private static final String IDX_NAME = "testIndex";

  // ═══════════════════════════════════════════════════════════════════════
  // oldValue == null: fresh insert
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * When oldValue is null (no existing entry for the key), the validator must
   * return the new RID's identity — the insert should proceed.
   */
  @Test
  public void validate_oldValueNull_returnsNewValueIdentity() {
    var index = mockIndexUniqueWithMetadata(null);
    var validator = new UniqueIndexEngineValidator(index);

    Object result = validator.validate("key1", null, RID_A);

    // The production code asserts newValue.isPersistent() and returns newValue.getIdentity()
    assertEquals("Fresh insert must return the new RID's identity",
        RID_A.getIdentity(), result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // oldValue == newValue: same-record update → IGNORE
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * When the old and new RIDs are equal (same record, re-put), the validator
   * must return {@link IndexEngineValidator#IGNORE} to suppress a spurious
   * "duplicate" error during a self-update.
   */
  @Test
  public void validate_sameRid_returnsIgnore() {
    var index = mockIndexUniqueWithMetadata(null);
    var validator = new UniqueIndexEngineValidator(index);

    // Both oldValue and newValue point to the same RID
    Object result = validator.validate("key1", RID_A, RID_A);

    assertSame("Same-RID update must return IGNORE sentinel",
        IndexEngineValidator.IGNORE, result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // oldValue != newValue, no metadata → duplicate, throws
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * When different RIDs exist for the same key and metadata is null
   * (treated as mergeSameKey=false), the validator must throw
   * {@link RecordDuplicatedException}.
   */
  @Test(expected = RecordDuplicatedException.class)
  public void validate_differentRid_nullMetadata_throwsDuplicate() {
    var index = mockIndexUniqueWithMetadata(null);
    var validator = new UniqueIndexEngineValidator(index);

    validator.validate("key1", RID_A, RID_B);
  }

  /**
   * When different RIDs exist for the same key and MERGE_KEYS is explicitly
   * Boolean.FALSE in the metadata map, the validator must throw
   * {@link RecordDuplicatedException}.
   */
  @Test(expected = RecordDuplicatedException.class)
  public void validate_differentRid_mergeFalse_throwsDuplicate() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(Index.MERGE_KEYS, Boolean.FALSE);
    var index = mockIndexUniqueWithMetadata(metadata);
    var validator = new UniqueIndexEngineValidator(index);

    validator.validate("key1", RID_A, RID_B);
  }

  /**
   * When MERGE_KEYS is null in the metadata map (key present, value is null),
   * the (Boolean) cast yields null and the null-check treats it as false —
   * duplicate is still thrown.
   */
  @Test(expected = RecordDuplicatedException.class)
  public void validate_differentRid_mergeNullValue_throwsDuplicate() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(Index.MERGE_KEYS, null);
    var index = mockIndexUniqueWithMetadata(metadata);
    var validator = new UniqueIndexEngineValidator(index);

    validator.validate("key1", RID_A, RID_B);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // oldValue != newValue, mergeSameKey=true → allowed, returns newValue
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * When MERGE_KEYS is Boolean.TRUE, different RIDs for the same key are allowed
   * (multi-RID merge mode). The validator must not throw and must return the new
   * value's identity.
   */
  @Test
  public void validate_differentRid_mergeTrue_returnsNewValueIdentity() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(Index.MERGE_KEYS, Boolean.TRUE);
    var index = mockIndexUniqueWithMetadata(metadata);
    var validator = new UniqueIndexEngineValidator(index);

    Object result = validator.validate("key1", RID_A, RID_B);

    // Falls through to: assert newValue.isPersistent() + return newValue.getIdentity()
    assertEquals("Merge-allowed path must return the new value's identity",
        RID_B.getIdentity(), result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Helpers
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Creates a mock IndexUnique whose {@code getMetadata()} returns the given map
   * (may be null).
   */
  private static IndexUnique mockIndexUniqueWithMetadata(Map<String, Object> metadata) {
    var index = mock(IndexUnique.class);
    when(index.getName()).thenReturn(IDX_NAME);
    when(index.getMetadata()).thenReturn(metadata);
    return index;
  }
}
