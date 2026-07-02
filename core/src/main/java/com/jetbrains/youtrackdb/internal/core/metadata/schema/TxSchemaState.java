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
     *  *  Unless required by applicable law or agreed to in writing, software
     *  *  distributed under the License is distributed on an "AS IS" BASIS,
     *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     *  *  See the License for the specific language governing permissions and
     *  *  limitations under the License.
     *  *
     *
     *
     */
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.index.IndexOverlay;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-session, transaction-scoped schema state for a schema-changing transaction. While a
 * transaction holds one of these, a schema mutation lands in {@link #txLocalSchema} (a private copy
 * of the committed {@link SchemaShared}) instead of the shared committed instance, so the change is
 * visible only to the owning transaction and rolls back for free.
 *
 * <p>The tx-local copy is a full working {@link SchemaShared} re-parsed from the committed schema
 * (see {@link SchemaShared#copyForTx}), not a field-level clone of the committed class objects.
 * Re-parsing is required because each class binds back to its {@link SchemaShared} through a final
 * owner field and links to its relatives by direct object reference; a clone would leave those
 * references pointing at the shared instances. The copy reuses the existing mutation machinery, so a
 * schema write recomputes the cross-class derived state (inheritance, polymorphic collection ids,
 * subclass sets, the global-property table) inside the copy with no new code.
 *
 * <p>{@link #changedClasses} records the names of classes the transaction touched, so the commit can
 * write only the changed per-class records (the per-class-record format) rather than the whole
 * schema. The names are stable across a class rename within the transaction only insofar as the same
 * mutation that renames also records the new name here.
 *
 * <p>This state also carries the tx-local {@link IndexOverlay} (see {@link #getIndexOverlay}), which
 * holds the transaction's index-definition deltas (created, dropped, renamed, collection-membership)
 * without copying the shared index manager's maps or any engine-backed {@code Index} object. The
 * overlay is created lazily on the first index change, so a schema-only transaction that never
 * touches an index never allocates one. Routing reads and writes to the schema copy, seeding it on
 * the first schema write, and promoting both the schema and the overlay at commit are the
 * responsibility of the proxy-routing, index-manager-routing, and commit-reconciliation work, not of
 * this holder.
 *
 * <p>A class created inside the transaction allocates no real storage collection during the
 * transaction; it carries a provisional id drawn from the {@code <= -2} sub-range (see
 * {@link SchemaShared#PROVISIONAL_COLLECTION_ID_CEILING}). This state owns the per-transaction
 * provisional-id allocator ({@link #allocateProvisionalCollectionId}) and the provisional&rarr;real
 * mapping ({@link #recordResolvedCollectionId} / {@link #getResolvedCollectionId}) that the commit
 * populates once it creates the real collections.
 */
public final class TxSchemaState {

  /**
   * The "no resolution recorded yet" sentinel {@link #getResolvedCollectionId} returns on a miss.
   * {@link Integer#MIN_VALUE} is deliberately distinct from every meaningful collection-id value: a
   * real id ({@code >= 0}), the abstract-class marker {@code -1}
   * ({@link SchemaShared#ABSTRACT_COLLECTION_ID}), and every provisional id the allocator hands out
   * (which start at {@code -2} and decrement, so they never reach {@link Integer#MIN_VALUE} — the
   * allocator asserts it stays in range). Using a dedicated sentinel rather than {@code -1} keeps a
   * not-resolved result from being misread as "resolved to the abstract collection" by the
   * commit-time consumer.
   */
  public static final int NO_RESOLUTION = Integer.MIN_VALUE;

  private final SchemaShared txLocalSchema;
  private final Set<String> changedClasses = new HashSet<>();

  /**
   * The tx-local index-definition overlay, created lazily on the first index change (see
   * {@link #getIndexOverlay}). Stays {@code null} for a transaction that changes only the schema and
   * never an index, so no overlay is allocated on the common schema-only path.
   */
  @Nullable private IndexOverlay indexOverlay;

  /**
   * The session-private immutable snapshot built for the current overlay generation, or {@code null}
   * when none has been built since the overlay last changed. While an index overlay is active,
   * {@link SchemaProxy#makeSnapshot()} builds an uncached snapshot that is never stored in the shared
   * {@link SchemaShared#snapshot} cache; without this memo every unpinned read
   * ({@code EntityImpl.getImmutableSchemaClass} reads unpinned, and {@code executeReadRecord} opens
   * and closes its pin per record) would rebuild the whole {@link ImmutableSchema}, so a same-tx
   * DDL-then-DML touching N records would rebuild it up to N times. Memoizing here reuses the built
   * snapshot across intervening unpinned reads while keeping it session-scoped: it lives on this
   * per-transaction state, never in the process-shared cache, so a concurrent session never observes
   * it, and it is dropped with this {@code TxSchemaState} when the transaction ends (commit or
   * rollback). A mid-transaction index change still forces exactly one rebuild by nulling this memo
   * through {@link #invalidateOverlaySnapshot()} in {@code forceRebuildSchemaSnapshotForIndexOverlay}.
   */
  @Nullable private ImmutableSchema overlaySnapshot;

  /**
   * The next provisional collection id to hand out. Starts at the ceiling
   * ({@link SchemaShared#PROVISIONAL_COLLECTION_ID_CEILING}, {@code -2}) and decrements on each
   * allocation, so successive provisional ids are {@code -2, -3, -4, ...} — each unique within the
   * transaction and disjoint from the abstract-class marker {@code -1}.
   */
  private int nextProvisionalCollectionId = SchemaShared.PROVISIONAL_COLLECTION_ID_CEILING;

  /**
   * Maps each provisional collection id this transaction allocated to the real id the commit
   * assigned it. Empty until the commit-time reconciliation creates the real collections and records
   * the resolution; the resolution patches every provisional reference to its real id before any
   * record serializes.
   */
  private final Int2IntOpenHashMap provisionalToReal = new Int2IntOpenHashMap();

  /**
   * Maps each provisional collection id this transaction allocated to the {@code <class>_<counter>}
   * name computed at allocation time. The commit creates the real collection under this name, so the
   * name must be carried from the producer to the commit: the tx-local collection counter has already
   * advanced past it by commit time, so the commit cannot regenerate it. Empty until a class create
   * (or an abstract&rarr;concrete alter) allocates its first provisional id.
   */
  private final Int2ObjectOpenHashMap<String> provisionalToName = new Int2ObjectOpenHashMap<>();

  /**
   * @param txLocalSchema the tx-local {@link SchemaShared} copy, seeded by
   *     {@link SchemaShared#copyForTx}. Must be a fresh copy private to the owning transaction, never
   *     the committed shared instance.
   */
  public TxSchemaState(@Nonnull SchemaShared txLocalSchema) {
    this.txLocalSchema = txLocalSchema;
    // NO_RESOLUTION (Integer.MIN_VALUE) is the "no resolution recorded" sentinel
    // getResolvedCollectionId returns on a miss. It is disjoint from every real id (>= 0), the
    // abstract marker (-1), and every provisional id the allocator hands out, so a not-resolved
    // result is never confused with a meaningful collection id.
    this.provisionalToReal.defaultReturnValue(NO_RESOLUTION);
  }

  /**
   * The tx-local {@link SchemaShared} copy that schema reads and writes route to during the
   * transaction.
   */
  @Nonnull
  public SchemaShared getTxLocalSchema() {
    return txLocalSchema;
  }

  /**
   * Records that the named class was created, altered, or dropped in this transaction, so the commit
   * writes its per-class record. Idempotent: recording the same class twice is a no-op.
   */
  public void markClassChanged(@Nonnull String className) {
    changedClasses.add(className);
  }

  /**
   * Removes a previously recorded class name from the changed-class set. Used by a transaction-local
   * rename: the proxy write choke point ({@link SchemaProxedResource#resolveForWrite()}) records the
   * write target under the name it carries at resolution time, which for a rename is the class's old
   * name. The rename then records the new name; the old name must not survive, because a name in the
   * changed-class set that is absent from the tx-local copy reads as a drop at commit and would delete
   * the renamed class's per-class record. Removing a name that was never recorded is a no-op.
   */
  public void unmarkClassChanged(@Nonnull String className) {
    changedClasses.remove(className);
  }

  /**
   * The names of classes the transaction has changed so far. The returned set is the live backing
   * set; callers must not mutate it outside {@link #markClassChanged}.
   */
  @Nonnull
  public Set<String> getChangedClasses() {
    return changedClasses;
  }

  /**
   * The tx-local index overlay, creating it on first use. Called on the first index change in the
   * transaction (index create / drop / rename / collection-membership), so a schema-only transaction
   * that never touches an index never allocates an overlay.
   */
  @Nonnull
  public IndexOverlay ensureIndexOverlay() {
    if (indexOverlay == null) {
      indexOverlay = new IndexOverlay();
    }
    return indexOverlay;
  }

  /**
   * The tx-local index overlay, or {@code null} when the transaction has changed no index yet. This
   * is the read-only probe the index-manager routing seam uses to decide whether the transaction has
   * any index delta to resolve against; it never creates the overlay.
   */
  @Nullable public IndexOverlay getIndexOverlay() {
    return indexOverlay;
  }

  /**
   * The memoized session-private snapshot for the current overlay generation, or {@code null} when
   * none has been built since the overlay last changed. {@link SchemaProxy#makeSnapshot()} calls this
   * on the overlay-active branch and builds-then-{@link #setOverlaySnapshot memoizes} on a miss, so
   * intervening unpinned reads reuse the built snapshot instead of rebuilding it per call.
   */
  @Nullable public ImmutableSchema getOverlaySnapshot() {
    return overlaySnapshot;
  }

  /**
   * Memoizes the session-private snapshot built for the current overlay generation. The snapshot is
   * session-scoped (it lives only here, never in the shared {@link SchemaShared#snapshot} cache), so
   * memoizing it does not make an overlay-dependent view visible to a concurrent session.
   */
  public void setOverlaySnapshot(@Nonnull ImmutableSchema snapshot) {
    this.overlaySnapshot = snapshot;
  }

  /**
   * Drops the memoized overlay snapshot so the next {@link SchemaProxy#makeSnapshot()} on the
   * overlay-active branch rebuilds it. Called from {@code forceRebuildSchemaSnapshotForIndexOverlay}
   * on every mid-transaction index change, so a change to the overlay's contents forces exactly one
   * rebuild while unpinned reads between changes reuse the built snapshot.
   */
  public void invalidateOverlaySnapshot() {
    this.overlaySnapshot = null;
  }

  /**
   * Allocates the next provisional collection id for a class created inside this transaction,
   * carrying the {@code <class>_<counter>} name the commit creates the real collection under. The
   * ids run {@code -2, -3, -4, ...}: each is unique within the transaction (the counter never
   * repeats) and is disjoint from the abstract-class marker {@code -1}. The id is a placeholder a
   * record can carry through the transaction; the commit creates the real collection (under
   * {@code collectionName}) and resolves the provisional id to its real id before any record
   * serializes.
   *
   * <p>The name must be carried here because the producer computes it from the tx-local collection
   * counter, which has already advanced by commit time, so the commit cannot regenerate it.
   *
   * @param collectionName the {@code <class>_<counter>} name the commit creates the real collection
   *     under; must be non-null.
   */
  public int allocateProvisionalCollectionId(@Nonnull String collectionName) {
    var allocated = nextProvisionalCollectionId--;
    // Guard the (practically unreachable) wrap past Integer.MIN_VALUE: a wrapped counter would
    // hand out a non-negative id that the downstream sign-class predicate reclassifies as a real
    // id, silently corrupting the tx-local reverse map. The assert is zero-cost in production and
    // mirrors the invariant recordResolvedCollectionId asserts on the resolution side.
    assert SchemaShared.isProvisionalCollectionId(allocated)
        : "provisional collection id space exhausted, allocator produced " + allocated;
    provisionalToName.put(allocated, collectionName);
    return allocated;
  }

  /**
   * The {@code <class>_<counter>} name recorded for {@code provisionalCollectionId} when it was
   * allocated, or {@code null} when no name was recorded for it. The commit creates the real
   * collection under this name.
   *
   * @param provisionalCollectionId a provisional id this transaction previously allocated (must be
   *     {@code <= -2}).
   */
  @Nonnull
  public String getProvisionalCollectionName(int provisionalCollectionId) {
    assert SchemaShared.isProvisionalCollectionId(provisionalCollectionId)
        : "only a provisional id (<= -2) carries a provisional name, got "
            + provisionalCollectionId;
    final var name = provisionalToName.get(provisionalCollectionId);
    assert name != null
        : "no provisional collection name recorded for id " + provisionalCollectionId;
    return name;
  }

  /**
   * A read-only view of the provisional&rarr;name map, for the commit-time reconciliation to read
   * the name each provisional collection must be created under. The view is unmodifiable: names are
   * recorded only through {@link #allocateProvisionalCollectionId(String)}.
   */
  @Nonnull
  public Int2ObjectMap<String> getProvisionalCollectionNames() {
    return Int2ObjectMaps.unmodifiable(provisionalToName);
  }

  /**
   * Records that the commit resolved a provisional collection id to a real one. Idempotent for a
   * given provisional id only insofar as the caller passes a consistent real id; recording two
   * different real ids for the same provisional id is a commit-reconciliation bug.
   *
   * @param provisionalCollectionId a provisional id this transaction previously allocated (must be
   *     {@code <= -2})
   * @param realCollectionId the non-negative real id the commit created for it
   */
  public void recordResolvedCollectionId(int provisionalCollectionId, int realCollectionId) {
    assert SchemaShared.isProvisionalCollectionId(provisionalCollectionId)
        : "only a provisional id (<= -2) can be resolved, got " + provisionalCollectionId;
    assert realCollectionId >= 0
        : "a provisional id must resolve to a non-negative real id, got " + realCollectionId;
    provisionalToReal.put(provisionalCollectionId, realCollectionId);
  }

  /**
   * The real collection id the commit assigned to {@code provisionalCollectionId}, or
   * {@link #NO_RESOLUTION} when no resolution has been recorded for it yet. The consumer must test
   * for resolution against {@link #NO_RESOLUTION} (or via {@link #getResolvedCollectionIds}
   * {@code containsKey}), never against {@code -1}, because {@code -1} is the abstract-class marker
   * elsewhere in the schema.
   */
  public int getResolvedCollectionId(int provisionalCollectionId) {
    return provisionalToReal.get(provisionalCollectionId);
  }

  /**
   * A read-only view of the provisional&rarr;real resolution map, for the commit-time reconciliation
   * to read and the patch list to apply. The view is unmodifiable: writes must route through
   * {@link #recordResolvedCollectionId}, which validates the provisional and real id ranges. The
   * view's {@code defaultReturnValue} is {@link #NO_RESOLUTION}, so a {@code get} for an unresolved
   * id returns the same not-resolved sentinel as {@link #getResolvedCollectionId}.
   */
  @Nonnull
  public Int2IntMap getResolvedCollectionIds() {
    return Int2IntMaps.unmodifiable(provisionalToReal);
  }
}
