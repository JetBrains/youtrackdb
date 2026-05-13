package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 * Verifies that the {@link CellBTreeSingleValue#setEngineId(long)} default method
 * is a true no-op: implementations that do not override it can call it without
 * any side-effect or exception. The v3 {@code BTree} class overrides this method
 * (to store the engine-id for tombstone GC); this test exercises the default path
 * via a minimal stub that inherits the interface default.
 */
public class CellBTreeSingleValueDefaultTest {

  /**
   * A minimal stub implementing {@link CellBTreeSingleValue} that intentionally
   * does NOT override {@code setEngineId}, inheriting the interface default no-op.
   * Only the methods required by the compiler are present; all throw
   * {@link UnsupportedOperationException} since they are not exercised here.
   */
  private static final class StubTree implements CellBTreeSingleValue<String> {

    @Override
    public void create(@Nonnull AtomicOperation op, BinarySerializer<String> ks,
        PropertyTypeInternal[] kt, int keySize) {
    }

    @Override
    public RID get(String key, @Nonnull AtomicOperation op) {
      return null;
    }

    @Override
    public RID getVisible(String key, IndexesSnapshot snapshot,
        @Nonnull AtomicOperation op) {
      return null;
    }

    @Override
    public Stream<RID> getVisibleStream(String key, IndexesSnapshot snapshot,
        @Nonnull AtomicOperation op) {
      return Stream.empty();
    }

    @Override
    public boolean put(@Nonnull AtomicOperation op, String key, RID value) {
      return false;
    }

    @Override
    public int validatedPut(@Nonnull AtomicOperation op, String key, RID value,
        IndexEngineValidator<String, RID> validator) {
      return 0;
    }

    @Override
    public void close() {
    }

    @Override
    public void delete(@Nonnull AtomicOperation op) {
    }

    @Override
    public void load(String name, int keySize, PropertyTypeInternal[] kt,
        BinarySerializer<String> ks, @Nonnull AtomicOperation op) {
    }

    @Override
    public long size(@Nonnull AtomicOperation op) {
      return 0;
    }

    @Override
    public RID remove(@Nonnull AtomicOperation op, String key) {
      return null;
    }

    @Override
    public Stream<RawPair<String, RID>> iterateEntriesMinor(String key, boolean inclusive,
        boolean asc, @Nonnull AtomicOperation op) {
      return Stream.empty();
    }

    @Override
    public Stream<RawPair<String, RID>> iterateEntriesMajor(String key, boolean inclusive,
        boolean asc, @Nonnull AtomicOperation op) {
      return Stream.empty();
    }

    @Override
    public String firstKey(@Nonnull AtomicOperation op) {
      return null;
    }

    @Override
    public String lastKey(@Nonnull AtomicOperation op) {
      return null;
    }

    @Override
    public Stream<String> keyStream(@Nonnull AtomicOperation op) {
      return Stream.empty();
    }

    @Override
    public Stream<RawPair<String, RID>> allEntries(@Nonnull AtomicOperation op) {
      return Stream.empty();
    }

    @Override
    public Stream<RawPair<String, RID>> iterateEntriesBetween(String from, boolean fromInclusive,
        String to, boolean toInclusive, boolean asc, @Nonnull AtomicOperation op) {
      return Stream.empty();
    }

    @Override
    public void acquireAtomicExclusiveLock(@Nonnull AtomicOperation op) {
    }

    @Override
    public long getApproximateEntriesCount(@Nonnull AtomicOperation op) {
      return 0;
    }

    @Override
    public void setApproximateEntriesCount(@Nonnull AtomicOperation op, long count) {
    }

    @Override
    public void addToApproximateEntriesCount(@Nonnull AtomicOperation op, long delta) {
    }
  }

  /**
   * Calling {@link CellBTreeSingleValue#setEngineId(long)} on a stub that does NOT
   * override the default completes without throwing — the default body is empty.
   */
  @Test
  public void setEngineIdDefaultIsNoOp() {
    CellBTreeSingleValue<String> tree = new StubTree();
    // Must not throw; the default implementation is intentionally a no-op.
    tree.setEngineId(123L);
  }
}
