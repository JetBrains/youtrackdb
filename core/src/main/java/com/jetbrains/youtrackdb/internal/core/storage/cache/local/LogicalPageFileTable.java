package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Layout 28 disk-cache shadow inventory. Callers supply existing file or lifecycle exclusion.
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
  }

  private void put(String name, int expectedId, boolean nonDurable, Kind kind) {
    var identity = resolve(name, expectedId);
    states.put(expectedId, new State(identity, null, false, nonDurable, kind));
  }

  // A rename to an alias removes canonical membership, not the physical file.
  void forget(int internalId) {
    states.remove(internalId);
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
  }
}
