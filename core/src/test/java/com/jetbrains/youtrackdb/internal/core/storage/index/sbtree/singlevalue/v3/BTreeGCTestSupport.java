package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Shared test infrastructure for BTree tombstone GC tests.
 * Provides a stub {@link BaseIndexEngine} for {@code indexEngineNameMap} registration
 * and reflection helpers for LWM pinning and engine registration/unregistration.
 */
final class BTreeGCTestSupport {

  private BTreeGCTestSupport() {
  }

  // ---- LWM pinning helpers (via reflection) ----

  @SuppressWarnings("unchecked")
  static Object pinLwm(AbstractStorage storage, long lwmValue) throws Exception {
    Class<?> holderClass = Class.forName(
        "com.jetbrains.youtrackdb.internal.core.storage.impl.local.TsMinHolder");
    var ctor = holderClass.getDeclaredConstructor();
    ctor.setAccessible(true);
    Object holder = ctor.newInstance();
    Field tsMinField = holderClass.getDeclaredField("tsMin");
    tsMinField.setAccessible(true);
    tsMinField.setLong(holder, lwmValue);

    Field tsMinsField = AbstractStorage.class.getDeclaredField("tsMins");
    tsMinsField.setAccessible(true);
    Set<Object> tsMins = (Set<Object>) tsMinsField.get(storage);
    tsMins.add(holder);
    return holder;
  }

  @SuppressWarnings("unchecked")
  static void unpinLwm(AbstractStorage storage, Object holder) throws Exception {
    Field tsMinsField = AbstractStorage.class.getDeclaredField("tsMins");
    tsMinsField.setAccessible(true);
    Set<Object> tsMins = (Set<Object>) tsMinsField.get(storage);
    tsMins.remove(holder);
  }

  // ---- Stub engine registration (via reflection) ----

  /**
   * Registers a minimal stub {@link BaseIndexEngine} in AbstractStorage's
   * {@code indexEngineNameMap} so that snapshot entry queries can resolve the
   * index. Acquires stateLock.writeLock() because indexEngineNameMap is a plain
   * HashMap that requires external synchronization.
   */
  @SuppressWarnings("unchecked")
  static void registerStubEngine(
      AbstractStorage storage, String name, int id) throws Exception {
    BaseIndexEngine stub = new StubIndexEngine(name, id);
    var lock = getStateLock(storage);
    lock.writeLock().lock();
    try {
      Field mapField =
          AbstractStorage.class.getDeclaredField("indexEngineNameMap");
      mapField.setAccessible(true);
      Map<String, BaseIndexEngine> map =
          (Map<String, BaseIndexEngine>) mapField.get(storage);
      map.put(name, stub);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @SuppressWarnings("unchecked")
  static void unregisterStubEngine(
      AbstractStorage storage, String name) throws Exception {
    var lock = getStateLock(storage);
    lock.writeLock().lock();
    try {
      Field mapField =
          AbstractStorage.class.getDeclaredField("indexEngineNameMap");
      mapField.setAccessible(true);
      Map<String, BaseIndexEngine> map =
          (Map<String, BaseIndexEngine>) mapField.get(storage);
      map.remove(name);
    } finally {
      lock.writeLock().unlock();
    }
  }

  static ReadWriteLock getStateLock(AbstractStorage storage) throws Exception {
    Field lockField = AbstractStorage.class.getDeclaredField("stateLock");
    lockField.setAccessible(true);
    return (ReadWriteLock) lockField.get(storage);
  }

  // ---- Stub engine ----

  /**
   * Minimal {@link BaseIndexEngine} stub that provides only {@code getId()}
   * and {@code getName()} — the only methods used by
   * {@code AbstractStorage.hasActiveSnapshotEntries()}.
   */
  static class StubIndexEngine implements BaseIndexEngine {
    private final String name;
    private final int id;

    StubIndexEngine(String name, int id) {
      this.name = name;
      this.id = id;
    }

    @Override
    public int getId() {
      return id;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public void init(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded s,
        com.jetbrains.youtrackdb.internal.core.index.IndexMetadata m) {
    }

    @Override
    public void flush() {
    }

    @Override
    public void create(AtomicOperation o,
        com.jetbrains.youtrackdb.internal.core.config.IndexEngineData d) {
    }

    @Override
    public void load(
        com.jetbrains.youtrackdb.internal.core.config.IndexEngineData d,
        AtomicOperation o) {
    }

    @Override
    public void delete(AtomicOperation o) {
    }

    @Override
    public void clear(
        com.jetbrains.youtrackdb.internal.core.storage.Storage s,
        AtomicOperation o) {
    }

    @Override
    public void close() {
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        iterateEntriesBetween(Object f, boolean fi, Object t, boolean ti,
            boolean a,
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer tr,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        iterateEntriesMajor(Object k, boolean i, boolean a,
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        iterateEntriesMinor(Object k, boolean i, boolean a,
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        stream(
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<
        com.jetbrains.youtrackdb.internal.common.util.RawPair<Object, RID>>
        descStream(
            com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
            AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public java.util.stream.Stream<Object> keyStream(AtomicOperation o) {
      return java.util.stream.Stream.empty();
    }

    @Override
    public long size(
        com.jetbrains.youtrackdb.internal.core.storage.Storage s,
        com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer t,
        AtomicOperation o) {
      return 0;
    }

    @Override
    public int getEngineAPIVersion() {
      return 0;
    }

    @Override
    public boolean acquireAtomicExclusiveLock(AtomicOperation o) {
      return false;
    }
  }
}
