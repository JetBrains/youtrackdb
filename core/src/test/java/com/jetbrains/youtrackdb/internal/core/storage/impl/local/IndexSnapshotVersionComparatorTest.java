package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.*;

import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import java.util.Comparator;
import org.junit.Test;

/**
 * Unit tests for {@link AbstractStorage#INDEX_SNAPSHOT_VERSION_COMPARATOR}.
 * This comparator is the ordering foundation for the ConcurrentSkipListMap
 * backing all snapshot visibility indexes.
 */
public class IndexSnapshotVersionComparatorTest {

  private static final Comparator<CompositeKey> CMP =
      AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR;

  @Test
  public void differentVersion_orderedByVersion() {
    var a = new CompositeKey(1L, "alpha", 100L);
    var b = new CompositeKey(1L, "alpha", 200L);
    assertTrue("Lower version must sort before higher", CMP.compare(a, b) < 0);
    assertTrue("Higher version must sort after lower", CMP.compare(b, a) > 0);
  }

  @Test
  public void sameVersion_differentUserKey_distinguishes() {
    var a = new CompositeKey(1L, "alpha", 100L);
    var b = new CompositeKey(1L, "beta", 100L);
    assertNotEquals("Same version, different user key must not be equal",
        0, CMP.compare(a, b));
  }

  @Test
  public void sameVersion_differentSize_orderedBySize() {
    // Same version (100L), shared elements match (1L, 100L), but longer
    // has an extra element. After element-wise tie through minSize, the
    // size tiebreaker applies (shorter key sorts first).
    var shorter = new CompositeKey(1L, 100L);
    var longer = new CompositeKey(1L, 100L, 100L);
    assertTrue("Shorter key should sort before longer",
        CMP.compare(shorter, longer) < 0);
  }

  @Test
  public void sameVersion_sameElements_equal() {
    var a = new CompositeKey(1L, "alpha", 100L);
    var b = new CompositeKey(1L, "alpha", 100L);
    assertEquals("Identical keys must compare as equal", 0, CMP.compare(a, b));
  }

  @Test
  public void sameVersion_nullElement_doesNotThrow() {
    var a = new CompositeKey(1L, (Object) null, 100L);
    var b = new CompositeKey(1L, "value", 100L);
    // Must not throw NPE — DefaultComparator handles nulls
    int cmp = CMP.compare(a, b);
    assertNotEquals("Null vs non-null must not be equal", 0, cmp);
  }

  @Test
  public void symmetry() {
    var a = new CompositeKey(1L, "alpha", 100L);
    var b = new CompositeKey(1L, "beta", 100L);
    assertEquals("compare(a,b) must be -compare(b,a)",
        Integer.signum(CMP.compare(a, b)),
        -Integer.signum(CMP.compare(b, a)));
  }

  @Test
  public void transitivity() {
    var a = new CompositeKey(1L, "alpha", 100L);
    var b = new CompositeKey(1L, "beta", 100L);
    var c = new CompositeKey(1L, "gamma", 100L);
    if (CMP.compare(a, b) < 0 && CMP.compare(b, c) < 0) {
      assertTrue("Transitivity: a < b and b < c implies a < c",
          CMP.compare(a, c) < 0);
    }
  }
}
