package com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrack.db.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BonsaiCollectionPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.Change;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class EdgeBTreeImpl implements EdgeBTree<RID, Integer> {

  private final LinkBagBTree bTree;
  private final int intFileId;
  private final long ridBagId;

  private final BinarySerializer<RID> keySerializer;
  private final BinarySerializer<Integer> valueSerializer;

  public EdgeBTreeImpl(
      final LinkBagBTree bTree,
      final int intFileId,
      final long ridBagId,
      BinarySerializer<RID> keySerializer,
      BinarySerializer<Integer> valueSerializer) {
    this.bTree = bTree;
    this.intFileId = intFileId;
    this.ridBagId = ridBagId;
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;
  }

  @Override
  public BonsaiCollectionPointer getCollectionPointer() {
    return new BonsaiCollectionPointer(intFileId, getRootBucketPointer());
  }

  @Override
  public long getFileId() {
    return bTree.getFileId();
  }

  @Override
  public RidBagBucketPointer getRootBucketPointer() {
    return new RidBagBucketPointer(ridBagId, 0);
  }

  @Nullable
  @Override
  public Integer get(RID rid) {
    final int result;

    result = bTree.get(new EdgeKey(ridBagId, rid.getCollectionId(), rid.getCollectionPosition()));

    if (result < 0) {
      return null;
    }

    return result;
  }

  @Override
  public boolean put(AtomicOperation atomicOperation, RID rid, Integer value) {
    return bTree.put(
        atomicOperation,
        new EdgeKey(ridBagId, rid.getCollectionId(), rid.getCollectionPosition()),
        value);
  }

  @Override
  public void clear(AtomicOperation atomicOperation) {
    try (var stream =
        bTree.iterateEntriesBetween(
            new EdgeKey(ridBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(ridBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true)) {
      final var iterator = stream.iterator();

      while (iterator.hasNext()) {
        final var entry = iterator.next();
        bTree.remove(atomicOperation, entry.first);
      }
    }
  }

  public boolean isEmpty() {
    try (final var stream =
        bTree.iterateEntriesMajor(
            new EdgeKey(ridBagId, Integer.MIN_VALUE, Long.MIN_VALUE), true, true)) {
      return stream.findAny().isEmpty();
    }
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
    clear(atomicOperation);
  }

  @Nullable
  @Override
  public Integer remove(AtomicOperation atomicOperation, RID rid) {
    final int result;
    result =
        bTree.remove(
            atomicOperation, new EdgeKey(ridBagId, rid.getCollectionId(), rid.getCollectionPosition()));

    if (result < 0) {
      return null;
    }

    return result;
  }

  @Override
  public void loadEntriesMajor(
      RID rid,
      boolean inclusive,
      boolean ascSortOrder,
      RangeResultListener<RID, Integer> listener) {
    try (final var stream =
        bTree.iterateEntriesBetween(
            new EdgeKey(ridBagId, rid.getCollectionId(), rid.getCollectionPosition()),
            inclusive,
            new EdgeKey(ridBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true)) {
      listenStream(stream, listener);
    }
  }

  @Nullable
  @Override
  public RID firstKey() {
    try (final var stream =
        bTree.iterateEntriesBetween(
            new EdgeKey(ridBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(ridBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true)) {
      final var iterator = stream.iterator();
      if (iterator.hasNext()) {
        final var entry = iterator.next();
        return new RecordId(entry.first.targetCollection, entry.first.targetPosition);
      }
    }

    return null;
  }

  @Nullable
  @Override
  public RID lastKey() {
    try (final var stream =
        bTree.iterateEntriesBetween(
            new EdgeKey(ridBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            new EdgeKey(ridBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            false)) {
      final var iterator = stream.iterator();
      if (iterator.hasNext()) {
        final var entry = iterator.next();
        return new RecordId(entry.first.targetCollection, entry.first.targetPosition);
      }
    }

    return null;
  }

  @Override
  public int getRealBagSize(Map<RID, Change> changes) {
    final Map<Identifiable, Change> notAppliedChanges = new HashMap<>(changes);
    final var size = new ModifiableInteger(0);

    try (final var stream =
        bTree.iterateEntriesBetween(
            new EdgeKey(ridBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(ridBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true)) {
      forEachEntry(
          stream,
          entry -> {
            final var rid =
                new RecordId(entry.first.targetCollection, entry.first.targetPosition);
            final var change = notAppliedChanges.remove(rid);
            final int result;

            final Integer treeValue = entry.second;
            if (change == null) {
              result = treeValue;
            } else {
              result = change.applyTo(treeValue);
            }

            size.increment(result);
            return true;
          });
    }

    for (final var change : notAppliedChanges.values()) {
      final var result = change.applyTo(0);
      size.increment(result);
    }

    return size.value;
  }

  @Override
  public BinarySerializer<RID> getKeySerializer() {
    return keySerializer;
  }

  @Override
  public BinarySerializer<Integer> getValueSerializer() {
    return valueSerializer;
  }

  private static void forEachEntry(
      final Stream<RawPairObjectInteger<EdgeKey>> stream,
      final Function<RawPairObjectInteger<EdgeKey>, Boolean> consumer) {

    var cont = true;

    final var iterator = stream.iterator();
    while (iterator.hasNext() && cont) {
      cont = consumer.apply(iterator.next());
    }
  }

  private static IntArrayList streamToList(
      final Stream<RawPairObjectInteger<EdgeKey>> stream, int maxValuesToFetch) {
    if (maxValuesToFetch < 0) {
      maxValuesToFetch = Integer.MAX_VALUE;
    }

    final var result = new IntArrayList(Math.max(8, maxValuesToFetch));

    final var limit = maxValuesToFetch;
    forEachEntry(
        stream,
        entry -> {
          result.add(entry.second);
          return result.size() < limit;
        });

    return result;
  }

  private static void listenStream(
      final Stream<RawPairObjectInteger<EdgeKey>> stream,
      final RangeResultListener<RID, Integer> listener) {
    forEachEntry(
        stream,
        entry ->
            listener.addResult(
                new Entry<>() {
                  @Override
                  public RID getKey() {
                    return new RecordId(entry.first.targetCollection, entry.first.targetPosition);
                  }

                  @Override
                  public Integer getValue() {
                    return entry.second;
                  }

                  @Override
                  public Integer setValue(Integer value) {
                    throw new UnsupportedOperationException();
                  }
                }));
  }
}
