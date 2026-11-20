package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import it.unimi.dsi.fastutil.HashCommon;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Random;
import org.junit.Test;
import org.junit.experimental.categories.Category;

public class PageFileIdDistributionTest {

  private enum Encoding {
    MIXED, RANDOM, PACKED
  }

  // This is the codec's raw packing seam, not a second implementation of its bit fields.
  private static int packed(PageFileRole role, int component) throws Exception {
    Method pack = PageFileIdentity.class.getDeclaredMethod("pack", int.class, int.class, int.class);
    pack.setAccessible(true);
    return (int) pack.invoke(null, role.kind().code(), role.code(), component);
  }

  private static int[] files(Encoding encoding) throws Exception {
    var random = new Random(1620);
    var used = new HashSet<Integer>();
    var files = new int[2006];
    var roles = PageFileRole.values();
    for (int i = 0; i < files.length; i++) {
      var role = roles[i % roles.length];
      int component = i / roles.length + 2;
      if (encoding == Encoding.MIXED) {
        files[i] = new PageFileIdentity(role, component).internalId();
      } else if (encoding == Encoding.PACKED) {
        files[i] = packed(role, component);
      } else {
        do {
          files[i] = random.nextInt(Integer.MAX_VALUE - 1) + 1;
        } while (!used.add(files[i]));
      }
    }
    return files;
  }

  // The issue's two exact collision pairs must separate through the real codec and hash methods.
  @Test
  public void issueCollisionPairsSeparate() throws Exception {
    int a = new PageFileIdentity(PageFileRole.RECORD_DATA, 2).internalId();
    int b = new PageFileIdentity(PageFileRole.RECORD_DATA, 3).internalId();
    assertNotEquals(new PageKey(a, 31).hashCode(), new PageKey(b, 0).hashCode());
    assertNotEquals(sketch(a, 0), sketch(b, 31));
    int oldA = packed(PageFileRole.RECORD_DATA, 2);
    int oldB = packed(PageFileRole.RECORD_DATA, 3);
    assertEquals(new PageKey(oldA, 31).hashCode(), new PageKey(oldB, 0).hashCode());
    assertEquals(sketch(oldA, 0), sketch(oldB, 31));
  }

  private static int sketch(int fileId, int page) {
    return ConcurrentLongIntHashMap.hashForFrequencySketch(
        AbstractWriteCache.composeFileId(1, fileId), page);
  }

  // 125 complete role families plus six files have 59 pages each, with 662 extra pages.
  // Random IDs provide a layout-27-style baseline. Packed IDs must fail the same limits.
  @Test
  public void pageBucketsAndSketchInputsStayNearRandom() throws Exception {
    for (var encoding : Encoding.values()) {
      for (boolean spread : new boolean[] {false, true}) {
        int[] buckets = new int[262144];
        var sketchInputs = new HashSet<Integer>();
        int pages = 0;
        for (int i = 0, ids[] = files(encoding); i < ids.length; i++) {
          for (int page = 0; page < (i < 662 ? 60 : 59); page++) {
            int hash = new PageKey(ids[i], page).hashCode();
            if (spread) {
              hash ^= hash >>> 16;
            }
            buckets[hash & (buckets.length - 1)]++;
            sketchInputs.add(sketch(ids[i], page));
            pages++;
          }
        }
        assertEquals(119016, pages);
        int occupied = 0;
        int maximum = 0;
        for (int count : buckets) {
          occupied += count == 0 ? 0 : 1;
          maximum = Math.max(maximum, count);
        }
        System.out.printf("%s spread=%s occupied=%d max=%d sketch=%d%n",
            encoding, spread, occupied, maximum, sketchInputs.size());
        if (encoding == Encoding.PACKED) {
          assertTrue(occupied < 85000);
          assertTrue(sketchInputs.size() < 118000);
        } else {
          assertTrue(encoding + " occupied=" + occupied, occupied >= 85000);
          assertTrue(encoding + " maximum=" + maximum, maximum <= 10);
          assertTrue(sketchInputs.size() >= 118000);
        }
      }
    }
  }

  // Fastutil's real initial-bucket hash feeds a 4096-slot linear-probing table at 49% load.
  // Probe limits allow ordinary collisions but reject the structured packing.
  @Test
  public void intTableProbesStayNearRandom() throws Exception {
    for (var encoding : Encoding.values()) {
      int[] table = new int[4096];
      int occupied = 0;
      var initial = new HashSet<Integer>();
      int probes = 0;
      int maximum = 0;
      for (int id : files(encoding)) {
        int slot = HashCommon.mix(id) & (table.length - 1);
        initial.add(slot);
        int length = 1;
        while (table[slot] != 0) {
          length++;
          slot = (slot + 1) & (table.length - 1);
        }
        table[slot] = id;
        occupied++;
        probes += length;
        maximum = Math.max(maximum, length);
      }
      double mean = (double) probes / occupied;
      System.out.printf("%s initial=%d mean=%.3f max=%d%n",
          encoding, initial.size(), mean, maximum);
      if (encoding == Encoding.PACKED) {
        assertTrue(initial.size() < 1400);
      } else {
        assertTrue(initial.size() >= 1400);
        assertTrue(mean < 1.9);
        assertTrue(maximum <= 30);
      }
    }
  }

  // Real lock objects expose stripe selection without duplicating the manager's hash.
  // Sixteen roles at one component must not all share one stripe at any supported size.
  // Run sequentially because managers capture the process-wide stripe setting at construction.
  @Test
  @Category(SequentialTest.class)
  public void roleFamiliesSpreadAcrossLockStripes() throws Exception {
    var setting = GlobalConfiguration.ENVIRONMENT_LOCK_MANAGER_CONCURRENCY_LEVEL;
    Object previous = setting.getValue();
    boolean previouslyChanged = setting.isChanged();
    try {
      for (int stripes : new int[] {16, 32, 64, 128, 256, 512}) {
        setting.setValue(stripes);
        for (var encoding : Encoding.values()) {
          var manager = new PartitionedLockManager<Integer>();
          var counts = new IdentityHashMap<Object, Integer>();
          int[] ids = files(encoding);
          for (int i = 0; i < 16; i++) {
            var lock = manager.acquireExclusiveLock(ids[i]);
            try {
              counts.merge(lock, 1, Integer::sum);
            } finally {
              lock.unlock();
            }
          }
          int maximum = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
          System.out.printf("%s stripes=%d occupied=%d max=%d%n",
              encoding, stripes, counts.size(), maximum);
          if (encoding == Encoding.PACKED) {
            assertEquals(1, counts.size());
          } else {
            assertTrue(counts.size() >= 8);
            assertTrue(maximum <= 4);
          }
        }
      }
    } finally {
      // Preserve the default sentinel as well as explicitly configured values.
      if (previouslyChanged) {
        setting.setValue(previous);
      } else {
        setting.resetToDefault();
      }
      assertEquals(previous, setting.getValue());
      assertEquals(previouslyChanged, setting.isChanged());
    }
  }
}
