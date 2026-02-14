package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * @since 22.03.12
 */
@Test
public class LinkMapIndexTest extends BaseDBTest {
  @BeforeClass
  public void setupSchema() {
    final var linkMapIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkMapIndexTestClass");
    linkMapIndexTestClass.createProperty("linkMap", PropertyType.LINKMAP);

    linkMapIndexTestClass.createIndex("mapIndexTestKey", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "linkMap");
    linkMapIndexTestClass.createIndex(
        "mapIndexTestValue", SchemaClass.INDEX_TYPE.NOTUNIQUE, "linkMap by value");
  }

  @AfterClass
  public void destroySchema() {
    session = createSessionInstance();
    session.getMetadata().getSchema().dropClass("LinkMapIndexTestClass");
    session.close();
  }

  @Override
  @AfterMethod
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from LinkMapIndexTestClass").close();
    session.commit();

    super.afterMethod();
  }

  public void testIndexMap() {

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
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();

        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");

    Assert.assertEquals(valueIndexMap.size(session), 2);
    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapInTx() {

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
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapUpdateOne() {

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
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapUpdateOneTx() {

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
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapUpdateOneTxRollback() {

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
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key1")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docTwo.getIdentity())
            && !value.equals(docOne.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapAddItem() {

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
            "UPDATE " + document.getIdentity() + " set linkMap['key3'] = " + docThree.getIdentity())
        .close();
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 3);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapAddItemTx() {

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
      loadedDocument.<Map<String, RID>>getProperty("linkMap").put("key3", docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 3);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();

        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 3);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapAddItemTxRollback() {

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
    loadedDocument.<Map<String, RID>>getProperty("linkMap").put("key3", docThree.getIdentity());

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapUpdateItem() {

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
            "UPDATE " + document.getIdentity() + " set linkMap['key2'] = " + docThree.getIdentity())
        .close();
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndexMap.size(session), 2);
    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapUpdateItemInTx() {

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
      loadedDocument.<Map<String, RID>>getProperty("linkMap").put("key2", docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Assert.assertEquals(keyIndexMap.size(session), 2);
    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapUpdateItemInTxRollback() {

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
    loadedDocument.<Map<String, RID>>getProperty("linkMap").put("key2", docThree.getIdentity());

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapRemoveItem() {

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
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapRemoveItemInTx() {

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
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapRemoveItemInTxRollback() {

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
    Assert.assertEquals(keyIndexMap.size(session), 3);

    final Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 3);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapRemove() {

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
    Assert.assertEquals(keyIndexMap.size(session), 0);

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 0);
    session.rollback();
  }

  public void testIndexMapRemoveInTx() {

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
    Assert.assertEquals(keyIndexMap.size(session), 0);

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 0);
    session.rollback();
  }

  public void testIndexMapRemoveInTxRollback() {

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
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  public void testIndexMapSQL() {

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
    Assert.assertNotNull(resultByKey);
    Assert.assertEquals(resultByKey.size(), 1);

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertEquals(map, document.getProperty("linkMap"));

    var resultByValue =
        executeQuery(
            "select * from LinkMapIndexTestClass where linkMap  containsvalue ?",
            docOne.getIdentity());
    Assert.assertNotNull(resultByValue);
    Assert.assertEquals(resultByValue.size(), 1);

    Assert.assertEquals(map, document.getProperty("linkMap"));
    session.commit();
  }
}