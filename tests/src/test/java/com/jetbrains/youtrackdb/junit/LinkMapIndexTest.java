// Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for link map index operations.
 *
 * @since 22.03.12
 */
public class LinkMapIndexTest extends BaseDBJUnit5Test {

  @Override
  @BeforeAll
  void beforeAll() throws Exception {
    super.beforeAll();

    // setupSchema — create the test class and its indexes
    final var linkMapIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkMapIndexTestClass");
    linkMapIndexTestClass.createProperty("linkMap", PropertyType.LINKMAP);

    linkMapIndexTestClass.createIndex("mapIndexTestKey", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "linkMap");
    linkMapIndexTestClass.createIndex(
        "mapIndexTestValue", SchemaClass.INDEX_TYPE.NOTUNIQUE, "linkMap by value");
  }

  @Override
  @AfterAll
  void afterAll() throws Exception {
    // destroySchema — drop the test class before closing
    session = createSessionInstance();
    session.getMetadata().getSchema().dropClass("LinkMapIndexTestClass");
    session.close();

    super.afterAll();
  }

  @Override
  @AfterEach
  void afterEach() throws Exception {
    session.begin();
    session.execute("delete from LinkMapIndexTestClass").close();
    session.commit();

    super.afterEach();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMap
  @Test
  void testIndexMap() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();

        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");

    assertEquals(2, valueIndexMap.size(session));
    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapInTx
  @Test
  void testIndexMapInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    session.commit();

    try {
      session.begin();
      var map = session.newLinkMap();

      map.put("key1", docOne.getIdentity());
      map.put("key2", docTwo.getIdentity());

      final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
      document.setProperty("linkMap", map);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapUpdateOne
  @Test
  void testIndexMapUpdateOne() {

    session.begin();

    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var mapOne = session.newLinkMap();

    mapOne.put("key1", docOne.getIdentity());
    mapOne.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", mapOne);

    final var mapTwo = session.newLinkMap();
    mapTwo.put("key2", docOne.getIdentity());
    mapTwo.put("key3", docThree.getIdentity());

    document.setProperty("linkMap", mapTwo);

    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapUpdateOneTx
  @Test
  void testIndexMapUpdateOneTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    try {
      var mapTwo = session.newLinkMap();

      mapTwo.put("key3", docOne.getIdentity());
      mapTwo.put("key2", docTwo.getIdentity());

      final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
      document.setProperty("linkMap", mapTwo);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapUpdateOneTxRollback
  @Test
  void testIndexMapUpdateOneTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var mapOne = session.newLinkMap();

    mapOne.put("key1", docOne.getIdentity());
    mapOne.put("key2", docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", mapOne);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    final var mapTwo = session.newLinkMap();

    mapTwo.put("key3", docTwo.getIdentity());
    mapTwo.put("key2", docThree.getIdentity());

    document.setProperty("linkMap", mapTwo);

    session.rollback();

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key1")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docTwo.getIdentity())
            && !value.equals(docOne.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapAddItem
  @Test
  void testIndexMapAddItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    final var docTwo = ((EntityImpl) session.newEntity());
    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE " + document.getIdentity() + " set linkMap['key3'] = "
                + docThree.getIdentity())
        .close();
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(3, keyIndexMap.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(3, valueIndexMap.size(session));

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapAddItemTx
  @Test
  void testIndexMapAddItemTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>getProperty("linkMap")
          .put("key3", docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(3, keyIndexMap.size(session));

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();

        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(3, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapAddItemTxRollback
  @Test
  void testIndexMapAddItemTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>getProperty("linkMap")
        .put("key3", docThree.getIdentity());

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docOne.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapUpdateItem
  @Test
  void testIndexMapUpdateItem() {

    session.begin();
    final var docOne = session.newEntity();

    final var docTwo = session.newEntity();

    final var docThree = session.newEntity();

    var map = session.newLinkMap();

    map.put("key1", docOne);
    map.put("key2", docTwo);

    final var document = session.newEntity("LinkMapIndexTestClass");
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE " + document.getIdentity() + " set linkMap['key2'] = "
                + docThree.getIdentity())
        .close();
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");

    assertEquals(2, keyIndexMap.size(session));
    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapUpdateItemInTx
  @Test
  void testIndexMapUpdateItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>getProperty("linkMap")
          .put("key2", docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    assertEquals(2, keyIndexMap.size(session));
    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapUpdateItemInTxRollback
  @Test
  void testIndexMapUpdateItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>getProperty("linkMap")
        .put("key2", docThree.getIdentity());

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapRemoveItem
  @Test
  void testIndexMapRemoveItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    session.execute("UPDATE " + document.getIdentity() + " remove linkMap = 'key2'").close();
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapRemoveItemInTx
  @Test
  void testIndexMapRemoveItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>getProperty("linkMap").remove("key2");

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapRemoveItemInTxRollback
  @Test
  void testIndexMapRemoveItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>getProperty("linkMap").remove("key2");

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(3, keyIndexMap.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(3, valueIndexMap.size(session));

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapRemove
  @Test
  void testIndexMapRemove() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    session.begin();
    assertEquals(0, keyIndexMap.size(session));

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(0, valueIndexMap.size(session));
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapRemoveInTx
  @Test
  void testIndexMapRemoveInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      activeTx.<EntityImpl>load(document).delete();
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    session.begin();
    assertEquals(0, keyIndexMap.size(session));

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(0, valueIndexMap.size(session));
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapRemoveInTxRollback
  @Test
  void testIndexMapRemoveInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.rollback();

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndexMap.size(session));

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndexMap.size(session));

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkMapIndexTest#testIndexMapSQL
  @Test
  void testIndexMapSQL() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    var resultByKey =
        session.query(
            "select * from LinkMapIndexTestClass where linkMap containskey ?",
            "key1").toList();
    assertNotNull(resultByKey);
    assertEquals(1, resultByKey.size());

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    assertEquals(document.getProperty("linkMap"), map);

    var resultByValue =
        executeQuery(
            "select * from LinkMapIndexTestClass where linkMap  containsvalue ?",
            docOne.getIdentity());
    assertNotNull(resultByValue);
    assertEquals(1, resultByValue.size());

    assertEquals(document.getProperty("linkMap"), map);
    session.commit();
  }
}
