package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import org.junit.Test;

/**
 * Unit tests for {@link VersionedIndexOps#extractUserKey(CompositeKey, int)}.
 *
 * <p>This method is called on every index read/stream operation to strip
 * internal trailing elements (version, RID) and recover the user-visible key.
 * It handles null keys, single-element unwrap, and multi-element composite
 * preservation.
 */
public class VersionedIndexOpsExtractUserKeyTest {

  /**
   * Null input must return null (null-key entries have no composite key).
   */
  @Test
  public void extractUserKey_null_returnsNull() {
    assertNull(VersionedIndexOps.extractUserKey(null, 1));
  }

  /**
   * Null user element within CompositeKey: CompositeKey(null, 100L) with
   * trailingCount=1. This is the production case for single-value indexes
   * with null keys — the null user key must be returned as null.
   */
  @Test
  public void extractUserKey_nullUserElement_returnsNull() {
    var composite = new CompositeKey((Object) null, 100L);
    var result = VersionedIndexOps.extractUserKey(composite, 1);
    assertNull("Null user key element must be returned as null", result);
  }

  /**
   * Single-value index: CompositeKey("A", version) with trailingCount=1
   * must return the raw first element "A" (unwrapped from CompositeKey).
   */
  @Test
  public void extractUserKey_singleUserElement_unwraps() {
    var composite = new CompositeKey("A", 100L);
    var result = VersionedIndexOps.extractUserKey(composite, 1);

    assertEquals("A", result);
    assertTrue(
        "Single user key must be unwrapped to raw Object, not CompositeKey",
        result instanceof String);
  }

  /**
   * Composite index with 2 user elements: CompositeKey("A", "B", version)
   * with trailingCount=1 must return CompositeKey("A", "B").
   */
  @Test
  public void extractUserKey_multipleUserElements_returnsCompositeKey() {
    var composite = new CompositeKey("A", "B", 100L);
    var result = VersionedIndexOps.extractUserKey(composite, 1);

    assertTrue(
        "Multiple user keys must be returned as CompositeKey",
        result instanceof CompositeKey);
    var resultKey = (CompositeKey) result;
    assertEquals(2, resultKey.getKeys().size());
    assertEquals("A", resultKey.getKeys().get(0));
    assertEquals("B", resultKey.getKeys().get(1));
  }

  /**
   * Multi-value index: CompositeKey("A", RID, version) with trailingCount=2
   * must strip both RID and version, returning raw "A".
   */
  @Test
  public void extractUserKey_multiValue_stripsRidAndVersion() {
    var rid = new RecordId(5, 10);
    var composite = new CompositeKey("A", rid, 100L);
    var result = VersionedIndexOps.extractUserKey(composite, 2);

    assertEquals("A", result);
    assertTrue(
        "Single user key must be unwrapped to raw Object",
        result instanceof String);
  }

  /**
   * Multi-value composite index: CompositeKey("A", "B", RID, version) with
   * trailingCount=2 must strip RID and version, returning CompositeKey("A", "B").
   */
  @Test
  public void extractUserKey_multiValue_multipleUserKeys_returnsCompositeKey() {
    var rid = new RecordId(5, 10);
    var composite = new CompositeKey("A", "B", rid, 100L);
    var result = VersionedIndexOps.extractUserKey(composite, 2);

    assertTrue(
        "Multiple user keys must be returned as CompositeKey",
        result instanceof CompositeKey);
    var resultKey = (CompositeKey) result;
    assertEquals(2, resultKey.getKeys().size());
    assertEquals("A", resultKey.getKeys().get(0));
    assertEquals("B", resultKey.getKeys().get(1));
  }
}
