package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;

/**
 * Shared assertion helpers for the tx-result-cache test suites. Keeps a single copy of small probes
 * that more than one cache test class needs (e.g. asserting a cache holds exactly one entry), instead
 * of duplicating them per suite.
 */
final class CacheTestSupport {

  private CacheTestSupport() {
  }

  /** Returns the single cache entry, asserting there is exactly one. */
  static CachedEntry onlyEntry(QueryResultCache cache) {
    var entries = cache.entriesForTest();
    assertEquals("expected exactly one cache entry", 1, entries.size());
    return entries.iterator().next();
  }
}
