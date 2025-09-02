package com.jetbrains.youtrackdb.internal.core.sql.method.misc;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the "asList()" method implemented by the SQLMethodAsList class. Note that the only input to
 * the execute() method from the SQLMethod interface that is used is the ioResult argument (the 4th
 * argument).
 */
public class SQLMethodAsListTest extends DbTestBase {

  private SQLMethodAsList function;

  @Before
  public void setup() {
    function = new SQLMethodAsList();
  }

  @Test
  public void testList() {
    // The expected behavior is to return the list itself.
    var aList = new ArrayList<Object>();
    aList.add(1);
    aList.add("2");
    var result = function.execute(null, null, null, aList, null);
    assertEquals(result, aList);
  }

  @Test
  public void testNull() {
    // The expected behavior is to return an empty list.
    var result = function.execute(null, null, null, null, null);
    assertEquals(result, new ArrayList<Object>());
  }

  @Test
  public void testCollection() {
    // The expected behavior is to return a list with all of the elements
    // of the collection in it.
    Set<Object> aCollection = new LinkedHashSet<Object>();
    aCollection.add(1);
    aCollection.add("2");
    var result = function.execute(null, null, null, aCollection, null);

    var expected = new ArrayList<Object>();
    expected.add(1);
    expected.add("2");
    assertEquals(result, expected);
  }

  @Test
  public void testIterable() {
    // The expected behavior is to return a list with all of the elements
    // of the iterable in it, in order of the collecitons iterator.
    var expected = new ArrayList<Object>();
    expected.add(1);
    expected.add("2");

    var anIterable = new TestIterable<Object>(expected);
    var result = function.execute(null, null, null, anIterable, null);

    assertEquals(result, expected);
  }

  @Test
  public void testIterator() {
    // The expected behavior is to return a list with all of the elements
    // of the iterator in it, in order of the iterator.
    var expected = new ArrayList<Object>();
    expected.add(1);
    expected.add("2");

    var anIterable = new TestIterable<Object>(expected);
    var result = function.execute(null, null, null, anIterable.iterator(), null);

    assertEquals(result, expected);
  }

  @Test
  public void testODocument() {
    // The expected behavior is to return a list with only the single
    // EntityImpl in it.
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("f1", 1);
    doc.setProperty("f2", 2);

    var result = function.execute(null, null, null, doc, null);

    var expected = new ArrayList<Object>();
    expected.add(doc);

    assertEquals(result, expected);
    session.commit();
  }

  @Test
  public void testOtherSingleValue() {
    // The expected behavior is to return a list with only the single
    // element in it.

    var result = function.execute(null, null, null, Integer.valueOf(4), null);
    var expected = new ArrayList<Object>();
    expected.add(Integer.valueOf(4));
    assertEquals(result, expected);
  }
}
