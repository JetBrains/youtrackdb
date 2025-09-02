package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.junit.Assert;
import org.junit.Test;

public class GetSchemaPropertyOnLoadValueTest extends DbTestBase {

  @Test
  public void testOnloadValue() {
    session.createClass("test");
    session.begin();
    var doc = (EntityImpl) session.newEntity("test");
    doc.setProperty("name", "John Doe");

    session.commit();
    RID id = doc.getIdentity();
    session.activateOnCurrentThread();
    session.begin();
    EntityImpl doc2 = session.load(id);
    doc2.setProperty("name", "Sun Doe");

    doc2.setProperty("name", "Jane Doe");

    Assert.assertEquals("John Doe", doc2.getPropertyOnLoadValue("name"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testOnLoadValueForList() throws IllegalArgumentException {
    session.createVertexClass("test");
    session.createEdgeClass("myLink");
    session.begin();
    var doc = session.newVertex("test");

    IntStream.rangeClosed(1, 8)
        .forEach(
            i -> {
              var linked = session.newVertex("test");
              linked.setProperty("name", i + "");
              doc.addEdge(linked, "myLink");
            });
    session.commit();

    session.begin();
    var loadedDoc = session.<Entity>load(doc.getIdentity());
    loadedDoc.getPropertyOnLoadValue(Vertex.DIRECTION_OUT_PREFIX + "myLink");
  }

  @Test
  public void testOnLoadValueForScalarList() throws IllegalArgumentException {
    session.createVertexClass("test");
    session.begin();
    var vertex = session.newVertex("test");
    vertex.getOrCreateEmbeddedList("list").addAll(Arrays.asList(1, 2, 3));
    session.commit();
    session.begin();
    vertex = session.load(vertex.getIdentity());
    List<Integer> storedList = vertex.getProperty("list");
    storedList.add(4);
    List<Integer> onLoad = vertex.getPropertyOnLoadValue("list");
    Assert.assertEquals(3, onLoad.size());
  }

  @Test
  public void testOnLoadValueForScalarSet() throws IllegalArgumentException {
    session.createVertexClass("test");
    session.begin();
    var doc = session.newVertex("test");
    doc.getOrCreateEmbeddedSet("set").addAll(new HashSet<>(Arrays.asList(1, 2, 3)));
    session.commit();
    session.begin();
    doc = session.load(doc.getIdentity());
    Set<Integer> storedSet = doc.getProperty("set");
    storedSet.add(4);
    Set<Integer> onLoad = doc.getPropertyOnLoadValue("set");
    Assert.assertEquals(3, onLoad.size());
  }

  @Test
  public void testStringBlobOnLoadValue() {
    session.createVertexClass("test");

    session.begin();
    var before = "Hello World";
    var byteArrayBefore = before.getBytes();
    var after = "Goodbye Cruel World";

    var byteArrayAfter = after.getBytes();
    var oBlob = session.newBlob(byteArrayBefore);
    var oBlob2 = session.newBlob(byteArrayAfter);

    var entity = (VertexEntityImpl) session.newVertex("test");
    entity.setProperty("stringBlob", oBlob);
    session.commit();

    session.begin();
    entity = session.load(entity.getIdentity());
    var activeTx = session.getActiveTransaction();
    oBlob2 = activeTx.load(oBlob2);

    entity.setLazyLoad(true);
    entity.setProperty("stringBlob", oBlob2);
    RecordBytes onLoad = entity.getPropertyOnLoadValue("stringBlob");
    Assert.assertEquals(before, new String(onLoad.toStream()));
    Assert.assertEquals(
        after, new String(((RecordBytes) entity.getProperty("stringBlob")).toStream()));
    // no lazy load
    entity.setLazyLoad(false);
    Assert.assertTrue(entity.getPropertyOnLoadValue("stringBlob") instanceof RID);
  }

  @Test
  public void testRandomOnLoadValue() {
    var seed = 1739881589149L;//System.currentTimeMillis();
    System.out.println("Seed is " + seed);
    var random = new Random(seed);
    var propertyNames = Arrays.asList("prop1", "prop2", "prop3", "prop4");
    var values = new ArrayList<>();
    values.add(100);
    values.add(1000L);
    values.add(100.0);
    values.add(500.0d);
    values.add((byte) 0xf0);
    values.add("Hello");
    values.add(new HashSet<>(Arrays.asList(99, 22, 21)));
    values.add(Arrays.asList(1, 2, 3, 4));
    values.add(Arrays.asList("1", "2", "3", "4"));

    var operations = new ArrayList<BiFunction<Vertex, String, Void>>();
    operations.add(
        (oVertex, propertyName) -> {
          var value = values.get(random.nextInt(values.size()));
          if (value instanceof List<?> list) {
            oVertex.newEmbeddedList(propertyName).addAll(list);
          } else if (value instanceof Set<?>) {
            oVertex.newEmbeddedSet(propertyName).addAll((Set<?>) value);
          } else {
            oVertex.setProperty(propertyName, value);
          }
          return null;
        });
    operations.add(
        (oVertex, propertyName) -> {
          if (oVertex.hasProperty(propertyName)) {
            oVertex.removeProperty(propertyName);
          }
          return null;
        });

    session.createVertexClass("test");
    session.begin();
    var v = session.newVertex("test");
    var initialValues = new HashMap<String, Object>();
    propertyNames.forEach(
        name -> {
          var value = values.get(random.nextInt(values.size()));
          if (value instanceof List<?> list) {
            v.newEmbeddedList(name).addAll(list);
          } else if (value instanceof Set<?>) {
            v.newEmbeddedSet(name).addAll((Set<?>) value);
          } else {
            v.setProperty(name, value);
          }
        });

    session.commit();
    for (var txId = 0; txId < 5; txId++) {
      session.begin();
      var activeTx = session.getActiveTransaction();
      var boundDoc = activeTx.<Vertex>load(v);
      propertyNames.forEach(name -> initialValues.put(name, boundDoc.getProperty(name)));
      for (var i = 0; i < 1000; i++) {
        var operation = operations.get(random.nextInt(operations.size()));
        var propertyName = propertyNames.get(random.nextInt(propertyNames.size()));
        operation.apply(boundDoc, propertyName);
        assertInitialValues(boundDoc, initialValues);
      }
      session.commit();
    }
  }

  private static void assertInitialValues(Vertex vertex, Map<String, Object> initialValues) {
    initialValues.forEach(
        (key, value) -> {
          Assert.assertEquals("Property : " + key, vertex.getPropertyOnLoadValue(key), value);
        });
  }
}
