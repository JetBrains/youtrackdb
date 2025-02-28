package com.jetbrains.youtrack.db.internal.core.sql.method.misc;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the "asMap()" method implemented by the SQLMethodAsMap class. Note that the only input to
 * the execute() method from the SQLMethod interface that is used is the ioResult argument (the 4th
 * argument).
 */
public class SQLMethodAsMapTest extends DbTestBase {

  private SQLMethodAsMap function;

  @Before
  public void setup() {
    function = new SQLMethodAsMap();
  }

  @Test
  public void testMap() {
    // The expected behavior is to return the map itself.
    var aMap = new HashMap<Object, Object>();
    aMap.put("p1", 1);
    aMap.put("p2", 2);
    var result = function.execute(null, null, null, aMap, null);
    assertEquals(result, aMap);
  }

  @Test
  public void testNull() {
    // The expected behavior is to return an empty map.
    var result = function.execute(null, null, null, null, null);
    assertEquals(result, new HashMap<Object, Object>());
  }

  @Test
  public void testODocument() {
    // The expected behavior is to return a map that has the field names mapped
    // to the field values of the EntityImpl.
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.field("f1", 1);
    doc.field("f2", 2);

    var result = function.execute(null, null, null, doc, null);

    assertEquals(result, doc.toMap());
    session.commit();
  }

  @Test
  public void testIterable() {
    // The expected behavior is to return a map where the even values (0th,
    // 2nd, 4th, etc) are keys and the odd values (1st, 3rd, etc.) are
    // property values.
    var aCollection = new ArrayList<Object>();
    aCollection.add("p1");
    aCollection.add(1);
    aCollection.add("p2");
    aCollection.add(2);

    var result = function.execute(null, null, null, aCollection, null);

    var expected = new HashMap<Object, Object>();
    expected.put("p1", 1);
    expected.put("p2", 2);
    assertEquals(result, expected);
  }

  @Test
  public void testIterator() {
    // The expected behavior is to return a map where the even values (0th,
    // 2nd, 4th, etc) are keys and the odd values (1st, 3rd, etc.) are
    // property values.
    var aCollection = new ArrayList<Object>();
    aCollection.add("p1");
    aCollection.add(1);
    aCollection.add("p2");
    aCollection.add(2);

    var result = function.execute(null, null, null, aCollection.iterator(), null);

    var expected = new HashMap<Object, Object>();
    expected.put("p1", 1);
    expected.put("p2", 2);
    assertEquals(result, expected);
  }

  @Test
  public void testOtherValue() {
    // The expected behavior is to return null.
    var result = function.execute(null, null, null, Integer.valueOf(4), null);
    assertNull(result);
  }
}
