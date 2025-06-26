package com.jetbrains.youtrack.db.internal.core.storage.cache.chm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class WTinyLFUPolicyTest {

  @Test
  public void testEden() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();

    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    final var cacheEntries = new CacheEntry[3];
    final var cachePointers = new CachePointer[3];

    generateEntries(cacheEntries, cachePointers, pool);

    cacheSize.incrementAndGet();
    wTinyLFU.onAdd(cacheEntries[0]);

    cacheSize.incrementAndGet();
    wTinyLFU.onAdd(cacheEntries[1]);

    cacheSize.incrementAndGet();
    wTinyLFU.onAdd(cacheEntries[2]);

    Assert.assertEquals(3, cacheSize.get());
    Assert.assertEquals(15, wTinyLFU.getMaxSize());

    {
      final var probationIterator = wTinyLFU.probation();
      Assert.assertFalse(probationIterator.hasNext());
    }

    {
      final var protectionIterator = wTinyLFU.protection();
      Assert.assertFalse(protectionIterator.hasNext());
    }

    Assert.assertArrayEquals(
        new CacheEntry[]{cacheEntries[2], cacheEntries[1], cacheEntries[0]},
        toArray(wTinyLFU.eden()));

    wTinyLFU.onAccess(cacheEntries[1]);

    {
      final var probationIterator = wTinyLFU.probation();
      Assert.assertFalse(probationIterator.hasNext());
    }

    {
      final var protectionIterator = wTinyLFU.protection();
      Assert.assertFalse(protectionIterator.hasNext());
    }

    Assert.assertArrayEquals(
        new CacheEntry[]{cacheEntries[1], cacheEntries[2], cacheEntries[0]},
        toArray(wTinyLFU.eden()));

    wTinyLFU.onAccess(cacheEntries[1]);

    {
      final var probationIterator = wTinyLFU.probation();
      Assert.assertFalse(probationIterator.hasNext());
    }

    {
      final var protectionIterator = wTinyLFU.protection();
      Assert.assertFalse(protectionIterator.hasNext());
    }
    Assert.assertArrayEquals(
        new CacheEntry[]{cacheEntries[1], cacheEntries[2], cacheEntries[0]},
        toArray(wTinyLFU.eden()));

    wTinyLFU.onAccess(cacheEntries[0]);

    {
      final var probationIterator = wTinyLFU.probation();
      Assert.assertFalse(probationIterator.hasNext());
    }

    {
      final var protectionIterator = wTinyLFU.protection();
      Assert.assertFalse(protectionIterator.hasNext());
    }

    Assert.assertArrayEquals(
        new CacheEntry[]{cacheEntries[0], cacheEntries[1], cacheEntries[2]},
        toArray(wTinyLFU.eden()));

    Assert.assertEquals(3, cacheSize.get());

    clearPointers(wTinyLFU);
    Mockito.<Object>reset(admittor);
  }

  @Test
  public void testGoLastToProtection() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[4];
    var cachePointers = new CachePointer[4];

    generateEntries(cacheEntries, cachePointers, pool);

    cacheSize.incrementAndGet();
    wTinyLFU.onAdd(cacheEntries[0]);

    cacheSize.incrementAndGet();
    wTinyLFU.onAdd(cacheEntries[1]);

    cacheSize.incrementAndGet();
    wTinyLFU.onAdd(cacheEntries[2]);

    cacheSize.incrementAndGet();
    wTinyLFU.onAdd(cacheEntries[3]);

    Assert.assertArrayEquals(new CacheEntry[]{cacheEntries[0]}, toArray(wTinyLFU.probation()));
    Assert.assertFalse(wTinyLFU.protection().hasNext());
    Assert.assertArrayEquals(
        new CacheEntry[]{cacheEntries[3], cacheEntries[2], cacheEntries[1]},
        toArray(wTinyLFU.eden()));

    Assert.assertEquals(4, cacheSize.get());

    clearPointers(wTinyLFU);
    Mockito.<Object>reset(admittor);
  }

  @Test
  public void testProbationIsFull() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[15];
    var cachePointers = new CachePointer[15];

    generateEntries(cacheEntries, cachePointers, pool);

    for (var i = 0; i < 15; i++) {
      cacheSize.incrementAndGet();
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    var probationIterator = wTinyLFU.probation();

    for (var i = 11; i >= 0; i--) {
      final var cacheEntry = probationIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    Assert.assertFalse(wTinyLFU.protection().hasNext());

    var edenIterator = wTinyLFU.eden();
    for (var i = 14; i >= 12; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    clearPointers(wTinyLFU);
    Mockito.<Object>reset(admittor);
  }

  @Test
  public void testProbationIsOverflownNoProtectionOne() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[16];
    var cachePointers = new CachePointer[16];

    generateEntries(cacheEntries, cachePointers, pool);

    when(admittor.frequency(PageKey.hashCode(1, 0))).thenReturn(0);
    when(admittor.frequency(PageKey.hashCode(1, 12))).thenReturn(1);

    for (var i = 0; i < 16; i++) {
      cacheSize.incrementAndGet();
      data.put(new PageKey(1, i), cacheEntries[i]);
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    Assert.assertEquals(15, cacheSize.get());
    wTinyLFU.assertSize();
    wTinyLFU.assertConsistency();

    Assert.assertEquals(15, memoryAllocator.getMemoryConsumption());

    Assert.assertFalse(wTinyLFU.protection().hasNext());
    var edenIterator = wTinyLFU.eden();

    for (var i = 15; i >= 13; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    var probationIterator = wTinyLFU.probation();
    for (var i = 12; i >= 1; i--) {
      final var cacheEntry = probationIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    clearPointers(wTinyLFU);
  }

  @Test
  public void testProbationIsOverflownNoProtectionTwo() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[16];
    var cachePointers = new CachePointer[16];

    generateEntries(cacheEntries, cachePointers, pool);

    when(admittor.frequency(PageKey.hashCode(1, 0))).thenReturn(1);
    when(admittor.frequency(PageKey.hashCode(1, 12))).thenReturn(0);

    for (var i = 0; i < 16; i++) {
      cacheSize.incrementAndGet();
      data.put(new PageKey(1, i), cacheEntries[i]);
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    Assert.assertEquals(15, cacheSize.get());
    wTinyLFU.assertSize();
    wTinyLFU.assertConsistency();

    Assert.assertEquals(15, memoryAllocator.getMemoryConsumption());

    Assert.assertFalse(wTinyLFU.protection().hasNext());
    var edenIterator = wTinyLFU.eden();

    for (var i = 15; i >= 13; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    var probationIterator = wTinyLFU.probation();
    for (var i = 11; i >= 0; i--) {
      final var cacheEntry = probationIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    clearPointers(wTinyLFU);
  }

  @Test
  public void testProbationIsOverflownNoProtectionVictimOneIsAcquired() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[16];
    var cachePointers = new CachePointer[16];

    generateEntries(cacheEntries, cachePointers, pool);

    when(admittor.frequency(PageKey.hashCode(1, 0))).thenReturn(0);
    when(admittor.frequency(PageKey.hashCode(1, 12))).thenReturn(1);

    when(admittor.frequency(PageKey.hashCode(1, 1))).thenReturn(0);
    when(admittor.frequency(PageKey.hashCode(1, 13))).thenReturn(1);

    cacheEntries[0].acquireEntry();

    for (var i = 0; i < 16; i++) {
      cacheSize.incrementAndGet();
      data.put(new PageKey(1, i), cacheEntries[i]);
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    cacheEntries[0].releaseEntry();

    Assert.assertEquals(15, cacheSize.get());
    wTinyLFU.assertSize();
    wTinyLFU.assertConsistency();

    Assert.assertEquals(15, memoryAllocator.getMemoryConsumption());

    Assert.assertFalse(wTinyLFU.protection().hasNext());
    var edenIterator = wTinyLFU.eden();

    Assert.assertSame(cacheEntries[0], edenIterator.next());

    for (var i = 15; i >= 14; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    var probationIterator = wTinyLFU.probation();
    for (var i = 13; i >= 2; i--) {
      final var cacheEntry = probationIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    clearPointers(wTinyLFU);
  }

  @Test
  public void testProbationIsOverflownNoProtectionTwoIsAcquired() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[16];
    var cachePointers = new CachePointer[16];

    generateEntries(cacheEntries, cachePointers, pool);

    when(admittor.frequency(PageKey.hashCode(1, 0))).thenReturn(1);
    when(admittor.frequency(PageKey.hashCode(1, 12))).thenReturn(0);

    when(admittor.frequency(PageKey.hashCode(1, 0))).thenReturn(1);
    when(admittor.frequency(PageKey.hashCode(1, 13))).thenReturn(0);

    cacheEntries[12].acquireEntry();

    for (var i = 0; i < 16; i++) {
      cacheSize.incrementAndGet();
      data.put(new PageKey(1, i), cacheEntries[i]);
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    cacheEntries[12].releaseEntry();

    Assert.assertEquals(15, cacheSize.get());
    wTinyLFU.assertSize();
    wTinyLFU.assertConsistency();

    Assert.assertEquals(15, memoryAllocator.getMemoryConsumption());

    Assert.assertFalse(wTinyLFU.protection().hasNext());
    var edenIterator = wTinyLFU.eden();

    Assert.assertSame(cacheEntries[12], edenIterator.next());

    for (var i = 15; i >= 14; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    var probationIterator = wTinyLFU.probation();
    for (var i = 11; i >= 0; i--) {
      final var cacheEntry = probationIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    clearPointers(wTinyLFU);
  }

  @Test
  public void testProtectedOverflow() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[15];
    var cachePointers = new CachePointer[15];

    generateEntries(cacheEntries, cachePointers, pool);

    for (var i = 0; i < 15; i++) {
      cacheSize.incrementAndGet();
      data.put(new PageKey(1, i), cacheEntries[i]);
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    for (var i = 0; i < 11; i++) {
      wTinyLFU.onAccess(cacheEntries[i]);
    }

    final var edenIterator = wTinyLFU.eden();
    for (var i = 14; i >= 13; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    final var protectionIterator = wTinyLFU.protection();
    for (var i = 10; i >= 1; i--) {
      final var cacheEntry = protectionIterator.next();
      Assert.assertSame(cacheEntry, cacheEntries[i]);
    }

    wTinyLFU.assertSize();
    wTinyLFU.assertConsistency();

    clearPointers(wTinyLFU);
  }

  @Test
  public void testProtection() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[6];
    var cachePointers = new CachePointer[6];

    generateEntries(cacheEntries, cachePointers, pool);

    for (var i = 0; i < 6; i++) {
      cacheSize.incrementAndGet();
      data.put(new PageKey(1, i), cacheEntries[i]);
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    for (var i = 0; i < 3; i++) {
      wTinyLFU.onAccess(cacheEntries[i]);
    }

    final var edenIterator = wTinyLFU.eden();
    for (var i = 5; i >= 3; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    var probationIterator = wTinyLFU.probation();
    Assert.assertFalse(probationIterator.hasNext());

    var protectionIterator = wTinyLFU.protection();
    for (var i = 2; i >= 0; i--) {
      final var cacheEntry = protectionIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    Assert.assertEquals(6, cacheSize.get());

    wTinyLFU.onAccess(cacheEntries[1]);

    protectionIterator = wTinyLFU.protection();

    Assert.assertSame(cacheEntries[1], protectionIterator.next());
    Assert.assertSame(cacheEntries[2], protectionIterator.next());
    Assert.assertSame(cacheEntries[0], protectionIterator.next());

    Assert.assertFalse(protectionIterator.hasNext());

    wTinyLFU.assertSize();
    wTinyLFU.assertConsistency();

    clearPointers(wTinyLFU);
  }

  @Test
  public void testRemovedEden() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[1];
    var cachePointers = new CachePointer[1];

    generateEntries(cacheEntries, cachePointers, pool);

    cacheSize.incrementAndGet();
    data.put(new PageKey(1, 0), cacheEntries[0]);
    wTinyLFU.onAdd(cacheEntries[0]);

    cacheEntries[0].freeze();
    data.remove(new PageKey(1, 0), cacheEntries[0]);
    cacheSize.decrementAndGet();
    wTinyLFU.onRemove(cacheEntries[0]);

    Assert.assertTrue(cacheEntries[0].isDead());
    Assert.assertFalse(wTinyLFU.eden().hasNext());
    Assert.assertFalse(wTinyLFU.probation().hasNext());
    Assert.assertFalse(wTinyLFU.protection().hasNext());

    wTinyLFU.assertConsistency();
    wTinyLFU.assertSize();

    Assert.assertEquals(0, cacheSize.get());
    Assert.assertEquals(0, memoryAllocator.getMemoryConsumption());
    clearPointers(wTinyLFU);
  }

  @Test
  public void testRemoveProbation() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[6];
    var cachePointers = new CachePointer[6];

    generateEntries(cacheEntries, cachePointers, pool);

    for (var i = 0; i < 6; i++) {
      cacheSize.incrementAndGet();
      data.put(new PageKey(1, i), cacheEntries[i]);
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    cacheEntries[0].freeze();
    data.remove(new PageKey(1, 0), cacheEntries[0]);
    cacheSize.decrementAndGet();
    wTinyLFU.onRemove(cacheEntries[0]);

    Assert.assertEquals(5, cacheSize.get());
    Assert.assertFalse(wTinyLFU.protection().hasNext());

    var edenIterator = wTinyLFU.eden();
    for (var i = 5; i >= 3; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    var probationIterator = wTinyLFU.probation();
    for (var i = 2; i >= 1; i--) {
      final var cacheEntry = probationIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    Assert.assertTrue(cacheEntries[0].isDead());

    wTinyLFU.assertSize();
    wTinyLFU.assertConsistency();

    Assert.assertEquals(5, memoryAllocator.getMemoryConsumption());
    clearPointers(wTinyLFU);
  }

  @Test
  public void testRemoveProtection() {
    var memoryAllocator = new DirectMemoryAllocator();
    var pool = new ByteBufferPool(1, memoryAllocator, 0);

    var data = new ConcurrentHashMap<PageKey, CacheEntry>();
    var admittor = mock(Admittor.class);

    var cacheSize = new AtomicInteger();
    var wTinyLFU = new WTinyLFUPolicy(data, admittor, cacheSize);
    wTinyLFU.setMaxSize(15);

    var cacheEntries = new CacheEntry[6];
    var cachePointers = new CachePointer[6];

    generateEntries(cacheEntries, cachePointers, pool);

    for (var i = 0; i < 6; i++) {
      cacheSize.incrementAndGet();
      data.put(new PageKey(1, i), cacheEntries[i]);
      wTinyLFU.onAdd(cacheEntries[i]);
    }

    for (var i = 0; i < 3; i++) {
      wTinyLFU.onAccess(cacheEntries[i]);
    }

    cacheEntries[0].freeze();
    data.remove(new PageKey(1, 0), cacheEntries[0]);
    cacheSize.decrementAndGet();
    wTinyLFU.onRemove(cacheEntries[0]);

    Assert.assertEquals(5, cacheSize.get());
    Assert.assertTrue(cacheEntries[0].isDead());

    Assert.assertFalse(wTinyLFU.probation().hasNext());

    var edenIterator = wTinyLFU.eden();
    for (var i = 5; i >= 3; i--) {
      final var cacheEntry = edenIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    var protactionIterator = wTinyLFU.protection();
    for (var i = 2; i >= 1; i--) {
      final var cacheEntry = protactionIterator.next();
      Assert.assertSame(cacheEntries[i], cacheEntry);
    }

    wTinyLFU.assertSize();
    wTinyLFU.assertConsistency();

    Assert.assertEquals(5, memoryAllocator.getMemoryConsumption());
    clearPointers(wTinyLFU);
  }

  private static CacheEntry[] toArray(Iterator<CacheEntry> iterator) {
    final List<CacheEntry> entries = new ArrayList<>();
    while (iterator.hasNext()) {
      final var cacheEntry = iterator.next();
      entries.add(cacheEntry);
    }

    return entries.toArray(new CacheEntry[0]);
  }

  private static void generateEntries(
      CacheEntry[] cacheEntries, CachePointer[] cachePointers, ByteBufferPool pool) {
    for (var i = 0; i < cacheEntries.length; i++) {
      final var cachePointer =
          new CachePointer(pool.acquireDirect(true, Intention.TEST), pool, 1, i);
      final CacheEntry cacheEntry = new CacheEntryImpl(1, i, cachePointer, false, null);

      cachePointer.incrementReadersReferrer();
      cacheEntries[i] = cacheEntry;
      cachePointers[i] = cachePointer;
    }
  }

  private static void clearPointers(WTinyLFUPolicy policy) {
    clearQueue(policy.eden());
    clearQueue(policy.probation());
    clearQueue(policy.protection());
  }

  private static void clearQueue(final Iterator<CacheEntry> iterator) {
    while (iterator.hasNext()) {
      final var cacheEntry = iterator.next();
      cacheEntry.getCachePointer().decrementReadersReferrer();
    }
  }
}
