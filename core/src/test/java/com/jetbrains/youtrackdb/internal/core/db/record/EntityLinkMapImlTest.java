/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *
 */
package com.jetbrains.youtrackdb.internal.core.db.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.Test;

/**
 * Behavioural pins for {@link EntityLinkMapIml} — the {@code Map<String, Identifiable>}
 * wrapper that maintains both forward (key→RID) and reverse (RID→keys) indexes alongside
 * an encoded-key cache. The class has six public ctors, three rollback paths, and four
 * mutating methods (put / remove / putInternal / replace) each emitting events through a
 * {@link com.jetbrains.youtrackdb.internal.core.record.impl.SimpleMultiValueTracker}. Tests
 * here pin: ctor variants, the UTF-8 key-size guard, the reverse-map maintenance contract,
 * the entry-set view (LinkEntrySet / LinkEntryIterator / LinkEntry), the rollback semantics,
 * and the type-safe rejection branches in {@code remove(Object, Object)} / {@code replace}.
 */
public class EntityLinkMapImlTest extends DbTestBase {

  // ---------- constructors ----------

  /**
   * The session-only ctor leaves {@code sourceRecord} unset and uses the default key-size
   * limit (64). The map starts empty and reports a non-null session via the weak ref.
   */
  @Test
  public void ctorWithSessionOnly() {
    session.begin();
    final var map = new EntityLinkMapIml(session);
    assertNull(map.getOwner());
    assertSame(session, map.getSession());
    assertTrue(map.isEmpty());
    assertEquals(0, map.size());
    session.rollback();
  }

  /**
   * The size-presizing ctor is structurally identical to the session-only ctor for our
   * external assertions. Pins the second overload so a future allocation tweak doesn't
   * regress.
   */
  @Test
  public void ctorWithSizeAndSession() {
    session.begin();
    final var map = new EntityLinkMapIml(8, session);
    assertNull(map.getOwner());
    assertTrue(map.isEmpty());
    session.rollback();
  }

  /** The record-element ctor inherits the session via {@code RecordElement.getSession()}. */
  @Test
  public void ctorWithRecordElementSetsOwner() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml((RecordElement) doc);
    assertSame(doc, map.getOwner());
    assertSame(session, map.getSession());
    session.rollback();
  }

  /** The {@code EntityImpl}-typed ctor sets the owner directly. */
  @Test
  public void ctorWithEntityImplSetsOwner() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    assertSame(doc, map.getOwner());
    session.rollback();
  }

  /** The size-presizing {@code EntityImpl} ctor matches the no-size variant on shape. */
  @Test
  public void ctorWithEntityImplAndSize() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc, 4);
    assertSame(doc, map.getOwner());
    assertTrue(map.isEmpty());
    session.rollback();
  }

  /**
   * The origin-collection ctor copies all entries via {@code putAll} → {@code put}. The
   * resulting map is equal to the source map (entry-by-entry) and the parent flips dirty
   * because {@code put} routes through {@code addEvent} → {@code setDirty}.
   */
  @Test
  public void ctorWithOriginMapCopiesEntries() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final Map<String, Identifiable> origin = new HashMap<>();
    origin.put("k", a);

    final var map = new EntityLinkMapIml(doc, origin);
    assertEquals(1, map.size());
    assertEquals(a.getIdentity(), map.get("k"));
    session.rollback();
  }

  // ---------- get / put / remove ----------

  /** {@code get(non-String)} short-circuits to {@code null} via the type guard. */
  @Test
  public void getRejectsNonStringKey() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    assertNull(map.get(42));
    assertNull(map.get((Object) null));
    session.rollback();
  }

  /** {@code put(null, …)} throws — pins the production message verbatim. */
  @Test
  public void putNullKeyRejected() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    final var ex = assertThrows(IllegalArgumentException.class, () -> map.put(null, a));
    assertEquals("null key not supported by embedded map", ex.getMessage());
    session.rollback();
  }

  /**
   * UTF-8 keys longer than the 64-byte limit are rejected. The error message names the
   * actual length so a developer fixing a regression can tell which key blew the budget.
   */
  @Test
  public void putKeyExceedingUtf8LimitRejected() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);

    final var oversized = "x".repeat(65); // 65 ASCII bytes ≥ 64-byte cap
    final var ex = assertThrows(IllegalArgumentException.class, () -> map.put(oversized, a));
    assertTrue(ex.getMessage().contains("UTF-8 encoded key size limit exceeded"));
    session.rollback();
  }

  /**
   * {@code put} on an existing key returns the old RID and emits an UPDATE event when the
   * value differs.
   */
  @Test
  public void putUpdateExistingKeyEmitsUpdateEvent() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    map.enableTracking(doc);

    final var prev = map.put("k", b);
    assertEquals(a.getIdentity(), prev);
    assertEquals(b.getIdentity(), map.get("k"));

    final var event = map.getTimeLine().getMultiValueChangeEvents().getFirst();
    assertEquals(MultiValueChangeEvent.ChangeType.UPDATE, event.getChangeType());
    session.rollback();
  }

  /**
   * {@code remove(Object)} returns null when the key is missing — the {@code containsKey}
   * fast-rejection branch.
   */
  @Test
  public void removeMissingKeyReturnsNull() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    assertNull(map.remove("missing"));
    session.rollback();
  }

  /** {@code remove(Object)} on an existing key returns the old RID and emits REMOVE. */
  @Test
  public void removeExistingKey() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    map.enableTracking(doc);

    final var removed = map.remove("k");
    assertEquals(a.getIdentity(), removed);
    assertTrue(map.isEmpty());
    assertEquals(MultiValueChangeEvent.ChangeType.REMOVE,
        map.getTimeLine().getMultiValueChangeEvents().getFirst().getChangeType());
    session.rollback();
  }

  // ---------- putInternal ----------

  /**
   * {@code putInternal} bypasses event emission and the dirty propagation. Pins the
   * no-event behaviour so a future change is forced to update either the tests or the
   * production contract.
   */
  @Test
  public void putInternalDoesNotEmitEvents() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.enableTracking(doc);

    map.putInternal("k", a);

    assertEquals(1, map.size());
    assertFalse(map.isModified());
    session.rollback();
  }

  /** {@code putInternal(null, …)} also throws. */
  @Test
  public void putInternalNullKeyRejected() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    assertThrows(IllegalArgumentException.class, () -> map.putInternal(null, a));
    session.rollback();
  }

  // ---------- clear ----------

  /** {@code clear} emits one REMOVE event per entry and empties all three indexes. */
  @Test
  public void clearEmitsRemoveEventsAndDrainsIndexes() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("a", a);
    map.put("b", b);
    map.enableTracking(doc);

    map.clear();

    assertTrue(map.isEmpty());
    assertEquals(2, map.getTimeLine().getMultiValueChangeEvents().size());
    session.rollback();
  }

  // ---------- entrySet (LinkEntrySet / LinkEntryIterator / LinkEntry) ----------

  /**
   * The entry-set view exposes one entry per put. Each entry's key/value matches the
   * source put. {@code setValue} on an entry rewrites the underlying map cell.
   */
  @Test
  public void entrySetIteratorAndSetValue() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);

    final var set = map.entrySet();
    assertEquals(1, set.size());
    final var it = set.iterator();
    assertTrue(it.hasNext());
    final var entry = it.next();
    assertEquals("k", entry.getKey());
    assertEquals(a.getIdentity(), entry.getValue());

    entry.setValue(b);
    assertEquals(b.getIdentity(), map.get("k"));
    session.rollback();
  }

  /** Iterator {@code remove} routes through {@code EntityLinkMapIml.remove(Object, Object)}. */
  @Test
  public void entrySetIteratorRemove() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);

    final var it = map.entrySet().iterator();
    assertTrue(it.hasNext());
    it.next();
    it.remove();
    assertTrue(map.isEmpty());
    session.rollback();
  }

  /**
   * Calling {@code remove} on the iterator before {@code next} throws
   * {@link IllegalStateException}.
   */
  @Test
  public void entrySetIteratorRemoveBeforeNextThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);

    final var it = map.entrySet().iterator();
    assertThrows(IllegalStateException.class, it::remove);
    session.rollback();
  }

  // ---------- remove(Object, Object) ----------

  /** {@code remove(non-String, value)} fast-fails. */
  @Test
  public void removeKeyValueRejectsNonStringKey() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    assertFalse(map.remove(42, a));
    assertEquals(1, map.size());
    session.rollback();
  }

  /** {@code remove(key, non-Identifiable)} fast-fails. */
  @Test
  public void removeKeyValueRejectsNonIdentifiableValue() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    assertFalse(map.remove("k", "not-identifiable"));
    assertEquals(1, map.size());
    session.rollback();
  }

  /** {@code remove(key, mismatching-value)} returns false; the entry is preserved. */
  @Test
  public void removeKeyValueMismatchPreservesEntry() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    assertFalse(map.remove("k", b));
    assertEquals(1, map.size());
    session.rollback();
  }

  /** {@code remove(key, value)} succeeds and emits REMOVE on the matching pair. */
  @Test
  public void removeKeyValueMatchEmitsRemove() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    map.enableTracking(doc);

    assertTrue(map.remove("k", a));
    assertTrue(map.isEmpty());
    assertEquals(MultiValueChangeEvent.ChangeType.REMOVE,
        map.getTimeLine().getMultiValueChangeEvents().getFirst().getChangeType());
    session.rollback();
  }

  // ---------- replace ----------

  /** 3-arg {@code replace} on a matching pair updates and emits UPDATE. */
  @Test
  public void replace3argMatchUpdates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    map.enableTracking(doc);

    assertTrue(map.replace("k", a, b));
    assertEquals(b.getIdentity(), map.get("k"));
    session.rollback();
  }

  /** 3-arg {@code replace} on a non-matching pair leaves the entry untouched. */
  @Test
  public void replace3argMismatchPreservesEntry() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var c = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    assertFalse(map.replace("k", b, c));
    assertEquals(a.getIdentity(), map.get("k"));
    session.rollback();
  }

  /** 2-arg {@code replace} on an existing key returns the previous RID. */
  @Test
  public void replace2argExistingKey() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);

    final var prev = map.replace("k", b);
    assertEquals(a.getIdentity(), prev);
    assertEquals(b.getIdentity(), map.get("k"));
    session.rollback();
  }

  /** 2-arg {@code replace} on a missing key returns null and does NOT add the entry. */
  @Test
  public void replace2argMissingKey() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    assertNull(map.replace("missing", a));
    assertTrue(map.isEmpty());
    session.rollback();
  }

  // ---------- contains/equals/hashCode/toString ----------

  /** {@code containsKey} / {@code containsValue} / {@code size}. */
  @Test
  public void containsKeyValueAndSize() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);

    assertTrue(map.containsKey("k"));
    assertFalse(map.containsKey("missing"));
    assertTrue(map.containsValue(a.getIdentity()));
    assertFalse(map.containsValue("not-rid"));
    assertEquals(1, map.size());
    session.rollback();
  }

  /** {@code equals} delegates to the underlying string→RID map. */
  @Test
  public void equalsDelegates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);

    assertEquals(map, map);
    assertEquals(map, Map.of("k", a.getIdentity()));
    assertNotEquals("non-map", map);
    session.rollback();
  }

  /** {@code hashCode} matches the backing map's hash. */
  @Test
  public void hashCodeMatchesBackingMap() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    assertEquals(Map.of("k", a.getIdentity()).hashCode(), map.hashCode());
    session.rollback();
  }

  /** {@code toString} delegates to the underlying map. */
  @Test
  public void toStringDelegates() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("k", a);
    assertNotNull(map.toString());
    assertTrue(map.toString().contains("k"));
    session.rollback();
  }

  // ---------- forEach ----------

  /** {@code forEach} visits each (key, value) pair exactly once. */
  @Test
  public void forEachVisitsEachPair() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.put("a", a);
    map.put("b", b);

    final var seen = new HashSet<String>();
    map.forEach((k, v) -> seen.add(k));
    assertEquals(2, seen.size());
    assertTrue(seen.contains("a"));
    assertTrue(seen.contains("b"));
    session.rollback();
  }

  // ---------- setOwner ----------

  /** {@code setOwner(null)} clears the source record. */
  @Test
  public void setOwnerNullClears() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.setOwner(null);
    assertNull(map.getOwner());
    session.rollback();
  }

  /** {@code setOwner} to a different non-null entity is rejected. */
  @Test
  public void setOwnerToDifferentEntityRejected() {
    session.begin();
    final var doc1 = (EntityImpl) session.newEntity();
    final var doc2 = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc1);
    final var ex = assertThrows(IllegalStateException.class, () -> map.setOwner(doc2));
    assertTrue(ex.getMessage().startsWith("This map is already owned by data container"));
    session.rollback();
  }

  /** {@code setOwner(non-Entity)} is rejected by {@link LinkTrackedMultiValue#checkEntityAsOwner}. */
  @Test
  public void setOwnerNonEntityRejected() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    final var fake = new EntityEmbeddedListImpl<>(doc); // not an Entity
    assertThrows(IllegalArgumentException.class, () -> map.setOwner(fake));
    session.rollback();
  }

  // ---------- addInternal ----------

  /**
   * {@code addInternal(Identifiable)} throws {@link UnsupportedOperationException}. This is
   * a contract guard — link-maps are key-addressed, not set-addressed.
   */
  @Test
  public void addInternalUnsupported() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    assertThrows(UnsupportedOperationException.class, () -> map.addInternal(a));
    session.rollback();
  }

  // ---------- rollback ----------

  /**
   * {@code rollbackChanges} on an unenabled tracker throws — the production message is
   * preserved for grep-friendliness.
   */
  @Test
  public void rollbackChangesWithoutTrackerThrows() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    final var tx = session.getActiveTransaction();
    final var ex = assertThrows(DatabaseException.class, () -> map.rollbackChanges(tx));
    assertTrue(ex.getMessage().contains("Changes are not tracked"));
    session.rollback();
  }

  /** {@code rollbackChanges} on an empty timeline is a no-op. */
  @Test
  public void rollbackChangesEmptyTimelineNoOp() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.enableTracking(doc);

    map.rollbackChanges(session.getActiveTransaction());
    assertTrue(map.isEmpty());
    session.rollback();
  }

  /**
   * {@code rollbackChanges} replays the tracker's events backwards: ADD undone, REMOVE
   * re-introduces the old binding, UPDATE restores the old value.
   */
  @Test
  public void rollbackChangesAddRemoveUpdate() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var b = (EntityImpl) session.newEntity();
    final var c = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);

    // baseline state — captured before tracking enables
    map.put("u", a); // updated later
    map.put("r", b); // removed later

    final Map<String, Identifiable> snapshot = new HashMap<>();
    snapshot.put("u", a.getIdentity());
    snapshot.put("r", b.getIdentity());

    map.enableTracking(doc);
    map.put("a", c); // ADD
    map.remove("r"); // REMOVE
    map.put("u", c); // UPDATE on existing key

    map.rollbackChanges(session.getActiveTransaction());

    assertEquals(snapshot.size(), map.size());
    assertEquals(snapshot.get("u"), map.get("u"));
    assertEquals(snapshot.get("r"), map.get("r"));
    assertFalse(map.containsKey("a"));
    session.rollback();
  }

  // ---------- transactionClear / isModified ----------

  /** {@code transactionClear} resets the tracker's per-transaction state. */
  @Test
  public void transactionClearResetsTransactionDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.enableTracking(doc);
    map.put("k", a);
    assertTrue(map.isTransactionModified());

    map.transactionClear();
    assertFalse(map.isTransactionModified());
    assertNull(map.getTransactionTimeLine());
    session.rollback();
  }

  /** {@code disableTracking} clears the local dirty flag and falls back to setDirty. */
  @Test
  public void disableTrackingFallsBackToSetDirty() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    final var map = new EntityLinkMapIml(doc);
    map.enableTracking(doc);
    map.put("k", a);
    assertTrue(map.isModified());

    map.disableTracking(doc);
    assertFalse(map.isModified());
    session.rollback();
  }
}
