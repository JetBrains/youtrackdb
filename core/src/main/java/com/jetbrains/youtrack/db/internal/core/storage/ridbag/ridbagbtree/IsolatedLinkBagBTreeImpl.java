package com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrack.db.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import java.util.Map.Entry;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IsolatedLinkBagBTreeImpl implements IsolatedLinkBagBTree<RID, Integer> {

  private final SharedLinkBagBTree bTree;
  private final int intFileId;
  private final long linkBagId;

  private final BinarySerializer<RID> keySerializer;
  private final BinarySerializer<Integer> valueSerializer;

  public IsolatedLinkBagBTreeImpl(
      final SharedLinkBagBTree bTree,
      final int intFileId,
      final long linkBagId,
      BinarySerializer<RID> keySerializer,
      BinarySerializer<Integer> valueSerializer) {
    this.bTree = bTree;
    this.intFileId = intFileId;
    this.linkBagId = linkBagId;
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;
  }

  @Override
  public LinkBagPointer getCollectionPointer() {
    return new LinkBagPointer(intFileId, linkBagId);
  }

  @Override
  public long getFileId() {
    return bTree.getFileId();
  }

  @Override
  public LinkBagBucketPointer getRootBucketPointer() {
    return new LinkBagBucketPointer(linkBagId, 0);
  }

  @Nullable
  @Override
  public Integer get(RID rid) {
    final int result;

    result = bTree.get(new EdgeKey(linkBagId, rid.getCollectionId(), rid.getCollectionPosition()));

    if (result < 0) {
      return null;
    }

    return result;
  }

  @Override
  public boolean put(AtomicOperation atomicOperation, RID rid, Integer value) {
    return bTree.put(
        atomicOperation,
        new EdgeKey(linkBagId, rid.getCollectionId(), rid.getCollectionPosition()),
        value);
  }

  @Override
  public void clear(AtomicOperation atomicOperation) {
    try (var stream =
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
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
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE), true, true)) {
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
            atomicOperation,
            new EdgeKey(linkBagId, rid.getCollectionId(), rid.getCollectionPosition()));

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
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, rid.getCollectionId(), rid.getCollectionPosition()),
            inclusive,
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true)) {
      listenStream(stream, listener);
    }
  }

  @Nonnull
  @Override
  public Spliterator<ObjectIntPair<RID>> spliteratorEntriesBetween(@Nonnull RID keyFrom,
      boolean fromInclusive, @Nonnull RID keyTo, boolean toInclusive, boolean ascSortOrder) {
    var spliterator = bTree.spliteratorEntriesBetween(
        new EdgeKey(linkBagId, keyFrom.getCollectionId(), keyFrom.getCollectionPosition()),
        fromInclusive,
        new EdgeKey(linkBagId, keyTo.getCollectionId(), keyTo.getCollectionPosition()),
        toInclusive,
        ascSortOrder);
    return new TransformingSpliterator(spliterator);
  }

  @Nullable
  @Override
  public RID firstKey() {
    try (final var stream =
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
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
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
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
  public int getRealBagSize() {
    final var size = new ModifiableInteger(0);

    try (final var stream =
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true)) {
      forEachEntry(
          stream,
          entry -> {
            final var rid =
                new RecordId(entry.first.targetCollection, entry.first.targetPosition);

            final var treeValue = entry.second;
            size.increment(treeValue);
            return true;
          });
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


  private static final class TransformingSpliterator implements
      Spliterator<ObjectIntPair<RID>> {

    private final Spliterator<RawPairObjectInteger<EdgeKey>> delegate;

    TransformingSpliterator(Spliterator<RawPairObjectInteger<EdgeKey>> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean tryAdvance(Consumer<? super ObjectIntPair<RID>> action) {
      return delegate.tryAdvance(pair -> {
        final var rid = new RecordId(pair.first.targetCollection, pair.first.targetPosition);
        action.accept(new ObjectIntImmutablePair<>(rid, pair.second));
      });
    }

    @Nullable
    @Override
    public Spliterator<ObjectIntPair<RID>> trySplit() {
      return new TransformingSpliterator(delegate.trySplit());
    }

    @Override
    public long estimateSize() {
      return delegate.estimateSize();
    }

    @Override
    public int characteristics() {
      return delegate.characteristics();
    }
  }
}
