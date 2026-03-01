package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import java.util.Map.Entry;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IsolatedLinkBagBTreeImpl implements IsolatedLinkBagBTree<RID, LinkBagValue> {

  private final SharedLinkBagBTree bTree;
  private final int intFileId;
  private final long linkBagId;

  private final BinarySerializer<RID> keySerializer;
  private final BinarySerializer<LinkBagValue> valueSerializer;

  public IsolatedLinkBagBTreeImpl(
      final SharedLinkBagBTree bTree,
      final int intFileId,
      final long linkBagId,
      BinarySerializer<RID> keySerializer,
      BinarySerializer<LinkBagValue> valueSerializer) {
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
  public LinkBagValue get(RID rid, AtomicOperation atomicOperation) {
    final var result = bTree.get(
        new EdgeKey(linkBagId, rid.getCollectionId(), rid.getCollectionPosition()),
        atomicOperation);

    return result;
  }

  @Override
  public boolean put(AtomicOperation atomicOperation, RID rid, LinkBagValue value) {
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
            true, atomicOperation)) {
      final var iterator = stream.iterator();

      while (iterator.hasNext()) {
        final var entry = iterator.next();
        bTree.remove(atomicOperation, entry.first());
      }
    }
  }

  @Override
  public boolean isEmpty(AtomicOperation atomicOperation) {
    try (final var stream =
        bTree.iterateEntriesMajor(
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE), true, true,
            atomicOperation)) {
      return stream.findAny().isEmpty();
    }
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
    clear(atomicOperation);
  }

  @Nullable
  @Override
  public LinkBagValue remove(AtomicOperation atomicOperation, RID rid) {
    return bTree.remove(
        atomicOperation,
        new EdgeKey(linkBagId, rid.getCollectionId(), rid.getCollectionPosition()));
  }

  @Override
  public void loadEntriesMajor(
      RID rid,
      boolean inclusive,
      boolean ascSortOrder,
      RangeResultListener<RID, LinkBagValue> listener, AtomicOperation atomicOperation) {
    try (final var stream =
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, rid.getCollectionId(), rid.getCollectionPosition()),
            inclusive,
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true, atomicOperation)) {
      listenStream(stream, listener);
    }
  }

  @Nonnull
  @Override
  public Spliterator<BTreeReadEntry<RID>> spliteratorEntriesBetween(@Nonnull RID keyFrom,
      boolean fromInclusive, @Nonnull RID keyTo, boolean toInclusive, boolean ascSortOrder,
      AtomicOperation atomicOperation) {
    var spliterator = bTree.spliteratorEntriesBetween(
        new EdgeKey(linkBagId, keyFrom.getCollectionId(), keyFrom.getCollectionPosition()),
        fromInclusive,
        new EdgeKey(linkBagId, keyTo.getCollectionId(), keyTo.getCollectionPosition()),
        toInclusive,
        ascSortOrder, atomicOperation);
    return new TransformingSpliterator(spliterator);
  }

  @Nullable
  @Override
  public RID firstKey(AtomicOperation atomicOperation) {
    try (final var stream =
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true, atomicOperation)) {
      final var iterator = stream.iterator();
      if (iterator.hasNext()) {
        final var entry = iterator.next();
        return new RecordId(entry.first().targetCollection, entry.first().targetPosition);
      }
    }

    return null;
  }

  @Nullable
  @Override
  public RID lastKey(AtomicOperation atomicOperation) {
    try (final var stream =
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            false, atomicOperation)) {
      final var iterator = stream.iterator();
      if (iterator.hasNext()) {
        final var entry = iterator.next();
        return new RecordId(entry.first().targetCollection, entry.first().targetPosition);
      }
    }

    return null;
  }

  @Override
  public int getRealBagSize(AtomicOperation atomicOperation) {
    final var size = new ModifiableInteger(0);

    try (final var stream =
        bTree.streamEntriesBetween(
            new EdgeKey(linkBagId, Integer.MIN_VALUE, Long.MIN_VALUE),
            true,
            new EdgeKey(linkBagId, Integer.MAX_VALUE, Long.MAX_VALUE),
            true,
            true, atomicOperation)) {
      forEachEntry(
          stream,
          entry -> {
            final var treeValue = entry.second();
            size.increment(treeValue.counter());
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
  public BinarySerializer<LinkBagValue> getValueSerializer() {
    return valueSerializer;
  }

  private static void forEachEntry(
      final Stream<RawPair<EdgeKey, LinkBagValue>> stream,
      final Function<RawPair<EdgeKey, LinkBagValue>, Boolean> consumer) {

    var cont = true;

    final var iterator = stream.iterator();
    while (iterator.hasNext() && cont) {
      cont = consumer.apply(iterator.next());
    }
  }

  private static void listenStream(
      final Stream<RawPair<EdgeKey, LinkBagValue>> stream,
      final RangeResultListener<RID, LinkBagValue> listener) {
    forEachEntry(
        stream,
        entry ->
            listener.addResult(
                new Entry<>() {
                  @Override
                  public RID getKey() {
                    return new RecordId(entry.first().targetCollection,
                        entry.first().targetPosition);
                  }

                  @Override
                  public LinkBagValue getValue() {
                    return entry.second();
                  }

                  @Override
                  public LinkBagValue setValue(LinkBagValue value) {
                    throw new UnsupportedOperationException();
                  }
                }));
  }


  private static final class TransformingSpliterator implements
      Spliterator<BTreeReadEntry<RID>> {

    private final Spliterator<RawPair<EdgeKey, LinkBagValue>> delegate;

    TransformingSpliterator(Spliterator<RawPair<EdgeKey, LinkBagValue>> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean tryAdvance(Consumer<? super BTreeReadEntry<RID>> action) {
      return delegate.tryAdvance(pair -> {
        final var rid = new RecordId(pair.first().targetCollection, pair.first().targetPosition);
        final var value = pair.second();
        action.accept(new BTreeReadEntry<>(rid, value.counter(),
            value.secondaryCollectionId(), value.secondaryPosition()));
      });
    }

    @Nullable
    @Override
    public Spliterator<BTreeReadEntry<RID>> trySplit() {
      var split = delegate.trySplit();
      return split != null ? new TransformingSpliterator(split) : null;
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
