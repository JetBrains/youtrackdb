package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * A total, never-throwing sort key derived from a record identifier: collection identifier first,
 * then collection position, both compared as signed values. That is exactly the order a MATCH
 * {@code ORDER BY <alias>.@rid} item produces, so a Gremlin sort keyed on this type and a translated
 * sort keyed on {@code @rid} agree row for row.
 *
 * <h2>Why the identifier itself is not the key</h2>
 *
 * A record identifier reaches a comparator as more than one implementation class — a committed
 * record carries {@link com.jetbrains.youtrackdb.internal.core.id.RecordId} while a record created
 * inside an open transaction carries
 * {@link com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId} — and one ordered query sees
 * both. TinkerPop's orderability comparator only calls {@code compareTo} when one operand's class
 * accepts the other; two sibling classes fail that test, so it falls back to comparing class name
 * and text. Every transaction-local identifier then sorts as one block ahead of every committed one,
 * which is not the MATCH order. Projecting both classes into this single key type removes the
 * fallback: one class, one natural order, numeric on both fields.
 *
 * <h2>Absent identifiers</h2>
 *
 * A value that carries no record identifier yields {@link #absent()}, which sorts before every key
 * that has one. Nothing throws, whatever the projected value turns out to be, because the projection
 * is installed by a strategy that infers the stream type rather than reading it.
 */
public final class RecordIdSortKey implements Comparable<RecordIdSortKey> {

  private static final RecordIdSortKey ABSENT = new RecordIdSortKey(false, 0, 0);

  /** {@code false} only for {@link #ABSENT}; the two identifier fields are then meaningless. */
  private final boolean present;

  private final int collectionId;

  private final long collectionPosition;

  private RecordIdSortKey(boolean present, int collectionId, long collectionPosition) {
    this.present = present;
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
  }

  /** The key every value without a record identifier projects to. Sorts before all others. */
  @Nonnull
  public static RecordIdSortKey absent() {
    return ABSENT;
  }

  /**
   * The key for {@code value}, for any value at all. A map entry contributes its key, which is what
   * an entry-stream sort needs; an element contributes its identifier; anything else is
   * {@link #absent()}.
   *
   * <p>The entry unwrap is one level deep on purpose. It covers the reachable shape — an element
   * keyed group map unfolded or ordered in place — without recursing into a structure this method
   * cannot bound.
   */
  @Nonnull
  public static RecordIdSortKey of(@Nullable Object value) {
    var candidate = value instanceof Map.Entry<?, ?> entry ? entry.getKey() : value;
    if (candidate instanceof RecordIdSortKey key) {
      return key;
    }
    if (candidate instanceof Identifiable identifiable) {
      return ofIdentity(identifiable.getIdentity());
    }
    if (candidate instanceof Element element) {
      return element.id() instanceof Identifiable identifiable
          ? ofIdentity(identifiable.getIdentity())
          : ABSENT;
    }
    return ABSENT;
  }

  private static RecordIdSortKey ofIdentity(@Nullable RID rid) {
    return rid == null
        ? ABSENT
        : new RecordIdSortKey(true, rid.getCollectionId(), rid.getCollectionPosition());
  }

  @Override
  public int compareTo(@Nonnull RecordIdSortKey other) {
    if (present != other.present) {
      return present ? 1 : -1;
    }
    if (!present) {
      return 0;
    }
    var byCollection = Integer.compare(collectionId, other.collectionId);
    return byCollection != 0
        ? byCollection
        : Long.compare(collectionPosition, other.collectionPosition);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof RecordIdSortKey key
        && present == key.present
        && collectionId == key.collectionId
        && collectionPosition == key.collectionPosition;
  }

  @Override
  public int hashCode() {
    return present ? 31 * collectionId + Long.hashCode(collectionPosition) : 0;
  }

  /**
   * The record identifier's own text form, or {@code -} when there is none. Stable across runs and
   * across processes, unlike a default identity rendering, because the translation shape cache and
   * the diagnostic step renderers both put a modulator's text into a key.
   */
  @Override
  public String toString() {
    return present
        ? String.valueOf(RID.PREFIX) + collectionId + RID.SEPARATOR + collectionPosition
        : "-";
  }
}
