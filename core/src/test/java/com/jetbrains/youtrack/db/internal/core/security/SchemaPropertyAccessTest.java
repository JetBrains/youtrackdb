package com.jetbrains.youtrack.db.internal.core.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyAccess;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class SchemaPropertyAccessTest extends DbTestBase {

  @Test
  public void testNotAccessible() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("name", "one value");
    assertEquals("one value", doc.getProperty("name"));
    assertEquals("one value", doc.field("name"));
    assertTrue(doc.containsField("name"));
    Set<String> toHide = new HashSet<>();
    toHide.add("name");
    doc.propertyAccess = new PropertyAccess(toHide);
    assertNull(doc.getProperty("name"));
    assertNull(doc.field("name"));
    assertNull(doc.getString("name"));
    assertNull(doc.getString("name"));
    assertFalse(doc.containsField("name"));
    assertNull(doc.getPropertyType("name"));
    session.rollback();
  }

  @Test
  public void testNotAccessibleAfterConvert() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("name", "one value");
    var doc1 = (EntityImpl) session.newEntity();
    RecordInternal.unsetDirty(doc1);
    doc1.fromStream(doc.toStream());
    assertEquals("one value", doc1.getProperty("name"));
    assertEquals("one value", doc1.field("name"));
    assertTrue(doc1.containsField("name"));
    assertEquals(PropertyType.STRING, doc1.getPropertyType("name"));

    Set<String> toHide = new HashSet<>();
    toHide.add("name");
    doc1.propertyAccess = new PropertyAccess(toHide);
    assertNull(doc1.getProperty("name"));
    assertNull(doc1.field("name"));
    assertFalse(doc1.containsField("name"));
    assertNull(doc1.getPropertyType("name"));
    session.rollback();
  }

  @Test
  public void testNotAccessiblePropertyListing() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("name", "one value");
    assertArrayEquals(new String[]{"name"}, doc.getPropertyNames().toArray());
    assertArrayEquals(
        new String[]{"one value"},
        doc.getPropertyNames().stream().map(doc::getProperty).toArray());
    assertEquals(new HashSet<String>(List.of("name")), doc.getPropertyNames());
    for (var e : doc) {
      assertEquals("name", e.getKey());
    }

    Set<String> toHide = new HashSet<>();
    toHide.add("name");
    doc.propertyAccess = new PropertyAccess(toHide);
    assertArrayEquals(new String[]{}, doc.fieldNames());
    assertArrayEquals(new String[]{}, doc.fieldValues());
    assertEquals(new HashSet<String>(), doc.getPropertyNames());
    for (var e : doc) {
      assertNotEquals("name", e.getKey());
    }
    session.rollback();
  }

  @Test
  public void testNotAccessiblePropertyListingSer() {
    session.begin();
    var docPre = (EntityImpl) session.newEntity();
    docPre.setProperty("name", "one value");
    assertArrayEquals(new String[]{"name"}, docPre.getPropertyNames().toArray());
    assertArrayEquals(
        new String[]{"one value"},
        docPre.getPropertyNames().stream().map(docPre::getProperty).toArray());
    assertEquals(new HashSet<String>(List.of("name")), docPre.getPropertyNames());
    for (var e : docPre) {
      assertEquals("name", e.getKey());
    }

    Set<String> toHide = new HashSet<>();
    toHide.add("name");
    var doc = (EntityImpl) session.newEntity();
    RecordInternal.unsetDirty(doc);
    doc.fromStream(docPre.toStream());
    doc.propertyAccess = new PropertyAccess(toHide);
    assertArrayEquals(new String[]{}, doc.getPropertyNames().toArray());
    assertArrayEquals(
        new String[]{}, doc.getPropertyNames().stream().map(doc::getProperty).toArray());
    assertEquals(new HashSet<String>(), doc.getPropertyNames());
    for (var e : doc) {
      assertNotEquals("name", e.getKey());
    }
    session.rollback();
  }

  @Test
  public void testJsonSerialization() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "one value");
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    assertTrue(entity.toJSON().contains("name"));

    Set<String> toHide = new HashSet<>();
    toHide.add("name");
    entity.propertyAccess = new PropertyAccess(toHide);
    assertFalse(entity.toJSON().contains("name"));
    entity.delete();
    session.commit();
  }

  @Test
  public void testToMap() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("name", "one value");
    assertTrue(doc.toMap().containsKey("name"));

    Set<String> toHide = new HashSet<>();
    toHide.add("name");
    doc.propertyAccess = new PropertyAccess(toHide);
    assertFalse(doc.toMap().containsKey("name"));
    session.rollback();
  }

  @Test
  public void testStringSerialization() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("name", "one value");
    assertTrue(doc.toString().contains("name"));

    Set<String> toHide = new HashSet<>();
    toHide.add("name");
    doc.propertyAccess = new PropertyAccess(toHide);
    assertFalse(doc.toString().contains("name"));
    session.rollback();
  }
}
