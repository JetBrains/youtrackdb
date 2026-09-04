package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A sort key that compares one property value through the collation the property declares, rather
 * than through TinkerPop orderability.
 *
 * <h2>Why a key type rather than a transformed value</h2>
 *
 * A collation is more than a transform of the value. {@code ci} lowercases both operands, and when
 * they then compare equal it falls back to the raw comparison, so {@code Ada} and {@code ada} keep a
 * stable relative order instead of tying. Projecting the lowercased value alone would lose that
 * second step, and the engine comparison — which calls {@link Collate#compareForOrderBy} — would
 * order a tie group differently from the native pipeline. Projecting this key instead routes both
 * arms through the one method.
 *
 * <p>TinkerPop's orderability comparator reaches {@link #compareTo} because this class is outside
 * every type it knows and is {@link Comparable} against its own class, which is the case its unknown
 * type branch answers with the natural order.
 */
public final class CollatedSortKey implements Comparable<CollatedSortKey> {

  /** The raw property value, {@code null} when the property held null. */
  @Nullable private final Object value;

  /** The collation that decides the order of two of these keys. */
  private final Collate collate;

  private CollatedSortKey(@Nullable Object value, Collate collate) {
    this.value = value;
    this.collate = collate;
  }

  @Nonnull
  public static CollatedSortKey of(@Nullable Object value, @Nonnull Collate collate) {
    return new CollatedSortKey(value, collate);
  }

  @Nullable public Object value() {
    return value;
  }

  /**
   * Null sorts first, as it does in the engine comparison, and the rest goes to the collation.
   *
   * <p>An incompatible pair of value classes — a text and a number in one schema-less column —
   * reaches the collation's own comparison and throws there. It is reported as equal, which is what
   * the engine comparison does with the same pair, so the two arms stay in step on a column the
   * schema does not constrain.
   */
  @Override
  public int compareTo(@Nonnull CollatedSortKey other) {
    if (value == null) {
      return other.value == null ? 0 : -1;
    }
    if (other.value == null) {
      return 1;
    }
    try {
      return collate.compareForOrderBy(value, other.value);
    } catch (RuntimeException e) {
      return 0;
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof CollatedSortKey key
        && collate.getName().equals(key.collate.getName())
        && compareTo(key) == 0;
  }

  /**
   * Hashes the collated form of the value, so two keys that the collation calls equal cannot hash
   * apart. The {@code ci} fallback to the raw comparison means such keys can still compare non-zero,
   * which only costs an extra bucket probe.
   */
  @Override
  public int hashCode() {
    var transformed = value == null ? null : collate.transform(value);
    return transformed == null ? 0 : transformed.hashCode();
  }

  /** Stable text form: the shape cache and the step renderers both key on a modulator's text. */
  @Override
  public String toString() {
    return collate.getName() + "(" + value + ")";
  }
}
