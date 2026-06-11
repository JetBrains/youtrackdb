package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Identity for a cached query result: the parsed statement paired with its normalised parameter
 * bindings. Two queries share a cache entry when their statements are equal and their parameters
 * are equal.
 *
 * <p><b>Statement equality.</b> {@code SQLEngine.parse()} is backed by an LRU statement cache that
 * returns the same {@link SQLStatement} instance for identical query text, so {@link #equals}
 * short-circuits on instance identity ({@code stmt == other.stmt}) before any structural walk. When
 * the statement cache has evicted and re-parsed the text, the instances differ; the deep path then
 * delegates to {@link SQLStatement#equals(Object)}, which is structural on the SELECT statement
 * (target, projection, WHERE, GROUP BY, ORDER BY, SKIP, LIMIT, and so on). The structural path is
 * what the eviction-plus-re-parse regression test exercises.
 *
 * <p><b>Parameter normalisation.</b> The two {@code query()} entry points pass either positional
 * {@code Object[]} arguments or a parameter {@code Map}. Both are normalised here to an immutable
 * {@code Map<Object, Object>}: positional arguments key on their {@link Integer} index ({@code 0},
 * {@code 1}, ...), mirroring how positional parameters bind during execution; an explicit map is
 * copied defensively so a later caller mutation cannot change a live key. {@code equals} and {@code
 * hashCode} use the normalised map, so a positional call and an equivalent index-keyed map call
 * collide on the same key.
 */
public final class CacheKey {

  private final SQLStatement statement;
  private final Map<Object, Object> params;
  private final int hash;

  /**
   * Takes ownership of {@code ownedParams}: the caller must hand in a map nothing else retains a
   * reference to (a freshly built map or {@link Collections#emptyMap()}), because this ctor wraps it
   * unmodifiable in place rather than copying it again. The factory methods below build that map and
   * never alias it, so there is exactly one map allocation per non-empty key and none for the
   * empty-param case (the shared {@link Collections#emptyMap()} singleton is unmodifiable already).
   */
  private CacheKey(@Nonnull SQLStatement statement, @Nonnull Map<Object, Object> ownedParams) {
    this.statement = statement;
    // The caller-owned map is already private and unaliased, so wrap it unmodifiable without a
    // second defensive copy. emptyMap() is itself immutable, so the wrap is a cheap no-op for it.
    this.params = ownedParams.isEmpty()
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(ownedParams);
    // Precompute the hash without Objects.hash varargs (no boxing, no Object[] allocation on the hot
    // path). Consistent with equals: two equal-but-distinct statement instances hash equally because
    // SQLStatement.hashCode() is structural, and equal params hash equally. Both inputs are immutable
    // for the key's lifetime, and the key is a HashMap key on every lookup.
    this.hash = 31 * statement.hashCode() + this.params.hashCode();
  }

  /**
   * Builds a key from positional {@code query(sql, Object...)} arguments. A {@code null} or empty
   * array yields a key with no parameters (no map allocation — the shared empty map is used).
   */
  public static CacheKey forArgs(@Nonnull SQLStatement statement, @Nullable Object[] args) {
    if (args == null || args.length == 0) {
      return new CacheKey(statement, Collections.emptyMap());
    }
    Map<Object, Object> normalized = new HashMap<>();
    for (var i = 0; i < args.length; i++) {
      normalized.put(i, args[i]);
    }
    return new CacheKey(statement, normalized);
  }

  /**
   * Builds a key from a {@code query(sql, Map)} parameter map. A {@code null} or empty map yields a
   * key with no parameters. The caller's map is copied once into a key-owned map (so a later caller
   * mutation cannot change a live key); the ctor then wraps that copy unmodifiable without a second
   * copy. Keys are taken as-is (the named/positional convention is the caller's), so a map keyed by
   * {@link Integer} indices collides with the equivalent positional call.
   */
  public static CacheKey forParams(@Nonnull SQLStatement statement,
      @Nullable Map<Object, Object> params) {
    if (params == null || params.isEmpty()) {
      return new CacheKey(statement, Collections.emptyMap());
    }
    // Defensive copy: the caller could mutate its map after the key is stored. This is the only
    // copy — the ctor wraps the result unmodifiable in place.
    return new CacheKey(statement, new HashMap<>(params));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CacheKey other)) {
      return false;
    }
    // Identity fast-path: identical query text returns the same cached SQLStatement instance,
    // so the common same-text lookup collapses to a pointer compare plus a param compare before any
    // structural statement walk runs.
    if (this.statement == other.statement) {
      return Objects.equals(this.params, other.params);
    }
    // Deep path: distinct instances (statement cache evicted and re-parsed) fall back to structural
    // statement equality. This path must never be skipped; it is what makes a re-parse hit the same
    // entry as the original parse.
    return this.statement.equals(other.statement) && Objects.equals(this.params, other.params);
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
