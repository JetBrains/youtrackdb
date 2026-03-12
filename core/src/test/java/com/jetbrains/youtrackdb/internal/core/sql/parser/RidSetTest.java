package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class RidSetTest extends DbTestBase {

  // --- Basic add/contains tests ---

  @Test
  public void testAddAndContains() {
    var set = new RidSet();
    RID rid = new RecordId(12, 100);
    Assert.assertFalse(set.contains(rid));
    Assert.assertTrue(set.add(rid));
    Assert.assertTrue(set.contains(rid));
    Assert.assertEquals(1, set.size());
  }

  @Test
  public void testAddPosition0() {
    var set = new RidSet();
    RID rid = new RecordId(12, 0);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testAddPosition31() {
    var set = new RidSet();
    RID rid = new RecordId(12, 31);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testAddPosition32() {
    var set = new RidSet();
    RID rid = new RecordId(12, 32);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testAddPosition63() {
    var set = new RidSet();
    RID rid = new RecordId(12, 63);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testAddPosition64() {
    var set = new RidSet();
    RID rid = new RecordId(12, 64);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testAddPosition65() {
    var set = new RidSet();
    RID rid = new RecordId(12, 65);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  // --- Duplicate add ---

  @Test
  public void testAddDuplicateReturnsFalse() {
    // Adding the same RID twice should return false on the second call.
    var set = new RidSet();
    RID rid = new RecordId(5, 42);
    Assert.assertTrue(set.add(rid));
    Assert.assertFalse(set.add(rid));
    Assert.assertEquals(1, set.size());
  }

  // --- Remove tests ---

  @Test
  public void testRemove() {
    var set = new RidSet();
    RID rid = new RecordId(12, 31);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    Assert.assertTrue(set.remove(rid));
    Assert.assertFalse(set.contains(rid));
    Assert.assertEquals(0, set.size());
  }

  @Test
  public void testRemoveNonExistentReturnsFalse() {
    // Removing a RID that was never added should return false.
    var set = new RidSet();
    RID rid = new RecordId(1, 1);
    Assert.assertFalse(set.remove(rid));
  }

  @Test
  public void testRemoveFromEmptyCollection() {
    // Removing from a collection ID that has no bitmap should return false.
    var set = new RidSet();
    set.add(new RecordId(5, 10));
    Assert.assertFalse(set.remove(new RecordId(99, 10)));
  }

  // --- Big collection ID and position ---

  @Test
  public void testBigCollectionId() {
    var set = new RidSet();
    RID rid = new RecordId(1200, 100);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    set.remove(rid);
    Assert.assertFalse(set.contains(rid));
  }

  @Test
  public void testMaxCollectionId() {
    // Test with COLLECTION_MAX (32767).
    var set = new RidSet();
    RID rid = new RecordId(RID.COLLECTION_MAX, 0);
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    Assert.assertEquals(1, set.size());
  }

  @Test
  public void testBigCollectionPosition() {
    var set = new RidSet();
    RID rid = new RecordId(12, 200L * 1000 * 1000);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    set.remove(rid);
    Assert.assertFalse(set.contains(rid));
  }

  @Test
  public void testVeryLargePosition() {
    // Test with a position that exceeds Integer.MAX_VALUE to exercise long addressing.
    var set = new RidSet();
    long bigPosition = (long) Integer.MAX_VALUE + 1000L;
    RID rid = new RecordId(3, bigPosition);
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    Assert.assertEquals(1, set.size());
    set.remove(rid);
    Assert.assertFalse(set.contains(rid));
    Assert.assertTrue(set.isEmpty());
  }

  // --- Negative RIDs ---

  @Test
  public void testNegativeCollectionId() {
    // RIDs with negative collection ID go into the negatives set.
    var set = new RidSet();
    RID rid = new RecordId(-1, 5);
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    Assert.assertEquals(1, set.size());
    set.remove(rid);
    Assert.assertFalse(set.contains(rid));
    Assert.assertEquals(0, set.size());
  }

  @Test
  public void testNegativePosition() {
    // RIDs with negative position go into the negatives set.
    var set = new RidSet();
    RID rid = new RecordId(5, -1);
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    Assert.assertEquals(1, set.size());
  }

  @Test
  public void testMixedNegativeAndPositiveRids() {
    // A set containing both negative and positive RIDs.
    var set = new RidSet();
    RID neg = new RecordId(-1, 10);
    RID pos = new RecordId(5, 10);
    set.add(neg);
    set.add(pos);
    Assert.assertTrue(set.contains(neg));
    Assert.assertTrue(set.contains(pos));
    Assert.assertEquals(2, set.size());
  }

  // --- isEmpty / size / clear ---

  @Test
  public void testIsEmptyOnNewSet() {
    var set = new RidSet();
    Assert.assertTrue(set.isEmpty());
    Assert.assertEquals(0, set.size());
  }

  @Test
  public void testIsEmptyAfterAddAndRemove() {
    var set = new RidSet();
    RID rid = new RecordId(1, 1);
    set.add(rid);
    Assert.assertFalse(set.isEmpty());
    set.remove(rid);
    Assert.assertTrue(set.isEmpty());
  }

  @Test
  public void testIsEmptyWithOnlyNegatives() {
    // isEmpty should consider negatives set too.
    var set = new RidSet();
    set.add(new RecordId(-1, 5));
    Assert.assertFalse(set.isEmpty());
  }

  @Test
  public void testClear() {
    var set = new RidSet();
    for (int i = 0; i < 100; i++) {
      set.add(new RecordId(i % 10, i));
    }
    set.add(new RecordId(-1, 5));
    Assert.assertFalse(set.isEmpty());
    set.clear();
    Assert.assertTrue(set.isEmpty());
    Assert.assertEquals(0, set.size());
  }

  @Test
  public void testSizeWithMultipleCollections() {
    var set = new RidSet();
    set.add(new RecordId(0, 0));
    set.add(new RecordId(0, 1));
    set.add(new RecordId(1, 0));
    set.add(new RecordId(100, 999));
    Assert.assertEquals(4, set.size());
  }

  // --- containsAll ---

  @Test
  public void testContainsAll() {
    var set = new RidSet();
    var rids = List.of(
        new RecordId(1, 0), new RecordId(1, 1), new RecordId(2, 5));
    set.addAll(rids);
    Assert.assertTrue(set.containsAll(rids));
    Assert.assertFalse(set.containsAll(List.of(new RecordId(1, 0), new RecordId(99, 99))));
  }

  // --- addAll ---

  @Test
  public void testAddAll() {
    var set = new RidSet();
    var rids = List.of(
        new RecordId(1, 10), new RecordId(1, 20), new RecordId(2, 30));
    Assert.assertTrue(set.addAll(rids));
    Assert.assertEquals(3, set.size());
    // Adding the same elements again should return false (no modification).
    Assert.assertFalse(set.addAll(rids));
  }

  // --- removeAll ---

  @Test
  public void testRemoveAll() {
    var set = new RidSet();
    var rids = List.of(
        new RecordId(1, 10), new RecordId(1, 20), new RecordId(2, 30));
    set.addAll(rids);
    Assert.assertTrue(set.removeAll(List.of(new RecordId(1, 10), new RecordId(2, 30))));
    Assert.assertEquals(1, set.size());
    Assert.assertTrue(set.contains(new RecordId(1, 20)));
  }

  @Test
  public void testRemoveAllNonExistent() {
    var set = new RidSet();
    set.add(new RecordId(1, 1));
    Assert.assertFalse(set.removeAll(List.of(new RecordId(99, 99))));
  }

  // --- toArray ---

  @Test
  public void testToArray() {
    var set = new RidSet();
    set.add(new RecordId(0, 5));
    set.add(new RecordId(0, 10));
    var arr = set.toArray();
    Assert.assertEquals(2, arr.length);
    var resultSet = new HashSet<>();
    for (var item : arr) {
      resultSet.add(item);
    }
    Assert.assertTrue(resultSet.contains(new RecordId(0, 5)));
    Assert.assertTrue(resultSet.contains(new RecordId(0, 10)));
  }

  @Test
  public void testToArrayTyped() {
    var set = new RidSet();
    set.add(new RecordId(0, 5));
    set.add(new RecordId(0, 10));
    RID[] arr = set.toArray(new RID[0]);
    Assert.assertEquals(2, arr.length);
  }

  // --- contains with non-RID types ---

  @Test
  public void testContainsNonRidObject() {
    // Passing an object that is neither RID nor Identifiable should return false.
    var set = new RidSet();
    set.add(new RecordId(1, 1));
    Assert.assertFalse(set.contains("not a RID"));
    Assert.assertFalse(set.contains(42));
  }

  // --- add null ---

  @Test(expected = IllegalArgumentException.class)
  public void testAddNullThrows() {
    var set = new RidSet();
    set.add(null);
  }

  // --- remove non-RID ---

  @Test(expected = IllegalArgumentException.class)
  public void testRemoveNonRidThrows() {
    var set = new RidSet();
    set.remove("not a rid");
  }

  // --- retainAll unsupported ---

  @Test(expected = UnsupportedOperationException.class)
  public void testRetainAllThrows() {
    var set = new RidSet();
    set.retainAll(List.of());
  }

  // --- Iterator tests ---

  @Test
  public void testIteratorBasicOrder() {
    // Iterator should yield RIDs in ascending collection ID then ascending position order.
    Set<RID> set = new RidSet();
    var collections = 100;
    var idsPerCollection = 10;

    for (var collection = 0; collection < collections; collection++) {
      for (long id = 0; id < idsPerCollection; id++) {
        set.add(new RecordId(collection, id));
      }
    }

    var iterator = set.iterator();
    for (var collection = 0; collection < collections; collection++) {
      for (long id = 0; id < idsPerCollection; id++) {
        Assert.assertTrue(iterator.hasNext());
        var next = iterator.next();
        Assert.assertNotNull(next);
        Assert.assertEquals(new RecordId(collection, id), next);
      }
    }
    Assert.assertFalse(iterator.hasNext());
  }

  @Test
  public void testIteratorWithLargeOffset() {
    // Test iterating RIDs at very large positions.
    Set<RID> control = new HashSet<>();
    Set<RID> set = new RidSet();

    var offset = (long) Integer.MAX_VALUE + 1000L;
    long idsPerCollection = 10;

    var collection = 1;
    for (long id = 0; id < idsPerCollection; id++) {
      var rid = new RecordId(collection, offset + id);
      set.add(rid);
      control.add(rid);
    }
    var iterator = set.iterator();

    for (long id = 0; id < idsPerCollection; id++) {
      Assert.assertTrue(iterator.hasNext());
      var next = iterator.next();
      Assert.assertNotNull(next);
      control.remove(next);
    }

    Assert.assertFalse(iterator.hasNext());
    Assert.assertTrue(control.isEmpty());
  }

  @Test
  public void testIteratorNegativesFirst() {
    // Negatives should be yielded before positive RIDs.
    var set = new RidSet();
    RID neg = new RecordId(-1, 5);
    RID pos = new RecordId(0, 0);
    set.add(pos);
    set.add(neg);

    var iterator = set.iterator();
    Assert.assertTrue(iterator.hasNext());
    var first = iterator.next();
    Assert.assertEquals(neg, first);

    Assert.assertTrue(iterator.hasNext());
    var second = iterator.next();
    Assert.assertEquals(pos, second);

    Assert.assertFalse(iterator.hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void testIteratorNextOnExhausted() {
    var set = new RidSet();
    set.add(new RecordId(0, 0));
    var iterator = set.iterator();
    iterator.next();
    iterator.next(); // should throw
  }

  @Test
  public void testIteratorEmpty() {
    var set = new RidSet();
    var iterator = set.iterator();
    Assert.assertFalse(iterator.hasNext());
  }

  @Test
  public void testIteratorSparseCollections() {
    // Collections with large gaps between IDs (e.g., 0, 500, 30000).
    var set = new RidSet();
    set.add(new RecordId(0, 5));
    set.add(new RecordId(500, 10));
    set.add(new RecordId(30000, 20));

    var results = new ArrayList<RID>();
    for (var rid : set) {
      results.add(rid);
    }

    Assert.assertEquals(3, results.size());
    Assert.assertEquals(new RecordId(0, 5), results.get(0));
    Assert.assertEquals(new RecordId(500, 10), results.get(1));
    Assert.assertEquals(new RecordId(30000, 20), results.get(2));
  }

  @Test
  public void testIteratorSparsePositions() {
    // Positions with large gaps within a single collection.
    var set = new RidSet();
    set.add(new RecordId(1, 0));
    set.add(new RecordId(1, 1000000));
    set.add(new RecordId(1, 2000000000L));

    var results = new ArrayList<RID>();
    for (var rid : set) {
      results.add(rid);
    }

    Assert.assertEquals(3, results.size());
    Assert.assertEquals(0L, results.get(0).getCollectionPosition());
    Assert.assertEquals(1000000L, results.get(1).getCollectionPosition());
    Assert.assertEquals(2000000000L, results.get(2).getCollectionPosition());
  }

  // --- Backward compatibility: constructor with bucketSize ---

  @Test
  public void testBucketSizeConstructorStillWorks() {
    // The old constructor with bucketSize should still work (ignored parameter).
    var set = new RidSet(Integer.MAX_VALUE / 10);
    set.add(new RecordId(5, 100));
    Assert.assertTrue(set.contains(new RecordId(5, 100)));
  }

  // --- Stress: many RIDs ---

  @Test
  public void testManyRidsPerCollection() {
    // Add 100k RIDs in a single collection and verify all are present.
    var set = new RidSet();
    int count = 100_000;
    for (long i = 0; i < count; i++) {
      set.add(new RecordId(0, i));
    }
    Assert.assertEquals(count, set.size());
    for (long i = 0; i < count; i++) {
      Assert.assertTrue(set.contains(new RecordId(0, i)));
    }
    // Verify iteration count
    int iterCount = 0;
    for (var ignored : set) {
      iterCount++;
    }
    Assert.assertEquals(count, iterCount);
  }

  @Test
  public void testManyCollections() {
    // Add one RID per collection for 10k collections.
    var set = new RidSet();
    int collections = 10_000;
    for (int c = 0; c < collections; c++) {
      set.add(new RecordId(c, c * 10L));
    }
    Assert.assertEquals(collections, set.size());
    for (int c = 0; c < collections; c++) {
      Assert.assertTrue(set.contains(new RecordId(c, c * 10L)));
    }
  }

  // --- Edge case: add and remove same RID multiple times ---

  @Test
  public void testAddRemoveAddCycle() {
    var set = new RidSet();
    RID rid = new RecordId(7, 77);
    Assert.assertTrue(set.add(rid));
    Assert.assertTrue(set.remove(rid));
    Assert.assertTrue(set.isEmpty());
    Assert.assertTrue(set.add(rid));
    Assert.assertTrue(set.contains(rid));
    Assert.assertEquals(1, set.size());
  }

  // --- for-each loop (Iterable contract) ---

  @Test
  public void testForEachLoop() {
    var set = new RidSet();
    set.add(new RecordId(1, 10));
    set.add(new RecordId(1, 20));
    set.add(new RecordId(2, 30));

    var collected = new HashSet<RID>();
    for (var rid : set) {
      collected.add(rid);
    }
    Assert.assertEquals(3, collected.size());
    Assert.assertTrue(collected.contains(new RecordId(1, 10)));
    Assert.assertTrue(collected.contains(new RecordId(1, 20)));
    Assert.assertTrue(collected.contains(new RecordId(2, 30)));
  }

  // --- Mutation-killing tests ---

  @Test
  public void testRemoveNonExistentPositionFromExistingCollection() {
    // Kills mutant: RidSet.java:157 (return existed → return true).
    // The collection exists but the specific position does not.
    var set = new RidSet();
    set.add(new RecordId(5, 10));
    set.add(new RecordId(5, 20));
    Assert.assertFalse(set.remove(new RecordId(5, 99)));
    Assert.assertEquals(2, set.size());
    Assert.assertTrue(set.contains(new RecordId(5, 10)));
    Assert.assertTrue(set.contains(new RecordId(5, 20)));
  }

  @Test
  public void testContainsWithIdentifiableWrapper() {
    // Kills mutant: RidSet.java:62 (instanceof Identifiable → false).
    // Uses a non-RID Identifiable whose getIdentity() points to a RID in the set.
    var set = new RidSet();
    set.add(new RecordId(5, 10));

    Identifiable wrapper = new Identifiable() {
      @Nonnull
      @Override
      public RID getIdentity() {
        return new RecordId(5, 10);
      }

      @Override
      public int compareTo(@Nonnull Identifiable o) {
        return getIdentity().compareTo(o);
      }
    };

    Assert.assertTrue(set.contains(wrapper));
  }

  @Test
  public void testContainsWithIdentifiableWrapperNotInSet() {
    // Verifies that a non-RID Identifiable pointing to a missing RID returns false.
    var set = new RidSet();
    set.add(new RecordId(5, 10));

    Identifiable wrapper = new Identifiable() {
      @Nonnull
      @Override
      public RID getIdentity() {
        return new RecordId(5, 99);
      }

      @Override
      public int compareTo(@Nonnull Identifiable o) {
        return getIdentity().compareTo(o);
      }
    };

    Assert.assertFalse(set.contains(wrapper));
  }

  @Test
  public void testToArrayTypedExactSize() {
    // Kills mutant: RidSet.java:97 (a.length < sz → a.length <= sz).
    // When the input array is exactly the right size, the same array should be returned.
    var set = new RidSet();
    set.add(new RecordId(0, 5));
    set.add(new RecordId(0, 10));
    RID[] input = new RID[2];
    RID[] result = set.toArray(input);
    Assert.assertSame(input, result);
    Assert.assertNotNull(result[0]);
    Assert.assertNotNull(result[1]);
  }

  @Test
  public void testToArrayTypedOversized() {
    // Kills mutant: RidSet.java:104 (a.length > sz → false).
    // When the input array is larger than the set, the element after the last must be null.
    var set = new RidSet();
    set.add(new RecordId(0, 5));
    RID[] input = new RID[3];
    input[1] = new RecordId(99, 99); // sentinel
    input[2] = new RecordId(99, 99); // sentinel
    RID[] result = set.toArray(input);
    Assert.assertSame(input, result);
    Assert.assertNotNull(result[0]);
    Assert.assertNull(result[1]); // null terminator per Set.toArray(T[]) contract
  }

  @Test
  public void testIteratorHasNextReturnsFalseOnPositiveOnlySet() {
    // Tests that hasNext() correctly returns false when only positive RIDs are exhausted.
    var set = new RidSet();
    set.add(new RecordId(0, 0));
    var iterator = set.iterator();
    Assert.assertTrue(iterator.hasNext());
    iterator.next();
    Assert.assertFalse(iterator.hasNext());
  }

  @Test
  public void testIteratorMultipleHasNextCallsIdempotent() {
    // Calling hasNext() multiple times without next() should always return the same result.
    var set = new RidSet();
    set.add(new RecordId(0, 0));
    var iterator = set.iterator();
    Assert.assertTrue(iterator.hasNext());
    Assert.assertTrue(iterator.hasNext());
    Assert.assertTrue(iterator.hasNext());
    iterator.next();
    Assert.assertFalse(iterator.hasNext());
    Assert.assertFalse(iterator.hasNext());
  }
}
