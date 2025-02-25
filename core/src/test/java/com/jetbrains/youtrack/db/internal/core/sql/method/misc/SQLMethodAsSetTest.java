package com.jetbrains.youtrack.db.internal.core.sql.method.misc;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the "asSet()" method implemented by the SQLMethodAsSet class. Note that the only input to
 * the execute() method from the SQLMethod interface that is used is the ioResult argument (the 4th
 * argument).
 */
public class SQLMethodAsSetTest extends DbTestBase {

  private SQLMethodAsSet function;

  @Before
  public void setup() {
    function = new SQLMethodAsSet();
  }

  @Test
  public void testSet() {
    // The expected behavior is to return the set itself.
    var aSet = new HashSet<Object>();
    aSet.add(1);
    aSet.add("2");
    var result = function.execute(null, null, null, aSet, null);
    assertEquals(result, aSet);
  }

  @Test
  public void testNull() {
    // The expected behavior is to return an empty set.
    var result = function.execute(null, null, null, null, null);
    assertEquals(result, new HashSet<Object>());
  }

  @Test
  public void testCollection() {
    // The expected behavior is to return a set with all of the elements
    // of the collection in it.
    var aCollection = new ArrayList<Object>();
    aCollection.add(1);
    aCollection.add("2");
    var result = function.execute(null, null, null, aCollection, null);

    var expected = new HashSet<Object>();
    expected.add(1);
    expected.add("2");
    assertEquals(result, expected);
  }

  @Test
  public void testIterable() {
    // The expected behavior is to return a set with all of the elements
    // of the iterable in it.
    var values = new ArrayList<Object>();
    values.add(1);
    values.add("2");

    var anIterable = new TestIterable<Object>(values);
    var result = function.execute(null, null, null, anIterable, null);

    var expected = new HashSet<Object>();
    expected.add(1);
    expected.add("2");

    assertEquals(result, expected);
  }

  @Test
  public void testIterator() {
    // The expected behavior is to return a set with all of the elements
    // of the iterator in it.
    var values = new ArrayList<Object>();
    values.add(1);
    values.add("2");

    var anIterable = new TestIterable<Object>(values);
    var result = function.execute(null, null, null, anIterable.iterator(), null);

    var expected = new HashSet<Object>();
    expected.add(1);
    expected.add("2");

    assertEquals(result, expected);
  }

  @Test
  public void testODocument() {
    // The expected behavior is to return a set with only the single
    // EntityImpl in it.
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.field("f1", 1);
    doc.field("f2", 2);

    var result = function.execute(null, null, null, doc, null);

    var expected = new HashSet<Object>();
    expected.add(doc);

    assertEquals(result, expected);
    session.rollback();
  }

  @Test
  public void testOtherSingleValue() {
    // The expected behavior is to return a set with only the single
    // element in it.

    var result = function.execute(null, null, null, 4, null);
    var expected = new HashSet<Object>();
    expected.add(4);
    assertEquals(result, expected);
  }

  @Test
  public void testIterableOrder() {

    var values = new ArrayList<Integer>(IntStream.rangeClosed(0, 1000).boxed().toList());
    var rnd = new Random();
    var seed = System.currentTimeMillis();
    rnd.setSeed(seed);
    System.out.println(seed);
    Collections.shuffle(values, rnd);

    var anIterable = new TestIterable<Integer>(values);
    var result = function.execute(null, null, null, anIterable, null);

    Assert.assertTrue(result instanceof Set<?>);
    Assert.assertEquals(values, ((Set<?>) result).stream().toList());
  }
}
