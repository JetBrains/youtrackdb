package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Layout 28 disk-cache logical inventory. Callers supply existing file or lifecycle exclusion.
 * Lookups never probe disk. Reservations and deletions are distinct session-only states.
 */
final class LogicalPageFileTable {

  enum Kind {
    LIVE, RESERVED, DELETED
  }

  record State(PageFileIdentity identity, Extents extents,
      boolean diskPresent, boolean nonDurable, Kind kind) {

    long logicalExtent() {
      return extents.logical.get();
    }

    long diskExtent() {
      return extents.written.get();
    }
  }

  /** Growth is lock-free. Shrink requires the caller's existing file and flush exclusion. */
  static final class Extents {

    private final AtomicLong logical;
    private final AtomicLong written;

    Extents(long logical, long written) {
      this.logical = new AtomicLong(logical);
      this.written = new AtomicLong(written);
    }

    void allocateThrough(long pages) {
      logical.accumulateAndGet(pages, Math::max);
    }

    void writtenThrough(long pages) {
      written.accumulateAndGet(pages, Math::max);
    }

    void shrink(long pages) {
      logical.set(pages);
      written.accumulateAndGet(pages, Math::min);
    }
  }

  // Background writes consult this table without acquiring filesLock.
  private final Map<Integer, State> states = new ConcurrentHashMap<>();

  // Callers use WOWCache's existing filesLock. An interrupted submitter can release it before
  // executor deletion finishes, so inventory publication must also tolerate concurrent mutation.
  // Name lookups and background enumeration need no lock or copy.
  private final Map<String, Integer> liveNames = new ConcurrentHashMap<>();

  private record Inventory(long version, Map<String, Integer> snapshot) {
  }

  private final AtomicReference<Inventory> inventory =
      new AtomicReference<>(new Inventory(0, Map.of()));

  Map<String, Integer> inventory() {
    var current = inventory.get();
    var snapshot = current.snapshot();
    if (snapshot == null) {
      snapshot = copyLiveNames();
      // A mutation invalidates the version even when no snapshot is cached. Never let a late
      // reader publish a copy that predates that invalidation.
      inventory.compareAndSet(current, new Inventory(current.version(), snapshot));
    }
    return snapshot;
  }

  // Separate copying from publication so tests can interleave executor deletion deterministically.
  Map<String, Integer> copyLiveNames() {
    return Map.copyOf(liveNames);
  }

  Integer fileId(String name) {
    return liveNames.get(name);
  }

  Collection<Integer> liveIds() {
    return liveNames.values();
  }

  List<String> deletedFiles() {
    return states.values().stream().filter(state -> state.kind() != Kind.LIVE)
        .map(state -> PageFileNames.layout28(state.identity())).toList();
  }

  private void invalidateInventory() {
    // Mutations are constant time. Only cold inventory readers pay for an immutable copy.
    inventory.updateAndGet(current -> new Inventory(current.version() + 1, null));
  }

  static PageFileIdentity resolve(String name, int expectedId) {
    var identity = PageFileNames.parseLayout28(name).orElseThrow(
        () -> new IllegalArgumentException("Not a canonical layout 28 logical name: " + name));
    if (identity.internalId() != expectedId) {
      throw new IllegalArgumentException("Page-file name and id disagree: " + name + ", "
          + expectedId);
    }
    return identity;
  }

  void reserve(String name, int expectedId) {
    put(name, expectedId, false, Kind.RESERVED);
  }

  void delete(String name, int expectedId) {
    put(name, expectedId, false, Kind.DELETED);
  }

  void register(String name, int expectedId, boolean nonDurable) {
    register(name, expectedId, nonDurable, 0, 0, true);
  }

  void register(String name, int expectedId, boolean nonDurable, long logicalExtent,
      long diskExtent, boolean diskPresent) {
    var identity = resolve(name, expectedId);
    var previous = live(expectedId);
    // Repeated registration can update durability without discarding concurrent growth.
    var extents = previous == null ? new Extents(logicalExtent, diskExtent) : previous.extents();
    states.put(expectedId, new State(identity, extents, diskPresent, nonDurable, Kind.LIVE));
    if (previous == null) {
      liveNames.put(name, expectedId);
      invalidateInventory();
    }
  }

  private void put(String name, int expectedId, boolean nonDurable, Kind kind) {
    var identity = resolve(name, expectedId);
    var previous = states.put(expectedId, new State(identity, null, false, nonDurable, kind));
    if (previous != null && previous.kind() == Kind.LIVE) {
      liveNames.remove(name);
      invalidateInventory();
    }
  }

  // Remove session membership without changing the physical file.
  void forget(int internalId) {
    var previous = states.remove(internalId);
    if (previous != null) {
      liveNames.remove(PageFileNames.layout28(previous.identity()));
      invalidateInventory();
    }
  }

  State live(String name, int expectedId) {
    resolve(name, expectedId);
    return live(expectedId);
  }

  State live(int internalId) {
    var state = states.get(internalId);
    return state != null && state.kind() == Kind.LIVE ? state : null;
  }

  boolean reserved(int internalId) {
    var state = states.get(internalId);
    return state != null && state.kind() == Kind.RESERVED;
  }

  boolean deleted(int internalId) {
    var state = states.get(internalId);
    return state != null && state.kind() == Kind.DELETED;
  }

  void clear() {
    states.clear();
    liveNames.clear();
    invalidateInventory();
  }
}
