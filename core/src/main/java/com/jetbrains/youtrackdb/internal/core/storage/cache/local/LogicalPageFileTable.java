package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import java.util.HashMap;
import java.util.Map;

/**
 * Layout 28 disk-cache shadow inventory. Callers supply existing file or lifecycle exclusion.
 * Lookups never probe disk. Reservations and deletions are distinct session-only states.
 */
final class LogicalPageFileTable {

  // Extents are deliberately unavailable until Track 1.2 installs allocation/write accounting.
  // No code may read these as cache or disk lengths.
  static final long UNTRACKED_EXTENT = -1;

  enum Kind {
    LIVE, RESERVED, DELETED
  }

  record State(PageFileIdentity identity, long logicalExtent, long diskExtent,
      boolean diskPresent, boolean nonDurable, Kind kind) {
  }

  private final Map<Integer, State> states = new HashMap<>();

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
    put(name, expectedId, nonDurable, Kind.LIVE);
  }

  private void put(String name, int expectedId, boolean nonDurable, Kind kind) {
    var identity = resolve(name, expectedId);
    states.put(expectedId, new State(identity, UNTRACKED_EXTENT, UNTRACKED_EXTENT,
        kind == Kind.LIVE, nonDurable, kind));
  }

  // A rename to an alias removes canonical membership, not the physical file.
  void forget(int internalId) {
    states.remove(internalId);
  }

  State live(String name, int expectedId) {
    resolve(name, expectedId);
    var state = states.get(expectedId);
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
