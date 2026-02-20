package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class TsMinHolderTest {

  @Test
  public void testDefaultValues() {
    var holder = new TsMinHolder();
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);
    assertThat(holder.activeTxCount).isZero();
    assertThat(holder.registeredInTsMins).isFalse();
  }

  @Test
  public void testMutableTsMin() {
    var holder = new TsMinHolder();
    holder.tsMin = 42L;
    assertThat(holder.tsMin).isEqualTo(42L);

    holder.tsMin = Long.MAX_VALUE;
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testLazyRegistration() {
    var holder = new TsMinHolder();
    assertThat(holder.registeredInTsMins).isFalse();

    holder.registeredInTsMins = true;
    assertThat(holder.registeredInTsMins).isTrue();
  }

  @Test
  public void testLowWaterMarkSingleHolder() {
    var tsMins = newTsMinsSet();

    var holder = new TsMinHolder();
    holder.tsMin = 100L;
    tsMins.add(holder);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(100L);
  }

  @Test
  public void testLowWaterMarkMultipleHolders() {
    var tsMins = newTsMinsSet();

    var h1 = new TsMinHolder();
    h1.tsMin = 300L;
    tsMins.add(h1);

    var h2 = new TsMinHolder();
    h2.tsMin = 100L;
    tsMins.add(h2);

    var h3 = new TsMinHolder();
    h3.tsMin = 200L;
    tsMins.add(h3);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(100L);
  }

  @Test
  public void testLowWaterMarkIdleHoldersIgnored() {
    var tsMins = newTsMinsSet();

    var active = new TsMinHolder();
    active.tsMin = 50L;
    tsMins.add(active);

    var idle = new TsMinHolder();
    // idle.tsMin remains Long.MAX_VALUE (no active tx)
    tsMins.add(idle);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(50L);
  }

  @Test
  public void testLowWaterMarkAllIdle() {
    var tsMins = newTsMinsSet();

    var h1 = new TsMinHolder();
    tsMins.add(h1);

    var h2 = new TsMinHolder();
    tsMins.add(h2);

    // Both holders have default MAX_VALUE (idle)
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testLowWaterMarkEmptySet() {
    var tsMins = newTsMinsSet();

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testLowWaterMarkUpdatesWhenHolderChanges() {
    var tsMins = newTsMinsSet();

    var holder = new TsMinHolder();
    holder.tsMin = 100L;
    tsMins.add(holder);

    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(100L);

    // Simulate transaction end — tsMin goes back to MAX_VALUE
    holder.tsMin = Long.MAX_VALUE;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(Long.MAX_VALUE);

    // Simulate new transaction begin
    holder.tsMin = 200L;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(200L);
  }

  @Test
  public void testActiveTxCountLifecycle() {
    var holder = new TsMinHolder();
    assertThat(holder.activeTxCount).isZero();
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);

    // Simulate first tx begin: tsMin set, count goes to 1
    holder.tsMin = Math.min(holder.tsMin, 100L);
    holder.activeTxCount++;
    assertThat(holder.activeTxCount).isEqualTo(1);
    assertThat(holder.tsMin).isEqualTo(100L);

    // Simulate second tx begin (overlapping session): tsMin stays at min, count goes to 2
    holder.tsMin = Math.min(holder.tsMin, 200L);
    holder.activeTxCount++;
    assertThat(holder.activeTxCount).isEqualTo(2);
    assertThat(holder.tsMin).isEqualTo(100L);

    // Simulate first tx end: count goes to 1, tsMin stays (still an active tx)
    holder.activeTxCount--;
    if (holder.activeTxCount == 0) {
      holder.tsMin = Long.MAX_VALUE;
    }
    assertThat(holder.activeTxCount).isEqualTo(1);
    assertThat(holder.tsMin).isEqualTo(100L);

    // Simulate second tx end: count goes to 0, tsMin resets
    holder.activeTxCount--;
    if (holder.activeTxCount == 0) {
      holder.tsMin = Long.MAX_VALUE;
    }
    assertThat(holder.activeTxCount).isZero();
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testThreadLocalReturnsSameHolderForSameThread() {
    ThreadLocal<TsMinHolder> threadLocal = ThreadLocal.withInitial(TsMinHolder::new);

    var mainHolder = threadLocal.get();
    mainHolder.tsMin = 100L;

    // Verify the holder is the same object on repeated access
    assertThat(threadLocal.get()).isSameAs(mainHolder);
    assertThat(threadLocal.get().tsMin).isEqualTo(100L);
  }

  @Test
  public void testWeakHashMapGcRemovesDeadHolder() {
    var tsMins = newTsMinsSet();

    // Keep a strong reference to the "surviving" holder
    var survivor = new TsMinHolder();
    survivor.tsMin = 500L;
    tsMins.add(survivor);

    // Create a holder that will become eligible for GC
    var ephemeral = new TsMinHolder();
    ephemeral.tsMin = 100L;
    tsMins.add(ephemeral);
    // Use a WeakReference to detect when GC actually collects the holder
    var weakRef = new WeakReference<>(ephemeral);

    assertThat(tsMins).hasSize(2);
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(100L);

    // Release the strong reference — the WeakHashMap entry becomes eligible for GC
    //noinspection UnusedAssignment
    ephemeral = null;

    // Poll until GC collects the weak reference (bounded to avoid hanging).
    // Allocate 1 MB per iteration to increase GC pressure in large-heap CI environments.
    for (int i = 0; i < 100 && weakRef.get() != null; i++) {
      //noinspection unused
      byte[] pressure = new byte[1024 * 1024];
      System.gc();
      try {
        //noinspection BusyWait
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    if (weakRef.get() == null) {
      // GC collected the holder — the WeakHashMap should have evicted it.
      // Access the set to trigger WeakHashMap's internal expungeStaleEntries().
      assertThat(tsMins).hasSize(1);
      assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(500L);
    }
    // If GC didn't collect within the timeout, the test is inconclusive but not a failure —
    // WeakHashMap GC behavior is JVM-dependent. The survivor invariant still holds.
    assertThat(tsMins).contains(survivor);
  }

  @Test
  public void testMultiThreadedLowWaterMark() throws InterruptedException {
    var tsMins = newTsMinsSet();
    // Each thread gets its own TsMinHolder from the ThreadLocal supplier.
    var threadLocal = ThreadLocal.withInitial(TsMinHolder::new);
    var allRegistered = new CountDownLatch(2);
    var checkDone = new CountDownLatch(1);
    var t2Holder = new AtomicReference<TsMinHolder>();

    // Thread 1: register with tsMin=200
    var t1 = new Thread(() -> {
      var holder = threadLocal.get();
      holder.tsMin = 200L;
      holder.activeTxCount++;
      tsMins.add(holder);
      allRegistered.countDown();
      try {
        checkDone.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    // Thread 2: register with tsMin=50
    var t2 = new Thread(() -> {
      var holder = threadLocal.get();
      holder.tsMin = 50L;
      holder.activeTxCount++;
      tsMins.add(holder);
      t2Holder.set(holder);
      allRegistered.countDown();
      try {
        checkDone.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    t1.start();
    t2.start();
    assertThat(allRegistered.await(10, TimeUnit.SECONDS))
        .as("Both threads should register within 10s")
        .isTrue();

    // Both threads registered — LWM should be the minimum (50)
    assertThat(tsMins).hasSize(2);
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(50L);

    // Thread 2 "ends" its transaction.
    // Mutated from main thread for test simplicity (in production, only the owning thread writes).
    t2Holder.get().tsMin = Long.MAX_VALUE;
    t2Holder.get().activeTxCount--;
    assertThat(AbstractStorage.computeGlobalLowWaterMark(tsMins)).isEqualTo(200L);

    checkDone.countDown();
    t1.join(5000);
    t2.join(5000);
    assertThat(t1.isAlive()).isFalse();
    assertThat(t2.isAlive()).isFalse();
  }

  @Test
  public void testLazyRegistrationAddsToSetOnlyOnce() {
    var tsMins = newTsMinsSet();
    var holder = new TsMinHolder();

    // Simulate the lazy registration pattern from AbstractStorage.startStorageTx()
    // First call: not yet registered — should add
    if (!holder.registeredInTsMins) {
      tsMins.add(holder);
      holder.registeredInTsMins = true;
    }
    assertThat(tsMins).hasSize(1);
    assertThat(holder.registeredInTsMins).isTrue();

    // Second call: already registered — should skip
    if (!holder.registeredInTsMins) {
      tsMins.add(holder);
      holder.registeredInTsMins = true;
    }
    assertThat(tsMins).hasSize(1);

    // Third call from a "different tx begin" on the same thread — still skip
    holder.tsMin = 42L;
    if (!holder.registeredInTsMins) {
      tsMins.add(holder);
      holder.registeredInTsMins = true;
    }
    assertThat(tsMins).hasSize(1);
    assertThat(holder.tsMin).isEqualTo(42L);
  }

  private static Set<TsMinHolder> newTsMinsSet() {
    return Collections.synchronizedSet(
        Collections.newSetFromMap(new WeakHashMap<>()));
  }
}
