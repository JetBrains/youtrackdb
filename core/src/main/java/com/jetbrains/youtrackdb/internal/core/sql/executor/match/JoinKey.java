package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Arrays;
import java.util.Objects;

/**
 * Composite key used by {@link HashJoinMatchStep} to index and probe the hash table
 * during build and probe phases of a hash join.
 *
 * <p>Three internal representations optimized for the most common cases:
 * <ul>
 *   <li><b>Single RID</b> ({@link #ofRid}) — the most common case where a NOT pattern
 *       shares a single alias with the positive pattern. Uses {@link RID#equals} and
 *       {@link RID#hashCode} directly.</li>
 *   <li><b>Multiple RIDs</b> ({@link #ofRids}) — composite key when multiple aliases
 *       are shared. Uses {@link Arrays#equals(Object[])} and
 *       {@link Arrays#hashCode(Object[])} on a {@code RID[]}.</li>
 *   <li><b>Object fallback</b> ({@link #ofObjects}) — for non-RID alias values (rare).
 *       Uses {@link Arrays#deepEquals} and {@link Arrays#deepHashCode}.</li>
 * </ul>
 *
 * <p>This is a regular class (not a Java record) because records auto-generate
 * reference-equality for arrays, which would break composite key comparison.
 */
final class JoinKey {

  /**
   * Internal tag identifying which representation this key uses.
   * Avoids instanceof checks on the value field.
   */
  private enum Kind {
    SINGLE_RID, RID_ARRAY, OBJECT_ARRAY
  }

  private final Kind kind;
  private final Object value;
  private final int hash;

  private JoinKey(Kind kind, Object value, int hash) {
    this.kind = kind;
    this.value = value;
    this.hash = hash;
  }

  /**
   * Creates a key for a single shared RID alias (the common case).
   *
   * @param rid the RID value (must not be null)
   * @return a new JoinKey wrapping the single RID
   */
  static JoinKey ofRid(RID rid) {
    assert MatchAssertions.checkNotNull(rid, "join key RID");
    return new JoinKey(Kind.SINGLE_RID, rid, rid.hashCode());
  }

  /**
   * Creates a composite key for multiple shared RID aliases.
   *
   * @param rids the RID values (must not be null, must have length >= 2)
   * @return a new JoinKey wrapping the RID array
   */
  static JoinKey ofRids(RID[] rids) {
    assert MatchAssertions.checkNotNull(rids, "join key RID array");
    assert rids.length >= 2 : "use ofRid() for single-RID keys";
    var copy = rids.clone();
    return new JoinKey(Kind.RID_ARRAY, copy, Arrays.hashCode(copy));
  }

  /**
   * Creates a fallback key for non-RID alias values.
   *
   * @param values the alias values (must not be null)
   * @return a new JoinKey wrapping the object array
   */
  static JoinKey ofObjects(Object[] values) {
    assert MatchAssertions.checkNotNull(values, "join key object array");
    var copy = values.clone();
    return new JoinKey(Kind.OBJECT_ARRAY, copy, Arrays.deepHashCode(copy));
  }

  /**
   * Creates a composite RID key taking ownership of the array (no defensive copy).
   * The caller must not mutate the array after calling this method.
   */
  static JoinKey ofRidsOwned(RID[] rids) {
    assert MatchAssertions.checkNotNull(rids, "join key RID array");
    assert rids.length >= 2 : "use ofRid() for single-RID keys";
    return new JoinKey(Kind.RID_ARRAY, rids, Arrays.hashCode(rids));
  }

  /**
   * Creates a fallback key taking ownership of the array (no defensive copy).
   * The caller must not mutate the array after calling this method.
   */
  static JoinKey ofObjectsOwned(Object[] values) {
    assert MatchAssertions.checkNotNull(values, "join key object array");
    return new JoinKey(Kind.OBJECT_ARRAY, values, Arrays.deepHashCode(values));
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof JoinKey other)) {
      return false;
    }
    if (this.hash != other.hash || this.kind != other.kind) {
      return false;
    }
    return switch (kind) {
      case SINGLE_RID -> Objects.equals(this.value, other.value);
      case RID_ARRAY -> Arrays.equals((RID[]) this.value, (RID[]) other.value);
      case OBJECT_ARRAY -> Arrays.deepEquals(
          (Object[]) this.value, (Object[]) other.value);
    };
  }

  @Override
  public String toString() {
    return switch (kind) {
      case SINGLE_RID -> "JoinKey[" + value + "]";
      case RID_ARRAY -> "JoinKey" + Arrays.toString((RID[]) value);
      case OBJECT_ARRAY -> "JoinKey" + Arrays.deepToString((Object[]) value);
    };
  }
}
