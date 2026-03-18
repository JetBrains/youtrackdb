package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * LRU cache for already parsed GQL statements using Guava Cache.
 * Eliminates TOCTOU race between cache lookup and parse via atomic cache.get(key, loader).
 */
public class GqlStatementCache {

  private final int capacity;
  private final Cache<String, GqlStatement> cache;

  public GqlStatementCache(int size) {
    this.capacity = size;
    this.cache = size > 0
        ? CacheBuilder.newBuilder().maximumSize(size).build()
        : null;
  }

  public static @Nonnull GqlStatement get(@Nonnull String statement,
      @Nullable DatabaseSessionEmbedded session) {
    if (session == null) {
      return parse(statement);
    }

    var resource = session.getSharedContext().getGqlStatementCache();
    return resource.getCached(statement);
  }

  protected static @Nonnull GqlStatement parse(@Nonnull String statement) {
    return GqlPlanner.parse(statement);
  }

  @SuppressWarnings("unused")
  public boolean contains(@Nonnull String statement) {
    if (capacity == 0) {
      return false;
    }
    return cache.asMap().containsKey(statement);
  }

  public @Nonnull GqlStatement getCached(@Nonnull String statement) {
    if (capacity == 0) {
      return parse(statement);
    }

    try {
      // Atomic cache.get(key, loader) eliminates TOCTOU race:
      // only one thread will parse for a given key, others will wait and reuse the result
      return cache.get(statement, () -> parse(statement));
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse GQL statement: " + statement, e);
    }
  }

  public void clear() {
    if (cache != null) {
      cache.invalidateAll();
    }
  }
}
