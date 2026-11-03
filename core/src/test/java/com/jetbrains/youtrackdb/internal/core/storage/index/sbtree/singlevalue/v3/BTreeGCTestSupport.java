package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Reflection helpers for LWM pinning and engine registration in tombstone GC tests.
 * These helpers depend on AbstractStorage's tsMins, stateLock and indexEngineNameMap fields
 * and the package-private TsMinHolder class. Update them when those internals change.
 */
final class BTreeGCTestSupport {

  private BTreeGCTestSupport() {
  }

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

  /** Registers a B-tree stub with distinct slot and history identities under the state lock. */
  @SuppressWarnings("unchecked")
  static void registerStubEngine(
      AbstractStorage storage, String name, int slot, int fileBaseId) throws Exception {
    if (slot == fileBaseId) {
      throw new IllegalArgumentException("GC fixtures require distinct slot and file base ids");
    }
    var stub = mock(BTreeIndexEngine.class);
    when(stub.getName()).thenReturn(name);
    when(stub.getId()).thenReturn(slot);
    when(stub.getFileBaseId()).thenReturn(fileBaseId);
    var lock = getStateLock(storage);
    lock.writeLock().lock();
    try {
      Field mapField = AbstractStorage.class.getDeclaredField("indexEngineNameMap");
      mapField.setAccessible(true);
      Map<String, BaseIndexEngine> map =
          (Map<String, BaseIndexEngine>) mapField.get(storage);
      map.put(name, stub);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @SuppressWarnings("unchecked")
  static void unregisterStubEngine(AbstractStorage storage, String name) throws Exception {
    var lock = getStateLock(storage);
    lock.writeLock().lock();
    try {
      Field mapField = AbstractStorage.class.getDeclaredField("indexEngineNameMap");
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
}
