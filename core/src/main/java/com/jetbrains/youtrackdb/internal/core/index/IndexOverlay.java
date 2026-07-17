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
   * Committed classes renamed inside the transaction, mapping the pre-transaction (committed)
   * class name to the name the class holds NOW in the tx-local view. Names are unstable
   * identities inside a transaction (a vacated name can be reused by a new class, chains and
   * swaps re-shuffle them), so the bookkeeping keeps one invariant: every entry's key is the
   * committed name of a LIVE committed-origin class and its value is that same class's current
   * name — maintained by advancing the entry on every further rename of the class (matched by
   * current name), skipping renames of impostor classes squatting a vacated key, and purging the
   * entry when the class is dropped ({@link #recordClassDropped}). The re-association of the
   * committed indexes is applied commit-only; transaction-private deferred handles are instead
   * fixed eagerly at record time (event-time semantics — the only reading that stays correct
   * under chains, swaps, and name reuse).
   */
  private final Map<String, String> classRenames = new HashMap<>();

  /**
   * Committed class names whose class was dropped inside this transaction (directly, or after
   * renames — the drop purge resolves the current name back to the committed key). A retired name
   * must never be recorded as a rename source again: any later class holding that name is a new,
   * never-committed class whose renames concern no committed index. Never stale: a dropped class
   * cannot come back within the transaction, and a live entry's rename-back removes only its
   * {@link #classRenames} entry, never retires the name.
   */
  private final Set<String> retiredCommittedClassNames = new HashSet<>();

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
   * class's indexes). Two things happen here, both at EVENT time because a rename identifies its
   * class unambiguously only at the moment it runs (exactly one tx-local class holds
   * {@code oldName} then):
   *
   * <ul>
   *   <li>every transaction-private deferred index handle whose definition names {@code oldName}
   *       is re-associated in place — fixing handles eagerly is the only reading that stays
   *       correct for an index created at a mid-chain name, under swap-shaped renames, and when a
   *       vacated name is reused (a commit-time fix keyed by pre-transaction names cannot tell
   *       those apart);
   *   <li>the committed-class rename map advances: an entry whose VALUE is {@code oldName} means
   *       the renamed class is that entry's committed-origin class, so the entry's value moves to
   *       {@code newName} (a move back to the committed name drops the entry — net no-op).
   *       Otherwise, when {@code oldName} is a vacated map KEY or a retired (dropped) committed
   *       name, the renamed class is an impostor squatting a recycled name and no committed index
   *       is affected — nothing is recorded. Otherwise a fresh entry is added ({@code oldName}
   *       may also be a never-committed name; the commit finds no committed indexes under it and
   *       the entry is harmless).
   * </ul>
   *
   * @param oldName the class name before this rename.
   * @param newName the class name after this rename.
   */
  public void recordClassRenamed(@Nonnull String oldName, @Nonnull String newName) {
    // Event-time deferred-handle fix: exactly the handles currently naming oldName belong to the
    // class being renamed, whatever its history.
    for (final var handle : txCreated.values()) {
      ((IndexAbstract) handle).reassociateDeferredClassName(oldName, newName);
    }

    final var committedSource = getClassRenameSource(oldName);
    if (committedSource != null) {
      if (committedSource.equals(newName)) {
        classRenames.remove(committedSource);
      } else {
        classRenames.put(committedSource, newName);
      }
      return;
    }
    if (classRenames.containsKey(oldName) || retiredCommittedClassNames.contains(oldName)) {
      // The committed class of this name was renamed away or dropped earlier in this transaction;
      // the class being renamed now is a new class recycling the name — no committed index moves.
      return;
    }
    if (oldName.equals(newName)) {
      return;
    }
    classRenames.put(oldName, newName);
  }

  /**
   * Records that the class currently named {@code currentName} was dropped inside this
   * transaction, keeping the rename bookkeeping sound: a dropped committed-origin class's rename
   * entry is purged (its committed indexes must not be re-associated to a name another class may
   * later take) and its committed name is retired so a later class recycling it never reads as
   * the committed class. Dropping an impostor that squats a vacated key purges nothing — the
   * vacated key still describes the live committed class that moved away.
   *
   * <p>Contract with the drop producer ({@code SchemaEmbedded.dropClassInternal}, tx-local
   * branch): the caller records the dropped class's index drops FIRST — via
   * {@link #recordDropped} for each index the rename-aware effective lookup resolves — and only
   * then invokes this hook. The ordering is load-bearing: this hook purges the class's rename
   * entry, and the caller's effective-index lookup needs that entry to find committed indexes
   * still keyed under a pre-rename name. This hook itself touches only the rename bookkeeping,
   * never the drop category.
   *
   * @param currentName the dropped class's name at drop time.
   */
  public void recordClassDropped(@Nonnull String currentName) {
    final var committedSource = getClassRenameSource(currentName);
    if (committedSource != null) {
      // The dropped class is the committed-origin class of this entry: purge and retire.
      classRenames.remove(committedSource);
      retiredCommittedClassNames.add(committedSource);
      return;
    }
    if (classRenames.containsKey(currentName)) {
      // The dropped class squatted a vacated name; the committed class of that name lives on
      // under its renamed name. Nothing to purge or retire.
      return;
    }
    // The dropped class held its own (possibly committed) name: retire it. Retiring a
    // never-committed name is harmless — nothing commits under it anyway.
    retiredCommittedClassNames.add(currentName);
  }

  /**
   * Whether {@code className} was renamed AWAY inside this transaction — i.e. the committed
   * {@code classPropertyIndex} entries under this name no longer belong to any tx-visible class.
   */
  public boolean isClassRenamedAway(@Nonnull String className) {
    return classRenames.containsKey(className);
  }

  /**
   * The pre-transaction class name that was renamed TO {@code className} inside this transaction,
   * or {@code null} when no rename targets it. The committed {@code classPropertyIndex} entries
   * under the returned name belong to {@code className} in the transaction's view. Deterministic:
   * entry values are current names of distinct live classes, so at most one entry can match
   * (drops purge their entry before a name can be re-targeted).
   */
  @Nullable public String getClassRenameSource(@Nonnull String className) {
    for (final var entry : classRenames.entrySet()) {
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

  /**
   * A read-only view of the class renames (committed class name &rarr; current tx-local name).
   */
  @Nonnull
  public Map<String, String> getClassRenames() {
    return Map.copyOf(classRenames);
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
      // Plain equality suffices: deferred handles are re-associated EAGERLY at each rename
      // ({@link #recordClassRenamed}), so a handle always carries its class's current tx-local
      // name.
      if (definition != null && className.equals(definition.getClassName())) {
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
    // retiredCommittedClassNames is deliberately excluded: it is record-time bookkeeping (it only
    // guards future recordings), never a commit-consumable or read-resolvable delta.
    return txCreated.isEmpty()
        && txDropped.isEmpty()
        && classRenames.isEmpty()
        && membershipAdded.isEmpty()
        && membershipRemoved.isEmpty();
  }
}
