package com.jetbrains.youtrack.db.internal.common.collection;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class IdentifiableMultiValueTest {

  @Test
  public void testListSize() {
    List<String> collection = new ArrayList<String>();
    MultiValue.add(collection, "foo");
    MultiValue.add(collection, "bar");
    MultiValue.add(collection, "baz");

    Assert.assertEquals(MultiValue.getSize(collection), 3);
  }

  @Test
  public void testArraySize() {
    var collection = new String[]{"foo", "bar", "baz"};
    Assert.assertEquals(MultiValue.getSize(collection), 3);
  }

  @Test
  public void testListFirstLast() {
    List<String> collection = new ArrayList<String>();
    MultiValue.add(collection, "foo");
    MultiValue.add(collection, "bar");
    MultiValue.add(collection, "baz");

    Assert.assertEquals(MultiValue.getFirstValue(collection), "foo");
    Assert.assertEquals(MultiValue.getLastValue(collection), "baz");
  }

  @Test
  public void testArrayFirstLast() {
    var collection = new String[]{"foo", "bar", "baz"};
    Assert.assertEquals(MultiValue.getFirstValue(collection), "foo");
    Assert.assertEquals(MultiValue.getLastValue(collection), "baz");
  }

  @Test
  public void testListValue() {
    Assert.assertNull(MultiValue.getValue(null, 0));
    List<String> collection = new ArrayList<String>();
    MultiValue.add(collection, "foo");
    MultiValue.add(collection, "bar");
    MultiValue.add(collection, "baz");

    Assert.assertNull(MultiValue.getValue(new Object(), 0));

    Assert.assertEquals(MultiValue.getValue(collection, 0), "foo");
    Assert.assertEquals(MultiValue.getValue(collection, 2), "baz");
    Assert.assertNull(MultiValue.getValue(new Object(), 3));
  }

  @Test
  public void testListRemove() {
    Assert.assertNull(MultiValue.getValue(null, 0));
    List<String> collection = new ArrayList<String>();
    MultiValue.add(collection, "foo");
    MultiValue.add(collection, "bar");
    MultiValue.add(collection, "baz");

    MultiValue.remove(collection, "bar", true);
    Assert.assertEquals(collection.size(), 2);
  }

  public void testToString() {
    List<String> collection = new ArrayList<String>();
    MultiValue.add(collection, 1);
    MultiValue.add(collection, 2);
    MultiValue.add(collection, 3);
    Assert.assertEquals(MultiValue.toString(collection), "[1, 2, 3]");
  }
}
