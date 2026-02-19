package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import org.junit.Test;

public class TsMinHolderTest {

  @Test
  public void testDefaultValues() {
    var holder = new TsMinHolder();
    assertThat(holder.tsMin).isEqualTo(Long.MAX_VALUE);
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
  public void testThreadLocalReturnsSameHolderForSameThread() {
    ThreadLocal<TsMinHolder> threadLocal = ThreadLocal.withInitial(TsMinHolder::new);

    var mainHolder = threadLocal.get();
    mainHolder.tsMin = 100L;

    // Verify the holder is the same object on repeated access
    assertThat(threadLocal.get()).isSameAs(mainHolder);
    assertThat(threadLocal.get().tsMin).isEqualTo(100L);
  }

  private static Set<TsMinHolder> newTsMinsSet() {
    return Collections.synchronizedSet(
        Collections.newSetFromMap(new WeakHashMap<>()));
  }
}
