package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.CellBTreeSingleValue;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Tests for histogram manager wiring in B-tree index engines (Step 5).
 *
 * <p>Verifies that put/remove/delete/close/clear operations correctly
 * delegate to the histogram manager when one is set, and that operations
 * work normally when no manager is set.
 *
 * <p>After snapshot isolation, single-value engines wrap every user key
 * into a {@link CompositeKey} with an appended version timestamp before
 * storing it in the B-tree. The version comes from
 * {@link AtomicOperation#getCommitTs()} (mocked to return {@code 1L} in
 * the fixture). For example, user key {@code "key1"} becomes
 * {@code CompositeKey("key1", 1L)}.
 */
public class BTreeEngineHistogramWiringTest {

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine: put() wiring
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_put_insert_callsOnPutWithWasInsertTrue() throws IOException {
    // Given a single-value engine with a histogram manager and a B-tree
    // that returns true (key was newly inserted)
    var fixture = new SingleValueFixture();
    when(fixture.sbTree.put(any(), eq(new CompositeKey("key1", 1L)), any(RID.class)))
        .thenReturn(true);

    // When put is called
    boolean result = fixture.engine.put(fixture.op, "key1", new RecordId(1, 1));

    // Then it returns true and calls onPut with wasInsert=true
    assertTrue(result);
    verify(fixture.manager).onPut(fixture.op, "key1", true, true);
  }

  @Test
  public void singleValue_put_update_callsOnPutWithWasInsertFalse() throws IOException {
    // Given a B-tree that returns false (key existed, value updated)
    var fixture = new SingleValueFixture();
    when(fixture.sbTree.put(any(), eq(new CompositeKey("key1", 1L)), any(RID.class)))
        .thenReturn(false);

    // When put is called
    boolean result = fixture.engine.put(fixture.op, "key1", new RecordId(1, 1));

    // Then it returns false and calls onPut with wasInsert=false
    assertFalse(result);
    verify(fixture.manager).onPut(fixture.op, "key1", true, false);
  }

  @Test
  public void singleValue_put_nullKey_callsOnPutWithNullKey() throws IOException {
    // Given a B-tree that accepts a null key insert.
    // convertToCompositeKey(null) produces CompositeKey(null), then
    // addKey(1L) yields CompositeKey(null, 1L).
    var fixture = new SingleValueFixture();
    when(fixture.sbTree.put(
        any(), eq(new CompositeKey((Object) null, 1L)), any(RID.class)))
        .thenReturn(true);

    // When put is called with a null key
    fixture.engine.put(fixture.op, null, new RecordId(1, 1));

    // Then onPut is called with the null key
    verify(fixture.manager).onPut(fixture.op, null, true, true);
  }

  @Test
  public void singleValue_put_noManager_worksNormally() throws IOException {
    // Given a single-value engine without a histogram manager
    var fixture = new SingleValueFixture();
    fixture.engine.setHistogramManager(null);
    when(fixture.sbTree.put(any(), eq(new CompositeKey("key1", 1L)), any(RID.class)))
        .thenReturn(true);

    // When put is called
    boolean result = fixture.engine.put(fixture.op, "key1", new RecordId(1, 1));

    // Then it still returns the correct value from the B-tree
    assertTrue(result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine: validatedPut() wiring
  //
  // After snapshot isolation, validatedPut() no longer delegates to
  // sbTree.validatedPut(). Instead it runs the full
  // iterateEntriesBetween → remove → validate → put flow inline.
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_validatedPut_insert_callsOnPutWithWasInsertTrue()
      throws IOException {
    // Given no existing entry (iterateEntriesBetween returns empty stream
    // by default) and sbTree.put returns true
    var fixture = new SingleValueFixture();
    when(fixture.sbTree.put(any(), eq(new CompositeKey("key1", 1L)), any(RID.class)))
        .thenReturn(true);

    // When validatedPut is called
    var validator = mock(
        com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator.class);
    boolean result =
        fixture.engine.validatedPut(fixture.op, "key1", new RecordId(1, 1), validator);

    // Then onPut is called with wasInsert=true
    assertTrue(result);
    verify(fixture.manager).onPut(fixture.op, "key1", true, true);
  }

  @Test
  public void singleValue_validatedPut_existingEntry_callsOnPut()
      throws IOException {
    // Given an existing entry in the B-tree (simulates update scenario).
    // With SI, the old versioned entry is removed and a new one is inserted.
    var fixture = new SingleValueFixture();
    var existingKey = new CompositeKey("key1", 0L);
    var existingRid = new RecordId(2, 2);
    when(fixture.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.of(new RawPair<>(existingKey, existingRid)));
    when(fixture.sbTree.remove(any(), eq(existingKey))).thenReturn(existingRid);
    when(fixture.sbTree.put(any(), eq(new CompositeKey("key1", 1L)), any(RID.class)))
        .thenReturn(true);

    // When validatedPut is called with a validator that accepts the change
    var validator = mock(
        com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator.class);
    boolean result =
        fixture.engine.validatedPut(fixture.op, "key1", new RecordId(1, 1), validator);

    // Then onPut is called (new versioned entry was inserted)
    assertTrue(result);
    verify(fixture.manager).onPut(fixture.op, "key1", true, true);
  }

  @Test
  public void singleValue_validatedPut_ignored_doesNotCallOnPut()
      throws IOException {
    // Given an existing entry in the B-tree and a validator that returns
    // IGNORE. The engine calls the validator at the engine level (not
    // delegated to sbTree.validatedPut) with the captured removedRID.
    var fixture = new SingleValueFixture();
    var existingKey = new CompositeKey("key1", 0L);
    var existingRid = new RecordId(2, 2);
    when(fixture.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.of(new RawPair<>(existingKey, existingRid)));

    var validator = mock(
        com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator.class);
    when(validator.validate(any(), any(), any()))
        .thenReturn(
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator.IGNORE);

    // When validatedPut is called
    boolean result =
        fixture.engine.validatedPut(fixture.op, "key1", new RecordId(1, 1), validator);

    // Then onPut is NOT called — validator returned IGNORE, no B-tree modification
    assertFalse(result);
    verify(fixture.manager, never()).onPut(any(), any(), anyBoolean(), anyBoolean());
  }

  @Test
  public void singleValue_validatedPut_noManager_worksNormally() throws IOException {
    // Given no histogram manager
    var fixture = new SingleValueFixture();
    fixture.engine.setHistogramManager(null);
    when(fixture.sbTree.put(any(), eq(new CompositeKey("key1", 1L)), any(RID.class)))
        .thenReturn(true);

    var validator = mock(
        com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator.class);
    boolean result =
        fixture.engine.validatedPut(fixture.op, "key1", new RecordId(1, 1), validator);

    assertTrue(result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine: remove() wiring
  //
  // After snapshot isolation, remove() uses iterateEntriesBetween to find
  // the existing entry, removes it, and inserts a TombstoneRID marker
  // with a new version key.
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_remove_success_callsOnRemove() throws IOException {
    // Given a B-tree with an existing entry that can be found and removed
    var fixture = new SingleValueFixture();
    var existingKey = new CompositeKey("key1", 0L);
    var existingRid = new RecordId(1, 1);
    when(fixture.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.of(new RawPair<>(existingKey, existingRid)));
    when(fixture.sbTree.remove(any(), eq(existingKey))).thenReturn(existingRid);
    // Tombstone insertion
    when(fixture.sbTree.put(any(), any(CompositeKey.class), any(RID.class)))
        .thenReturn(true);

    // When remove is called
    boolean result = fixture.engine.remove(fixture.op, "key1");

    // Then onRemove is called with isSingleValue=true
    assertTrue(result);
    verify(fixture.manager).onRemove(fixture.op, "key1", true);
  }

  @Test
  public void singleValue_remove_notFound_doesNotCallOnRemove() throws IOException {
    // Given an empty B-tree where iterateEntriesBetween returns no entries
    var fixture = new SingleValueFixture();
    when(fixture.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.empty());

    // When remove is called
    boolean result = fixture.engine.remove(fixture.op, "key1");

    // Then onRemove is NOT called (nothing was removed)
    assertFalse(result);
    verify(fixture.manager, never()).onRemove(any(), any(), anyBoolean());
  }

  @Test
  public void singleValue_remove_nullKey_callsOnRemoveWithNull() throws IOException {
    // Given an existing null-keyed entry. With SI, null keys use the same
    // path as non-null: iterateEntriesBetween → remove → tombstone.
    var fixture = new SingleValueFixture();
    var existingKey = new CompositeKey((Object) null, 0L);
    var existingRid = new RecordId(1, 1);
    when(fixture.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.of(new RawPair<>(existingKey, existingRid)));
    when(fixture.sbTree.remove(any(), eq(existingKey))).thenReturn(existingRid);
    when(fixture.sbTree.put(any(), any(CompositeKey.class), any(RID.class)))
        .thenReturn(true);

    // When remove is called with null key
    boolean result = fixture.engine.remove(fixture.op, null);

    // Then onRemove is called with null key
    assertTrue(result);
    verify(fixture.manager).onRemove(fixture.op, null, true);
  }

  @Test
  public void singleValue_remove_noManager_worksNormally() throws IOException {
    // Given no histogram manager, with an existing entry
    var fixture = new SingleValueFixture();
    fixture.engine.setHistogramManager(null);
    var existingKey = new CompositeKey("key1", 0L);
    var existingRid = new RecordId(1, 1);
    when(fixture.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.of(new RawPair<>(existingKey, existingRid)));
    when(fixture.sbTree.remove(any(), eq(existingKey))).thenReturn(existingRid);
    when(fixture.sbTree.put(any(), any(CompositeKey.class), any(RID.class)))
        .thenReturn(true);

    // When remove is called
    boolean result = fixture.engine.remove(fixture.op, "key1");

    // Then it still returns the correct result
    assertTrue(result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine: delete() wiring
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_delete_callsDeleteStatsFile() throws IOException {
    // Given a single-value engine with a histogram manager
    var fixture = new SingleValueFixture();

    // When delete is called
    fixture.engine.delete(fixture.op);

    // Then deleteStatsFile is called
    verify(fixture.manager).deleteStatsFile(fixture.op);
  }

  @Test
  public void singleValue_delete_noManager_worksNormally() {
    // Given no histogram manager
    var fixture = new SingleValueFixture();
    fixture.engine.setHistogramManager(null);

    // When delete is called — no exception
    fixture.engine.delete(fixture.op);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine: close() wiring
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_close_callsCloseStatsFile() {
    // Given a single-value engine with a histogram manager
    var fixture = new SingleValueFixture();

    // When close is called
    fixture.engine.close();

    // Then closeStatsFile is called
    verify(fixture.manager).closeStatsFile();
  }

  @Test
  public void singleValue_close_noManager_worksNormally() {
    // Given no histogram manager
    var fixture = new SingleValueFixture();
    fixture.engine.setHistogramManager(null);

    // When close is called — no exception
    fixture.engine.close();
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine: clear() wiring
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_clear_callsResetOnClear() throws IOException {
    // Given a single-value engine with a histogram manager
    var fixture = new SingleValueFixture();

    // When clear is called
    fixture.engine.clear(fixture.storage, fixture.op);

    // Then resetOnClear is called
    verify(fixture.manager).resetOnClear(fixture.op);
  }

  @Test
  public void singleValue_clear_noManager_worksNormally() {
    // Given no histogram manager
    var fixture = new SingleValueFixture();
    fixture.engine.setHistogramManager(null);

    // When clear is called — no exception
    fixture.engine.clear(fixture.storage, fixture.op);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine: put() wiring
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_put_nonNullKey_callsOnPutWithWasInsertTrue() throws IOException {
    // Given a multi-value engine with a histogram manager where B-tree reports insert
    var fixture = new MultiValueFixture();
    when(fixture.svTree.put(any(), any(CompositeKey.class), any(RID.class)))
        .thenReturn(true);

    // When put is called with a non-null key
    boolean result = fixture.engine.put(fixture.op, "key1", new RecordId(1, 1));

    // Then wasInsert propagates from B-tree and isSingleValue=false
    assertTrue(result);
    verify(fixture.manager).onPut(fixture.op, "key1", false, true);
  }

  @Test
  public void multiValue_put_nullKey_callsOnPutWithNullKey() throws IOException {
    // Given a multi-value engine with a histogram manager where null-tree reports insert
    var fixture = new MultiValueFixture();
    when(fixture.nullTree.put(any(), any(), any(RID.class)))
        .thenReturn(true);

    // When put is called with a null key
    boolean result = fixture.engine.put(fixture.op, null, new RecordId(1, 1));

    // Then onPut is called with null key, isSingleValue=false, wasInsert=true
    assertTrue(result);
    verify(fixture.manager).onPut(fixture.op, null, false, true);
  }

  @Test
  public void multiValue_put_noManager_worksNormally() throws IOException {
    // Given no histogram manager, B-tree reports insert
    var fixture = new MultiValueFixture();
    fixture.engine.setHistogramManager(null);
    when(fixture.svTree.put(any(), any(CompositeKey.class), any(RID.class)))
        .thenReturn(true);

    // When put is called
    boolean result = fixture.engine.put(fixture.op, "key1", new RecordId(1, 1));

    // Then it still succeeds
    assertTrue(result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine: remove() wiring
  //
  // After snapshot isolation, multi-value remove for null keys uses
  // iterateEntriesBetween → remove → put(tombstone) flow, similar to
  // single-value. The key is CompositeKey(NULL_KEY_SENTINEL, rid).
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_remove_nullKey_success_callsOnRemove() throws IOException {
    // Given a multi-value engine where the null-tree has an existing entry
    var fixture = new MultiValueFixture();
    var rid = new RecordId(1, 1);
    var existingKey = new CompositeKey(Long.MIN_VALUE, rid, 0L);
    when(fixture.nullTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.of(new RawPair<>(existingKey, rid)));
    when(fixture.nullTree.remove(any(), eq(existingKey))).thenReturn(rid);
    when(fixture.nullTree.put(any(), any(CompositeKey.class), any(RID.class)))
        .thenReturn(true);

    // When remove is called with a null key
    boolean result = fixture.engine.remove(fixture.op, null, rid);

    // Then onRemove is called with null key
    assertTrue(result);
    verify(fixture.manager).onRemove(fixture.op, null, false);
  }

  @Test
  public void multiValue_remove_nullKey_notFound_doesNotCallOnRemove()
      throws IOException {
    // Given a null-tree where no matching entry is found
    var fixture = new MultiValueFixture();
    var rid = new RecordId(1, 1);
    when(fixture.nullTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.empty());

    // When remove is called
    boolean result = fixture.engine.remove(fixture.op, null, rid);

    // Then onRemove is NOT called
    assertFalse(result);
    verify(fixture.manager, never()).onRemove(any(), any(), anyBoolean());
  }

  @Test
  public void multiValue_remove_noManager_worksNormally() throws IOException {
    // Given no histogram manager, null-tree has an existing entry
    var fixture = new MultiValueFixture();
    fixture.engine.setHistogramManager(null);
    var rid = new RecordId(1, 1);
    var existingKey = new CompositeKey(Long.MIN_VALUE, rid, 0L);
    when(fixture.nullTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.of(new RawPair<>(existingKey, rid)));
    when(fixture.nullTree.remove(any(), eq(existingKey))).thenReturn(rid);
    when(fixture.nullTree.put(any(), any(CompositeKey.class), any(RID.class)))
        .thenReturn(true);

    // When remove is called
    boolean result = fixture.engine.remove(fixture.op, null, rid);

    // Then it still succeeds
    assertTrue(result);
  }

  @Test
  public void multiValue_remove_nonNullKey_success_callsOnRemove() {
    // Given a multi-value engine with a svTree that returns entries
    var fixture = new MultiValueFixture();
    var rid = new RecordId(1, 1);
    var compositeKey = new CompositeKey("key1", rid, 0L);
    // Mock the iterateEntriesBetween to return one entry matching the composite
    when(fixture.svTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.of(new RawPair<>(compositeKey, rid)));
    // Mock successful removal and tombstone insertion
    try {
      when(fixture.svTree.remove(any(), any())).thenReturn(rid);
      when(fixture.svTree.put(any(), any(CompositeKey.class), any(RID.class)))
          .thenReturn(true);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // When remove is called with a non-null key
    boolean result = fixture.engine.remove(fixture.op, "key1", rid);

    // Then onRemove is called with the original key (not composite)
    assertTrue(result);
    verify(fixture.manager).onRemove(fixture.op, "key1", false);
  }

  @Test
  public void multiValue_remove_nonNullKey_notFound_doesNotCallOnRemove() {
    // Given an svTree that returns an empty stream (no matching entries)
    var fixture = new MultiValueFixture();
    var rid = new RecordId(1, 1);
    when(fixture.svTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenReturn(Stream.empty());

    // When remove is called
    boolean result = fixture.engine.remove(fixture.op, "key1", rid);

    // Then onRemove is NOT called
    assertFalse(result);
    verify(fixture.manager, never()).onRemove(any(), any(), anyBoolean());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine: delete() wiring
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_delete_callsDeleteStatsFile() throws IOException {
    // Given a multi-value engine with a histogram manager
    var fixture = new MultiValueFixture();

    // When delete is called
    fixture.engine.delete(fixture.op);

    // Then deleteStatsFile is called
    verify(fixture.manager).deleteStatsFile(fixture.op);
  }

  @Test
  public void multiValue_delete_noManager_worksNormally() {
    // Given no histogram manager
    var fixture = new MultiValueFixture();
    fixture.engine.setHistogramManager(null);

    // When delete is called — no exception
    fixture.engine.delete(fixture.op);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine: close() wiring
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_close_callsCloseStatsFile() {
    // Given a multi-value engine with a histogram manager
    var fixture = new MultiValueFixture();

    // When close is called
    fixture.engine.close();

    // Then closeStatsFile is called
    verify(fixture.manager).closeStatsFile();
  }

  @Test
  public void multiValue_close_noManager_worksNormally() {
    // Given no histogram manager
    var fixture = new MultiValueFixture();
    fixture.engine.setHistogramManager(null);

    // When close is called — no exception
    fixture.engine.close();
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine: clear() wiring
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_clear_callsResetOnClear() throws IOException {
    // Given a multi-value engine with a histogram manager
    var fixture = new MultiValueFixture();

    // When clear is called
    fixture.engine.clear(fixture.storage, fixture.op);

    // Then resetOnClear is called
    verify(fixture.manager).resetOnClear(fixture.op);
  }

  @Test
  public void multiValue_clear_noManager_worksNormally() {
    // Given no histogram manager
    var fixture = new MultiValueFixture();
    fixture.engine.setHistogramManager(null);

    // When clear is called — no exception
    fixture.engine.clear(fixture.storage, fixture.op);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Histogram manager error propagation
  // ═══════════════════════════════════════════════════════════════════════

  @Test(expected = RuntimeException.class)
  public void singleValue_put_histogramFailure_propagatesException()
      throws IOException {
    // Given a histogram manager that throws on onPut
    var fixture = new SingleValueFixture();
    when(fixture.sbTree.put(any(), eq(new CompositeKey("key1", 1L)), any(RID.class)))
        .thenReturn(true);
    doThrow(new RuntimeException("histogram error"))
        .when(fixture.manager).onPut(any(), any(), anyBoolean(), anyBoolean());

    // When put is called, the exception propagates (histogram failure is
    // not silently swallowed — it should be visible to the caller)
    fixture.engine.put(fixture.op, "key1", new RecordId(1, 1));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // BTreeIndexEngine.getHistogramManager() interface compliance
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void bTreeIndexEngine_getHistogramManager_isSatisfiedBySingleValue() {
    // The BTreeIndexEngine interface declares getHistogramManager()
    var fixture = new SingleValueFixture();
    BTreeIndexEngine iface = fixture.engine;
    // Must compile and work via the interface
    var mgr = iface.getHistogramManager();
    assertTrue(mgr instanceof IndexHistogramManager);
  }

  @Test
  public void bTreeIndexEngine_getHistogramManager_isSatisfiedByMultiValue() {
    var fixture = new MultiValueFixture();
    BTreeIndexEngine iface = fixture.engine;
    var mgr = iface.getHistogramManager();
    assertTrue(mgr instanceof IndexHistogramManager);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // V1IndexEngine.put() return type change
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void v1IndexEngine_put_returnsBoolean() throws IOException {
    // Verify the interface contract: V1IndexEngine.put() returns boolean
    var fixture = new SingleValueFixture();
    when(fixture.sbTree.put(any(), eq(new CompositeKey("key1", 1L)), any(RID.class)))
        .thenReturn(true);

    // Call via the V1IndexEngine interface
    boolean result =
        ((com.jetbrains.youtrackdb.internal.core.index.engine.V1IndexEngine) fixture.engine)
            .put(fixture.op, "key1", new RecordId(1, 1));
    assertTrue(result);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Test fixture for BTreeSingleValueIndexEngine with a mocked B-tree
   * and histogram manager.
   */
  private static class SingleValueFixture {
    final AbstractStorage storage;
    final AtomicOperation op;
    final IndexHistogramManager manager;
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<Object> sbTree =
        mock(CellBTreeSingleValue.class);
    final BTreeSingleValueIndexEngine engine;

    SingleValueFixture() {
      storage = createMockStorage();
      op = mock(AtomicOperation.class);
      // Mock getCommitTs — validatedPut/remove append version to CompositeKey
      when(op.getCommitTs()).thenReturn(1L);
      manager = mock(IndexHistogramManager.class);

      // Create engine with a mocked sbTree injected via Mockito field access
      engine = new BTreeSingleValueIndexEngine(0, "test-sv", storage, 4);
      // Replace the real sbTree with our mock via reflection
      injectField(engine, "sbTree", sbTree);
      engine.setHistogramManager(manager);
    }
  }

  /**
   * Test fixture for BTreeMultiValueIndexEngine with mocked trees
   * and histogram manager.
   */
  private static class MultiValueFixture {
    final AbstractStorage storage;
    final AtomicOperation op;
    final IndexHistogramManager manager;
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<Object> svTree =
        mock(CellBTreeSingleValue.class);
    @SuppressWarnings("unchecked")
    final CellBTreeSingleValue<Object> nullTree =
        mock(CellBTreeSingleValue.class);
    final BTreeMultiValueIndexEngine engine;

    MultiValueFixture() {
      storage = createMockStorage();
      op = mock(AtomicOperation.class);
      // Mock getCommitTs — remove appends version to CompositeKey
      when(op.getCommitTs()).thenReturn(1L);
      manager = mock(IndexHistogramManager.class);

      engine = new BTreeMultiValueIndexEngine(0, "test-mv", storage, 4);
      injectField(engine, "svTree", svTree);
      injectField(engine, "nullTree", nullTree);
      engine.setHistogramManager(manager);
    }
  }

  private static AbstractStorage createMockStorage() {
    var storage = mock(AbstractStorage.class);
    var factory = new CurrentStorageComponentsFactory(
        BinarySerializerFactory.currentBinaryFormatVersion());
    when(storage.getComponentsFactory()).thenReturn(factory);
    when(storage.getAtomicOperationsManager())
        .thenReturn(mock(AtomicOperationsManager.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.subIndexSnapshot(anyLong())).thenReturn(mock(IndexesSnapshot.class));
    return storage;
  }

  private static void injectField(Object target, String fieldName, Object value) {
    try {
      var field = findField(target.getClass(), fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Failed to inject field " + fieldName, e);
    }
  }

  private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(name);
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new RuntimeException("Field " + name + " not found");
  }
}
