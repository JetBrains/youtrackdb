package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 22.03.12
 */
@SuppressWarnings("deprecation")
@Test
public class LinkMapIndexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public LinkMapIndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

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

  @AfterMethod
  public void afterMethod() throws Exception {
    session.begin();
    session.command("delete from LinkMapIndexTestClass").close();
    session.commit();

    super.afterMethod();
  }

  public void testIndexMap() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    session.commit();

    try {
      session.begin();
      Map<String, RID> map = new HashMap<>();

      map.put("key1", docOne.getIdentity());
      map.put("key2", docTwo.getIdentity());

      final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
      document.field("linkMap", map);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateOne() {
    checkEmbeddedDB();

    session.begin();

    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> mapOne = new HashMap<>();

    mapOne.put("key1", docOne.getIdentity());
    mapOne.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", mapOne);

    final Map<String, RID> mapTwo = new HashMap<>();
    mapTwo.put("key2", docOne.getIdentity());
    mapTwo.put("key3", docThree.getIdentity());

    document.field("linkMap", mapTwo);

    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateOneTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    try {
      final Map<String, RID> mapTwo = new HashMap<>();

      mapTwo.put("key3", docOne.getIdentity());
      mapTwo.put("key2", docTwo.getIdentity());

      final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
      document.field("linkMap", mapTwo);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateOneTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> mapOne = new HashMap<>();

    mapOne.put("key1", docOne.getIdentity());
    mapOne.put("key2", docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", mapOne);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    final Map<String, RID> mapTwo = new HashMap<>();

    mapTwo.put("key3", docTwo.getIdentity());
    mapTwo.put("key2", docThree.getIdentity());

    document.field("linkMap", mapTwo);

    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docTwo.getIdentity())
            && !value.equals(docOne.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapAddItem() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    session.begin();
    session
        .command(
            "UPDATE " + document.getIdentity() + " set linkMap['key3'] = " + docThree.getIdentity())
        .close();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
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
  }

  public void testIndexMapAddItemTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>field("linkMap").put("key3", docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 3);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
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
  }

  public void testIndexMapAddItemTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>field("linkMap").put("key3", docThree.getIdentity());

    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateItem() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    session.begin();
    session
        .command(
            "UPDATE " + document.getIdentity() + " set linkMap['key2'] = " + docThree.getIdentity())
        .close();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndexMap.size(session), 2);
    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateItemInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>field("linkMap").put("key2", docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Assert.assertEquals(keyIndexMap.size(session), 2);
    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateItemInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>field("linkMap").put("key2", docThree.getIdentity());

    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapRemoveItem() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    session.begin();
    session.command("UPDATE " + document.getIdentity() + " remove linkMap = 'key2'").close();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapRemoveItemInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>field("linkMap").remove("key2");

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapRemoveItemInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>field("linkMap").remove("key2");

    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 3);

    final Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
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
  }

  public void testIndexMapRemove() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    session.begin();
    session.bindToSession(document).delete();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 0);

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 0);
  }

  public void testIndexMapRemoveInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    try {
      session.begin();
      session.bindToSession(document).delete();
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 0);

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 0);
  }

  public void testIndexMapRemoveInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    session.begin();
    session.bindToSession(document).delete();
    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
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
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapSQL() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    Map<String, RID> map = new HashMap<>();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.field("linkMap", map);

    session.commit();

    final List<EntityImpl> resultByKey =
        session.query(
            new SQLSynchQuery<EntityImpl>(
                "select * from LinkMapIndexTestClass where linkMap containskey ?"),
            "key1");
    Assert.assertNotNull(resultByKey);
    Assert.assertEquals(resultByKey.size(), 1);

    document = session.bindToSession(document);
    Assert.assertEquals(map, document.field("linkMap"));

    var resultByValue =
        executeQuery(
            "select * from LinkMapIndexTestClass where linkMap  containsvalue ?",
            docOne.getIdentity());
    Assert.assertNotNull(resultByValue);
    Assert.assertEquals(resultByValue.size(), 1);

    Assert.assertEquals(map, document.field("linkMap"));
  }
}
