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
package com.jetbrains.youtrackdb.internal.core.index;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Transaction-local overlay of index definitions for one schema- or index-changing transaction. It
 * carries the deltas a transaction applies to the shared index manager's two lookup maps
 * ({@code indexes} and {@code classPropertyIndex}) without copying either map or the engine-backed
 * {@code Index} objects. An index is a thin handle over a storage-backed engine, so there is no
 * in-memory content to deep-copy the way the schema view (D8) copies {@code SchemaShared}; the
 * overlay holds only the definition-level deltas and the shared manager keeps every committed
 * {@code Index} untouched until commit.
 *
 * <p>The overlay records four categories:
 *
 * <ul>
 *   <li><b>tx-created</b> — an index created inside the transaction. Its {@code Index} is a deferred
 *       handle whose engine is not built ({@code getIndexId() < 0}); the commit builds the engine
 *       and registers the handle. Held here so the tx-local snapshot resolves the new index into the
 *       owning class's raw index set, which is what makes a same-transaction insert into the indexed
 *       class track the new index instead of silently dropping it (the regression the snapshot
 *       force-rebuild and this category exist to prevent).
 *   <li><b>tx-dropped</b> — an index dropped inside the transaction. The shared {@code Index} stays
 *       registered (the commit removes it), so the overlay hides the name from the effective set.
 *   <li><b>class rename</b> — a class renamed inside the transaction (old class name &rarr; new
 *       class name). The re-association is commit-only metadata (D17): the commit rewrites every
 *       affected definition's {@code className} through replacement objects and re-keys
 *       {@code classPropertyIndex}; no shared {@code Index} is mutated mid-transaction. The
 *       index's own NAME does not change — the inert index-name rename is deferred to YTDB-1066
 *       and will grow its own category.
 *   <li><b>collection-membership</b> — a collection added to or removed from a committed index (the
 *       polymorphic ripple from {@code addSuperClass} or an alter that adds a collection to a class
 *       whose superclass is indexed). The shared {@code Index.collectionsToIndex} is not mutated
 *       mid-transaction; the commit persists the delta so the parent index then covers the new
 *       subclass collection.
 * </ul>
 *
 * <p>The overlay is <em>definition-only</em> and never mutates a shared {@code Index} object. All
 * engine work — building a tx-created index, dropping a tx-dropped index's engine, persisting a
 * membership delta — is deferred to commit. A rollback discards the overlay with the transaction, so
 * none of these deltas reach the shared manager.
 *
 * <p>Not thread-safe: an overlay belongs to a single session's single transaction and is never
 * shared across threads.
 */
public final class IndexOverlay {

  /**
   * Indexes created inside the transaction, keyed by index name. The value is the deferred handle
   * returned by {@link IndexManagerEmbedded#createIndex} (engine unbuilt, {@code getIndexId() < 0}).
   */
  private final Map<String, Index> txCreated = new HashMap<>();

  /** Names of indexes dropped inside the transaction; hidden from the effective set. */
  private final Set<String> txDropped = new HashSet<>();

  /**
   * Classes renamed inside the transaction, mapping the pre-transaction (old) class name to the
   * name the class holds at commit. Chained renames collapse at record time (A&rarr;B then
   * B&rarr;C stores A&rarr;C; a rename back to the original drops the entry), so each entry's key
   * is a name the committed {@code classPropertyIndex} may actually hold and its value is the
   * final tx-local name. The re-association is applied commit-only.
   */
  private final Map<String, String> renamed = new HashMap<>();

  /**
   * Collection-membership additions per committed index name (index name &rarr; set of collection
   * names added). Persisted at commit so the parent index covers the new subclass collection.
   */
  private final Map<String, Set<String>> membershipAdded = new HashMap<>();

  /**
   * Collection-membership removals per committed index name (index name &rarr; set of collection
   * names removed). Persisted at commit so the parent index stops covering the removed collection.
   */
  private final Map<String, Set<String>> membershipRemoved = new HashMap<>();

  /**
   * Records an index created inside the transaction. The handle is the deferred (engine-unbuilt)
   * {@code Index}; the commit builds its engine and registers it in the shared manager.
   *
   * <p>A create after a drop of the same committed name is a <em>replace</em>: the name stays in
   * {@code txDropped} (so the old committed engine is deleted at commit) while the new handle is added
   * to {@code txCreated} (so the new engine is built). {@link #resolveClassRawIndexes} already hides
   * the dropped committed index and surfaces the new one, and the commit runs drops before creates so
   * the old engine's name-map entry clears before the new build's duplicate-name guard. The drop is
   * NOT cancelled here: a committed name reaches {@code txDropped} only through {@link #recordDropped},
   * which cancels a same-tx create instead of recording a drop, so a name in {@code txDropped} always
   * names a committed index whose old engine must be deleted before the recreate.
   *
   * @param index the deferred handle; must carry a non-null name.
   */
  public void recordCreated(@Nonnull Index index) {
    final var name = index.getName();
    txCreated.put(name, index);
  }

  /**
   * Records an index dropped inside the transaction. The shared {@code Index} is left registered
   * until commit; the overlay hides the name from the effective set. If the name was created earlier
   * in this same transaction (never committed), the create is simply undone rather than recorded as a
   * drop, because dropping a tx-created index leaves the shared manager with nothing to remove.
   *
   * @param indexName the dropped index's name.
   */
  public void recordDropped(@Nonnull String indexName) {
    // Membership deltas recorded for the dropped name are moot: the commit's enroll phase deletes
    // the dropped index's metadata record before the membership loops run, so an unpurged delta
    // would re-write that just-deleted record and fail the commit with a record-not-found error on
    // a perfectly legal same-tx sequence (e.g. create a subclass under an indexed parent — which
    // ripples a membership add for the parent index — then drop that index).
    membershipAdded.remove(indexName);
    membershipRemoved.remove(indexName);
    if (txCreated.remove(indexName) != null) {
      // A create-then-drop within the same transaction: the index never reached the shared manager,
      // so there is nothing to hide or to delete at commit. Removing the tx-created entry is enough.
      return;
    }
    txDropped.add(indexName);
  }

  /**
   * Records a class renamed inside the transaction (the D17 commit-only re-association of the
   * class's indexes). Chained renames collapse: when {@code oldName} is itself the target of an
   * earlier rename in this transaction, that earlier entry's value advances to {@code newName}
   * instead of recording an intermediate name the committed maps never held — and a rename back
   * to the original name drops the entry entirely (net no-op).
   *
   * @param oldName the class name before this rename.
   * @param newName the class name after this rename.
   */
  public void recordRenamed(@Nonnull String oldName, @Nonnull String newName) {
    final var earlierSource = getClassRenameSource(oldName);
    if (earlierSource != null) {
      if (earlierSource.equals(newName)) {
        renamed.remove(earlierSource);
      } else {
        renamed.put(earlierSource, newName);
      }
      return;
    }
    if (oldName.equals(newName)) {
      return;
    }
    renamed.put(oldName, newName);
  }

  /**
   * Whether {@code className} was renamed AWAY inside this transaction — i.e. the committed
   * {@code classPropertyIndex} entries under this name no longer belong to any tx-visible class.
   */
  public boolean isClassRenamedAway(@Nonnull String className) {
    return renamed.containsKey(className);
  }

  /**
   * The pre-transaction class name that was renamed TO {@code className} inside this transaction,
   * or {@code null} when no rename targets it. The committed {@code classPropertyIndex} entries
   * under the returned name belong to {@code className} in the transaction's view.
   */
  @Nullable public String getClassRenameSource(@Nonnull String className) {
    for (final var entry : renamed.entrySet()) {
      if (entry.getValue().equals(className)) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Records that a collection was added to a committed index's membership (the polymorphic ripple).
   * Cancels a matching pending removal for the same (index, collection) pair rather than recording
   * both, so an add-after-remove within the transaction nets to no membership change.
   *
   * @param indexName      the committed index whose membership grows.
   * @param collectionName the collection added to the index.
   */
  public void recordMembershipAdded(@Nonnull String indexName, @Nonnull String collectionName) {
    final var removed = membershipRemoved.get(indexName);
    if (removed != null && removed.remove(collectionName)) {
      if (removed.isEmpty()) {
        membershipRemoved.remove(indexName);
      }
      return;
    }
    membershipAdded.computeIfAbsent(indexName, k -> new HashSet<>()).add(collectionName);
  }

  /**
   * Records that a collection was removed from a committed index's membership. Cancels a matching
   * pending addition for the same (index, collection) pair rather than recording both.
   *
   * @param indexName      the committed index whose membership shrinks.
   * @param collectionName the collection removed from the index.
   */
  public void recordMembershipRemoved(@Nonnull String indexName, @Nonnull String collectionName) {
    final var added = membershipAdded.get(indexName);
    if (added != null && added.remove(collectionName)) {
      if (added.isEmpty()) {
        membershipAdded.remove(indexName);
      }
      return;
    }
    membershipRemoved.computeIfAbsent(indexName, k -> new HashSet<>()).add(collectionName);
  }

  /** Whether the named index was created inside this transaction. */
  public boolean isTxCreated(@Nonnull String indexName) {
    return txCreated.containsKey(indexName);
  }

  /** Whether the named index was dropped inside this transaction. */
  public boolean isTxDropped(@Nonnull String indexName) {
    return txDropped.contains(indexName);
  }

  /** The deferred handle for a tx-created index, or {@code null} when the name is not tx-created. */
  @Nullable public Index getTxCreated(@Nonnull String indexName) {
    return txCreated.get(indexName);
  }

  /** The names of every index created inside this transaction. */
  @Nonnull
  public Set<String> getTxCreatedNames() {
    return new HashSet<>(txCreated.keySet());
  }

  /** The deferred handles of every index created inside this transaction. */
  @Nonnull
  public Collection<Index> getTxCreatedIndexes() {
    return new HashSet<>(txCreated.values());
  }

  /** The names of every index dropped inside this transaction. */
  @Nonnull
  public Set<String> getTxDroppedNames() {
    return new HashSet<>(txDropped);
  }

  /** A read-only view of the class renames (old class name &rarr; new class name). */
  @Nonnull
  public Map<String, String> getRenamed() {
    return Map.copyOf(renamed);
  }

  /** A read-only view of the per-index collection-membership additions. */
  @Nonnull
  public Map<String, Set<String>> getMembershipAdded() {
    return Map.copyOf(membershipAdded);
  }

  /** A read-only view of the per-index collection-membership removals. */
  @Nonnull
  public Map<String, Set<String>> getMembershipRemoved() {
    return Map.copyOf(membershipRemoved);
  }

  /**
   * Resolves a class's committed raw index set against this overlay, returning the effective set the
   * transaction sees: the committed indexes minus the ones dropped in the transaction, plus every
   * index created in the transaction whose definition names this class. The membership category does
   * not add or hide an index here — it changes which collections a committed index covers, which the
   * shared {@code Index} already reflects through its own registration, so it is a commit-time
   * concern rather than an effective-set change.
   *
   * @param className        the class whose raw index set is being resolved.
   * @param committedIndexes the committed raw indexes for the class (from the shared
   *                         {@code classPropertyIndex} map), unmodified.
   * @return the overlaid effective index set for the class.
   */
  @Nonnull
  public Set<Index> resolveClassRawIndexes(
      @Nonnull String className, @Nonnull Collection<Index> committedIndexes) {
    // LinkedHashSet keeps a deterministic iteration order for the merged set, which stabilises the
    // planner's index-candidate ordering across a rebuild.
    final Set<Index> effective = new LinkedHashSet<>();
    for (final var index : committedIndexes) {
      if (!txDropped.contains(index.getName())) {
        effective.add(index);
      }
    }
    for (final var index : txCreated.values()) {
      final var definition = index.getDefinition();
      if (definition == null || definition.getClassName() == null) {
        continue;
      }
      // A handle created BEFORE a same-tx class rename still carries the old class name (its
      // definition is re-associated only at commit), so match through the rename map: the
      // handle belongs to the class the transaction now knows under the renamed name.
      final var effectiveClassName =
          renamed.getOrDefault(definition.getClassName(), definition.getClassName());
      if (className.equals(effectiveClassName)) {
        effective.add(index);
      }
    }
    return effective;
  }

  /**
   * Whether the overlay carries any delta at all. An empty overlay is behaviourally identical to no
   * overlay, so the routing seam can skip the resolution entirely when this returns {@code true}.
   */
  public boolean isEmpty() {
    return txCreated.isEmpty()
        && txDropped.isEmpty()
        && renamed.isEmpty()
        && membershipAdded.isEmpty()
        && membershipRemoved.isEmpty();
  }
}
