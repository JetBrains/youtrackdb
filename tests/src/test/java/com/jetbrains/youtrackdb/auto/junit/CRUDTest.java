/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of CRUDTest. Original test class: com.jetbrains.youtrackdb.auto.CRUDTest
 * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CRUDTest extends BaseDBTest {

  protected static long startRecordNumber;
  private static Entity rome;

  /**
   * Original method: beforeClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:59
   */
  @BeforeClass
  public static void setUpClass() throws Exception {
    CRUDTest instance = new CRUDTest();
    instance.beforeClass();
    instance.initSchema();
  }

  public void initSchema() {
    createSimpleTestClass();
    createSimpleArrayTestClass();
    createBinaryTestClass();
    createComplexTestClass();
    createPersonClass();
    createEventClass();
    createAgendaClass();
    createNonGenericClass();
    createMediaClass();
    createParentChildClasses();
  }

  /**
   * Original test method: create Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:76
   */
  @Test
  public void test01_Create() {
    startRecordNumber = session.countClass("Account");

    Entity address;

    session.begin();
    var country = session.newEntity("Country");
    country.setProperty("name", "Italy");

    rome = session.newEntity("City");
    rome.setProperty("name", "Rome");
    rome.setProperty("country", country);

    address = session.newEntity("Address");
    address.setProperty("type", "Residence");
    address.setProperty("street", "Piazza Navona, 1");
    address.setProperty("city", rome);

    for (var i = startRecordNumber; i < startRecordNumber + TOT_RECORDS_ACCOUNT; ++i) {
      var account = session.newEntity("Account");
      account.setProperty("id", i);
      account.setProperty("name", "Bill");
      account.setProperty("surname", "Gates");
      account.setProperty("birthDate", new Date());
      account.setProperty("salary", (i + 300.10f));
      account.setProperty("addresses", session.newLinkList(List.of(address)));
    }
    session.commit();
  }

  /**
   * Original test method: testCreate Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:107 Depends on: create
   */
  @Test
  public void test02_TestCreate() {
    Assert.assertEquals(TOT_RECORDS_ACCOUNT, session.countClass("Account") - startRecordNumber);
  }

  /**
   * Original test method: testCreateClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:112 Depends on: testCreate
   */
  @Test
  public void test03_TestCreateClass() {
    var schema = session.getMetadata().getSchema();
    Assert.assertNull(schema.getClass("Dummy"));
    var dummyClass = schema.createClass("Dummy");
    dummyClass.createProperty("name", PropertyType.STRING);

    Assert.assertEquals(0, session.countClass("Dummy"));
    Assert.assertNotNull(schema.getClass("Dummy"));
  }

  /**
   * Original test method: testSimpleTypes Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:123
   */
  @Test
  public void test04_TestSimpleTypes() {
    session.begin();
    var element = session.newEntity("JavaSimpleTestClass");
    Assert.assertEquals("initTest", element.getProperty("text"));

    var date = new Date();
    element.setProperty("text", "test");
    element.setProperty("numberSimple", 12345);
    element.setProperty("doubleSimple", 12.34d);
    element.setProperty("floatSimple", 123.45f);
    element.setProperty("longSimple", 12345678L);
    element.setProperty("byteSimple", (byte) 1);
    element.setProperty("flagSimple", true);
    element.setProperty("dateField", date);

    session.commit();

    var id = element.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    EntityImpl loadedRecord = session.load(id);
    Assert.assertEquals("test", loadedRecord.getProperty("text"));
    Assert.assertEquals(Integer.valueOf(12345), loadedRecord.<Integer>getProperty("numberSimple"));
    Assert.assertEquals(Double.valueOf(12.34d), loadedRecord.<Double>getProperty("doubleSimple"));
    Assert.assertEquals(Float.valueOf(123.45f), loadedRecord.<Float>getProperty("floatSimple"));
    Assert.assertEquals(Long.valueOf(12345678L), loadedRecord.<Long>getProperty("longSimple"));
    Assert.assertEquals(Byte.valueOf((byte) 1), loadedRecord.<Byte>getProperty("byteSimple"));
    Assert.assertEquals(Boolean.TRUE, loadedRecord.<Boolean>getProperty("flagSimple"));
    Assert.assertEquals(date, loadedRecord.getProperty("dateField"));
    session.commit();
  }

  /**
   * Original test method: testSimpleArrayTypes Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:158 Depends on:
   * testSimpleTypes
   */
  @Test
  public void test05_TestSimpleArrayTypes() {
    session.begin();
    var element = session.newInstance("JavaSimpleArrayTestClass");
    var textArray = new String[10];
    var intArray = new int[10];
    var longArray = new long[10];
    var doubleArray = new double[10];
    var floatArray = new float[10];
    var booleanArray = new boolean[10];
    var dateArray = new Date[10];
    var byteArray = new byte[10];
    var cal = Calendar.getInstance();
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.MILLISECOND, 0);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.YEAR, 1900);
    cal.set(Calendar.MONTH, Calendar.JANUARY);
    for (var i = 0; i < 10; i++) {
      textArray[i] = i + "";
      intArray[i] = i;
      longArray[i] = i;
      doubleArray[i] = i;
      floatArray[i] = i;
      byteArray[i] = (byte) i;
      booleanArray[i] = (i % 2 == 0);
      cal.set(Calendar.DAY_OF_MONTH, (i + 1));
      dateArray[i] = cal.getTime();
    }
    final var values = List.of(
        new Pair<>("text", textArray),
        new Pair<>("dateField", dateArray),
        new Pair<>("doubleSimple", doubleArray),
        new Pair<>("flagSimple", booleanArray),
        new Pair<>("floatSimple", floatArray),
        new Pair<>("longSimple", longArray),
        new Pair<>("numberSimple", intArray)
    );

    for (var p : values) {
      try {
        element.setProperty(p.getKey(), p.getValue());
        Assert.fail("Should fail on array values");
      } catch (IllegalArgumentException ex) {
        // ignore
      }
    }

    element.setProperty("bytes", byteArray);

    session.commit();

    var id = element.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    element = session.load(id);

    byte[] loadedByteArray = element.getProperty("bytes");
    Assert.assertEquals(10, loadedByteArray.length);
    for (var i = 0; i < 10; i++) {
      Assert.assertEquals(byteArray[i], loadedByteArray[i]);
    }
    session.commit();
  }

  /**
   * Original test method: testBinaryDataType Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:349
   */
  @Test
  public void test06_TestBinaryDataType() throws IOException {
    session.begin();
    var doc = session.newInstance("JavaBinaryTestClass");

    var bytes = new byte[1024];
    for (var i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i % 128);
    }

    doc.setProperty("binaryData", bytes);
    session.commit();

    var id = doc.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    doc = session.load(id);
    var loadedBytes = doc.<byte[]>getProperty("binaryData");
    Assert.assertNotNull(loadedBytes);
    Assert.assertEquals(1024, loadedBytes.length);
    for (var i = 0; i < loadedBytes.length; i++) {
      Assert.assertEquals((byte) (i % 128), loadedBytes[i]);
    }
    session.commit();
  }

  /**
   * Original test method: collectionsDocumentTypeTestPhaseOne Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:403
   */
  @Test
  public void test07_CollectionsDocumentTypeTestPhaseOne() {
    session.begin();
    var doc = session.newInstance("JavaComplexTestClass");

    var first = session.newEntity("Child");
    first.setProperty("name", "first");

    var second = session.newEntity("Child");
    second.setProperty("name", "second");

    var set = session.newLinkSet();
    set.add(first);
    set.add(second);
    doc.setProperty("set", set);

    var list = session.newLinkList();
    list.add(first);
    list.add(second);
    doc.setProperty("list", list);

    var map = session.newLinkMap();
    map.put("first", first);
    map.put("second", second);
    doc.setProperty("children", map);

    checkCollectionImplementations(doc);
    session.commit();

    var id = doc.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    doc = session.load(id);
    checkCollectionImplementations(doc);
    session.commit();
  }

  /**
   * Original test method: collectionsDocumentTypeTestPhaseTwo Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:444 Depends on:
   * collectionsDocumentTypeTestPhaseOne
   */
  @Test
  public void test08_CollectionsDocumentTypeTestPhaseTwo() {
    session.begin();
    var doc = session.newInstance("JavaComplexTestClass");

    var first = session.newEntity("Child");
    first.setProperty("name", "first");

    var second = session.newEntity("Child");
    second.setProperty("name", "second");

    doc.setProperty("set", session.newLinkSet(Set.of(first, second)));
    doc.setProperty("list", session.newLinkList(List.of(first, second)));
    doc.setProperty("children", session.newLinkMap(Map.of("first", first, "second", second)));

    checkCollectionImplementations(doc);
    session.commit();

    var id = doc.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    doc = session.load(id);
    checkCollectionImplementations(doc);
    session.commit();
  }

  /**
   * Original test method: collectionsDocumentTypeTestPhaseThree Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:484 Depends on:
   * collectionsDocumentTypeTestPhaseTwo
   */
  @Test
  public void test09_CollectionsDocumentTypeTestPhaseThree() {
    session.begin();
    var a = session.newInstance("JavaComplexTestClass");

    for (var i = 0; i < 100; i++) {
      var child1 = session.newEntity("Child");
      var child2 = session.newEntity("Child");
      var child3 = session.newEntity("Child");

      a.setProperty("list", session.newLinkList(List.of(child1)));
      a.setProperty("set", session.newLinkSet(Set.of(child2)));
      a.setProperty("children", session.newLinkMap(Map.of("" + i, child3)));
    }
    session.commit();

    var rid = a.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    var agendas = executeQuery("SELECT FROM " + rid);
    var testLoadedEntity = agendas.getFirst().asEntity();
    checkCollectionImplementationsPhaseThree(testLoadedEntity);
    session.commit();
  }

  private void checkCollectionImplementationsPhaseThree(Entity doc) {
    var collectionObj = doc.getProperty("list");
    var validImplementation =
        collectionObj instanceof EntityEmbeddedListImpl<?> ||
            collectionObj instanceof EntityLinkListImpl;
    Assert.assertTrue("Document list implementation not compatible", validImplementation);

    collectionObj = doc.getProperty("set");
    validImplementation = collectionObj instanceof EntityEmbeddedSetImpl<?> ||
        collectionObj instanceof EntityLinkSetImpl;
    Assert.assertTrue("Document set implementation not compatible", validImplementation);

    collectionObj = doc.getProperty("children");
    validImplementation = collectionObj instanceof EntityLinkMapIml ||
        collectionObj instanceof EntityEmbeddedMapImpl;
    Assert.assertTrue("Document map implementation not compatible", validImplementation);
  }

  /**
   * Original helper method: checkCollectionImplementations Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:522
   */
  private void checkCollectionImplementations(Entity doc) {
    Assert.assertTrue(doc.getProperty("set") instanceof EntityLinkSetImpl);
    Assert.assertTrue(doc.getProperty("list") instanceof EntityLinkListImpl);
    Assert.assertTrue(doc.getProperty("children") instanceof EntityLinkMapIml);

    var setVal = doc.<Set<Identifiable>>getProperty("set");
    var listVal = doc.<List<Identifiable>>getProperty("list");
    var mapVal = doc.<Map<String, Identifiable>>getProperty("children");

    Assert.assertEquals(2, setVal.size());
    Assert.assertEquals(2, listVal.size());
    Assert.assertEquals(2, mapVal.size());

    for (var identifiable : setVal) {
      var transaction = session.getActiveTransaction();
      Assert.assertTrue(
          transaction.loadEntity(identifiable).<String>getProperty("name").equals("first")
              || transaction.loadEntity(identifiable).<String>getProperty("name").equals("second"));
    }

    for (var identifiable : listVal) {
      var transaction = session.getActiveTransaction();
      Assert.assertTrue(
          transaction.loadEntity(identifiable).<String>getProperty("name").equals("first")
              || transaction.loadEntity(identifiable).<String>getProperty("name").equals("second"));
    }

    for (var entry : mapVal.entrySet()) {
      var transaction = session.getActiveTransaction();
      Assert.assertEquals(entry.getKey(), transaction.loadEntity(entry.getValue())
          .<String>getProperty("name"));
    }
  }

  /**
   * Original test method: testDateInTransaction Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:553
   */
  @Test
  public void test10_TestDateInTransaction() {
    session.begin();
    var event = session.newInstance("Event");
    event.setProperty("name", "Test Event");
    event.setProperty("date", new Date());
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    event = activeTx.load(event);
    Assert.assertNotNull(event.getProperty("date"));
    session.commit();
  }

  /**
   * Original test method: readAndBrowseDescendingAndCheckHoleUtilization Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:568 Depends on: testCreate
   */
  @Test
  public void test11_ReadAndBrowseDescendingAndCheckHoleUtilization() {
    session.begin();
    Set<Integer> ids = new HashSet<>();

    var iterator = session.browseClass("Account");
    while (iterator.hasNext()) {
      var r = iterator.next();
      var id = r.<Integer>getProperty("id");
      Assert.assertFalse(ids.contains(id));
      ids.add(id);
    }

    session.commit();
  }

  /**
   * Original test method: mapEnumAndInternalObjects Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:617 Depends on:
   * readAndBrowseDescendingAndCheckHoleUtilization
   */
  @Test
  public void test12_MapEnumAndInternalObjects() {
    session.begin();
    var doc = session.newInstance();
    doc.setProperty("enumValue", PropertyType.BINARY);
    session.commit();
  }

  /**
   * Original test method: mapObjectsLinkTest Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:626 Depends on:
   * mapEnumAndInternalObjects
   */
  @Test
  public void test13_MapObjectsLinkTest() {
    session.begin();
    var doc = session.newInstance("JavaComplexTestClass");
    var child1 = session.newEntity("Child");
    child1.setProperty("name", "child1");
    var child2 = session.newEntity("Child");
    child2.setProperty("name", "child2");

    var children = session.newLinkMap();
    children.put("first", child1);
    children.put("second", child2);
    doc.setProperty("children", children);
    session.commit();

    var id = doc.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    doc = session.load(id);
    var loadedChildren = doc.<Map<String, Identifiable>>getProperty("children");
    Assert.assertEquals(2, loadedChildren.size());
    session.commit();
  }

  /**
   * Original test method: listObjectsLinkTest Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:707 Depends on:
   * mapObjectsLinkTest
   */
  @Test
  public void test14_ListObjectsLinkTest() {
    session.begin();
    var doc = session.newInstance("JavaComplexTestClass");
    var child1 = session.newEntity("Child");
    child1.setProperty("name", "child1");
    var child2 = session.newEntity("Child");
    child2.setProperty("name", "child2");

    var list = session.newLinkList();
    list.add(child1);
    list.add(child2);
    doc.setProperty("list", list);
    session.commit();

    var id = doc.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    doc = session.load(id);
    var loadedList = doc.<List<Identifiable>>getProperty("list");
    Assert.assertEquals(2, loadedList.size());
    session.commit();
  }

  /**
   * Original test method: listObjectsIterationTest Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:758 Depends on:
   * listObjectsLinkTest
   */
  @Test
  public void test15_ListObjectsIterationTest() {
    session.begin();
    var doc = session.newInstance("JavaComplexTestClass");
    var list = session.newLinkList();
    for (var i = 0; i < 10; i++) {
      var child = session.newEntity("Child");
      child.setProperty("name", "child" + i);
      list.add(child);
    }
    doc.setProperty("list", list);
    session.commit();

    var id = doc.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    doc = session.load(id);
    var loadedList = doc.<List<Identifiable>>getProperty("list");
    Assert.assertEquals(10, loadedList.size());

    var count = 0;
    for (var item : loadedList) {
      var transaction = session.getActiveTransaction();
      Assert.assertEquals("child" + count,
          transaction.loadEntity(item).<String>getProperty("name"));
      count++;
    }
    session.commit();
  }

  /**
   * Original test method: testObjectDelete Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2390
   */
  @Test
  public void test16_TestObjectDelete() {
    session.begin();
    var media = session.newEntity("Media");
    var testRecord = session.newBlob("This is a test".getBytes());

    media.setProperty("content", testRecord);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    media = activeTx1.load(media);
    Assert.assertEquals("This is a test", new String(media.getBlob("content").toStream()));

    // try to delete
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(media));
    session.commit();
  }

  /**
   * Original test method: update Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2411 Depends on:
   * mapEnumAndInternalObjects
   */
  @Test
  public void test17_Update() {
    var i = new int[]{0};

    session.executeInTxBatches(session.browseClass("Account"),
        (session, a) -> {
          if (i[0] % 2 == 0) {
            var addresses = a.<List<Identifiable>>getProperty("addresses");
            var newAddress = this.session.newEntity("Address");

            newAddress.setProperty("street", "Plaza central");
            newAddress.setProperty("type", "work");

            var city = this.session.newEntity("City");
            city.setProperty("name", "Madrid");

            var country = this.session.newEntity("Country");
            country.setProperty("name", "Spain");

            city.setProperty("country", country);
            newAddress.setProperty("city", city);

            var newAddresses = this.session.newLinkList();
            newAddresses.addFirst(newAddress);
            a.setProperty("addresses", newAddresses);
          }

          a.setProperty("salary", (i[0] + 500.10f));

          i[0]++;
        });
  }

  /**
   * Original test method: testUpdate Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2444 Depends on: update
   */
  @Test
  public void test18_TestUpdate() {
    var i = 0;
    session.begin();
    Entity a;
    for (var iterator = session.query("select from Account"); iterator.hasNext(); ) {
      a = iterator.next().asEntityOrNull();

      if (i % 2 == 0) {
        Identifiable identifiable3 = a.<List<Identifiable>>getProperty("addresses")
            .getFirst();
        var transaction3 = session.getActiveTransaction();
        Identifiable identifiable1 = transaction3.<Entity>load(identifiable3)
            .getProperty("city");
        var transaction1 = session.getActiveTransaction();
        Identifiable identifiable2 = transaction1.<Entity>load(identifiable1);
        var transaction2 = session.getActiveTransaction();
        Identifiable identifiable = transaction2.<Entity>load(identifiable2)
            .getProperty("country");
        var transaction = session.getActiveTransaction();
        Assert.assertEquals(
            "Spain",
            transaction.<Entity>load(identifiable)
                .getProperty("name"));
      } else {
        Identifiable identifiable = a.<List<Identifiable>>getProperty("addresses")
            .getFirst();
        var transaction = session.getActiveTransaction();
        Identifiable identifiable3 = transaction.<Entity>load(identifiable)
            .getProperty("city");
        var transaction3 = session.getActiveTransaction();
        Identifiable identifiable2 = transaction3.<Entity>load(identifiable3);
        var transaction2 = session.getActiveTransaction();
        Identifiable identifiable1 = transaction2.<Entity>load(identifiable2)
            .getProperty("country");
        var transaction1 = session.getActiveTransaction();
        Assert.assertEquals(
            "Italy",
            transaction1.<Entity>load(identifiable1)
                .getProperty("name"));
      }

      Assert.assertEquals(Float.valueOf(i + 500.1f), a.<Float>getProperty("salary"));

      i++;
    }
    session.commit();
  }

  /**
   * Original test method: checkLazyLoadingOff Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2493 Depends on: testUpdate
   */
  @Test
  public void test19_CheckLazyLoadingOff() {
    var profiles = session.countClass("Profile");

    session.begin();
    var neo = session.newEntity("Profile");
    neo.setProperty("nick", "Neo");
    neo.setProperty("value", 1);

    var address = session.newEntity("Address");
    address.setProperty("street", "Rio de Castilla");
    address.setProperty("type", "residence");

    var city = session.newEntity("City");
    city.setProperty("name", "Madrid");

    var country = session.newEntity("Country");
    country.setProperty("name", "Spain");

    city.setProperty("country", country);
    address.setProperty("city", city);

    neo.setProperty("location", address);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    neo = activeTx.load(neo);
    Assert.assertNotNull(neo.getEntity("location"));
    Assert.assertNotNull(neo.getEntity("location").getEntity("city"));
    Assert.assertNotNull(neo.getEntity("location").getEntity("city").getEntity("country"));
    session.commit();
  }

  /**
   * Original test method: queryPerFloat Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2557 Depends on:
   * checkLazyLoadingOff
   */
  @Test
  public void test20_QueryPerFloat() {
    session.begin();
    var result = session.query("select * from Account where salary = 500.10")
        .stream().collect(Collectors.toList());
    Assert.assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Original test method: queryCross3Levels Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2572 Depends on: queryPerFloat
   */
  @Test
  public void test21_QueryCross3Levels() {
    session.begin();
    var result = session.query("select from Account")
        .stream().collect(Collectors.toList());
    Assert.assertFalse(result.isEmpty());
    for (var r : result) {
      var addresses = r.<List<Identifiable>>getProperty("addresses");
      if (addresses != null && !addresses.isEmpty()) {
        var address = session.getActiveTransaction().<Entity>load(addresses.getFirst());
        if (address.getProperty("city") != null) {
          var city = address.getEntity("city");
          if (city.getProperty("country") != null) {
            Assert.assertNotNull(city.getEntity("country").getProperty("name"));
          }
        }
      }
    }
    session.commit();
  }

  /**
   * Original test method: deleteFirst Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2602 Depends on:
   * queryCross3Levels
   */
  @Test
  public void test22_DeleteFirst() {
    session.begin();
    var result = session.query("select from Account limit 1")
        .stream().findFirst();
    Assert.assertTrue(result.isPresent());
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(result.get().getIdentity()));
    startRecordNumber--;
    session.commit();
  }

  /**
   * Original test method: commandWithPositionalParameters Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2616 Depends on: deleteFirst
   */
  @Test
  public void test23_CommandWithPositionalParameters() {
    session.begin();
    var result = session.query("select from Account where name = ?", "Bill")
        .stream().collect(Collectors.toList());
    Assert.assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Original test method: queryWithPositionalParameters Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2626 Depends on:
   * commandWithPositionalParameters
   */
  @Test
  public void test24_QueryWithPositionalParameters() {
    session.begin();
    var result = session.query("select from Account where name = ?", "Bill")
        .stream().collect(Collectors.toList());
    Assert.assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Original test method: queryWithRidAsParameters Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2636 Depends on:
   * queryWithPositionalParameters
   */
  @Test
  public void test25_QueryWithRidAsParameters() {
    session.begin();
    var first = session.query("select from Account limit 1")
        .stream().findFirst();
    Assert.assertTrue(first.isPresent());
    var result = session.query("select from Account where @rid = ?", first.get().getIdentity())
        .stream().collect(Collectors.toList());
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original test method: queryWithRidStringAsParameters Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2648 Depends on:
   * queryWithRidAsParameters
   */
  @Test
  public void test26_QueryWithRidStringAsParameters() {
    session.begin();
    var first = session.query("select from Account limit 1")
        .stream().findFirst();
    Assert.assertTrue(first.isPresent());
    var result = session.query("select from Account where @rid = ?",
            first.get().getIdentity().toString())
        .stream().collect(Collectors.toList());
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original test method: commandWithNamedParameters Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2660 Depends on:
   * queryWithRidStringAsParameters
   */
  @Test
  public void test27_CommandWithNamedParameters() {
    session.begin();
    var params = new HashMap<String, Object>();
    params.put("name", "Bill");
    var result = session.query("select from Account where name = :name", params)
        .stream().collect(Collectors.toList());
    Assert.assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Original test method: commandWithWrongNamedParameters Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2675 Depends on:
   * commandWithNamedParameters
   */
  @Test
  public void test28_CommandWithWrongNamedParameters() {
    try {
      var params = new HashMap<String, Object>();
      params.put("name", "Barack");
      params.put("surname", "Obama");

      executeQuery("select from Profile where name = :name and surname = :surname%", params);
      Assert.fail("Expected CommandSQLParsingException");
    } catch (CommandSQLParsingException e) {
      // expected
    }
  }

  /**
   * Original test method: queryConcatAttrib Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2689 Depends on:
   * commandWithWrongNamedParameters
   */
  @Test
  public void test29_QueryConcatAttrib() {
    session.begin();
    var result = session.query(
            "select from Account where name.append(' ').append(surname) like 'Bill %'")
        .stream().collect(Collectors.toList());
    Assert.assertFalse(result.isEmpty());
    session.commit();
  }

  /**
   * Original test method: queryPreparedTwice Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2698 Depends on:
   * queryConcatAttrib
   */
  @Test
  public void test30_QueryPreparedTwice() {
    session.begin();
    var result1 = session.query("select from Account where name = ?", "Bill")
        .stream().collect(Collectors.toList());
    Assert.assertFalse(result1.isEmpty());

    var result2 = session.query("select from Account where name = ?", "Bill")
        .stream().collect(Collectors.toList());
    Assert.assertFalse(result2.isEmpty());
    Assert.assertEquals(result1.size(), result2.size());
    session.commit();
  }

  /**
   * Original test method: testEmbeddedDeletion Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2722
   */
  @Test
  public void test31_TestEmbeddedDeletion() {
    session.begin();
    var parent = session.newInstance("Parent");
    parent.setProperty("name", "Big Parent");

    var embedded = session.newEmbeddedEntity("EmbeddedChild");
    embedded.setProperty("name", "Little Child");

    parent.setProperty("embeddedChild", embedded);

    var presult = executeQuery("select from Parent");
    var cresult = executeQuery("select from EmbeddedChild");
    Assert.assertEquals(1, presult.size());
    Assert.assertEquals(0, cresult.size());

    var child = session.newEmbeddedEntity("EmbeddedChild");
    child.setProperty("name", "Little Child");
    parent.setProperty("child", child);

    session.commit();

    session.begin();
    presult = executeQuery("select from Parent");
    cresult = executeQuery("select from EmbeddedChild");
    Assert.assertEquals(1, presult.size());
    Assert.assertEquals(0, cresult.size());

    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(parent));
    session.commit();

    session.begin();
    presult = executeQuery("select * from Parent");
    cresult = executeQuery("select * from EmbeddedChild");

    Assert.assertEquals(0, presult.size());
    Assert.assertEquals(0, cresult.size());
    session.commit();
  }

  /**
   * Original test method: testEmbeddedBinary Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2766 Note: Original test was
   * disabled with @Test(enabled = false)
   */
  @Test
  @org.junit.Ignore("Original test was disabled in TestNG")
  public void test32_TestEmbeddedBinary() {
    session.begin();
    var a = session.newEntity("Account");
    a.setProperty("name", "Chris");
    a.setProperty("surname", "Martin");
    a.setProperty("id", 0);
    a.setProperty("thumbnail", new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity aa = session.load(a.getIdentity());
    Assert.assertNotNull(a.getProperty("thumbnail"));
    Assert.assertNotNull(aa.getProperty("thumbnail"));
    byte[] b = aa.getProperty("thumbnail");
    for (var i = 0; i < 10; ++i) {
      Assert.assertEquals(i, b[i]);
    }
    session.commit();
  }

  /**
   * Original test method: queryById Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2789 Depends on:
   * testEmbeddedBinary
   */
  @Test
  public void test33_QueryById() {
    session.begin();
    var first = session.query("select from Account limit 1")
        .stream().findFirst();
    Assert.assertTrue(first.isPresent());
    var result = session.query("select from " + first.get().getIdentity())
        .stream().collect(Collectors.toList());
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original test method: queryByIdNewApi Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2800 Depends on: queryById
   */
  @Test
  public void test34_QueryByIdNewApi() {
    session.begin();
    var first = session.query("select from Account limit 1")
        .stream().findFirst();
    Assert.assertTrue(first.isPresent());
    var id = first.get().getIdentity();

    var result = session.query("select from ?", id)
        .stream().collect(Collectors.toList());
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original test method: testSaveMultiCircular Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDTest.java:2817 Depends on:
   * queryByIdNewApi
   */
  @Test
  public void test35_TestSaveMultiCircular() {
    session = createSessionInstance();

    try {
      session.begin();
      var bObama = session.newInstance("Profile");
      bObama.setProperty("nick", "ThePresident0");
      bObama.setProperty("name", "Barack");
      bObama.setProperty("surname", "Obama");

      var address = session.newInstance("Address");
      address.setProperty("type", "Residence");
      address.setProperty("street", "Main Street");

      var city = session.newInstance("City");
      city.setProperty("name", "Washington");

      var country = session.newInstance("Country");
      country.setProperty("name", "USA");

      city.setProperty("country", country);
      address.setProperty("city", city);

      bObama.setProperty("location", address);

      var presidentSon1 = session.newInstance("Profile");
      presidentSon1.setProperty("nick", "PresidentSon10");
      presidentSon1.setProperty("name", "Malia Ann");
      presidentSon1.setProperty("surname", "Obama");
      presidentSon1.setProperty("invitedBy", bObama);

      var presidentSon2 = session.newInstance("Profile");
      presidentSon2.setProperty("nick", "PresidentSon20");
      presidentSon2.setProperty("name", "Natasha");
      presidentSon2.setProperty("surname", "Obama");
      presidentSon2.setProperty("invitedBy", bObama);

      var followers = session.newLinkList();
      followers.add(presidentSon1);
      followers.add(presidentSon2);

      bObama.setProperty("followers", followers);

      session.commit();
    } finally {
      session.close();
    }
  }

  // Helper methods

  private void createSimpleArrayTestClass() {
    if (session.getSchema().existsClass("JavaSimpleArrayTestClass")) {
      session.getSchema().dropClass("JavaSimpleArrayTestClass");
    }

    var cls = session.createClass("JavaSimpleArrayTestClass");
    cls.createProperty("text", PropertyType.EMBEDDEDLIST);
    cls.createProperty("numberSimple", PropertyType.EMBEDDEDLIST);
    cls.createProperty("longSimple", PropertyType.EMBEDDEDLIST);
    cls.createProperty("doubleSimple", PropertyType.EMBEDDEDLIST);
    cls.createProperty("floatSimple", PropertyType.EMBEDDEDLIST);
    cls.createProperty("byteSimple", PropertyType.EMBEDDEDLIST);
    cls.createProperty("flagSimple", PropertyType.EMBEDDEDLIST);
    cls.createProperty("dateField", PropertyType.EMBEDDEDLIST);
  }

  private void createBinaryTestClass() {
    if (session.getSchema().existsClass("JavaBinaryTestClass")) {
      session.getSchema().dropClass("JavaBinaryTestClass");
    }

    var cls = session.createClass("JavaBinaryTestClass");
    cls.createProperty("binaryData", PropertyType.BINARY);
  }

  private void createPersonClass() {
    if (session.getClass("PersonTest") == null) {
      var cls = session.createClass("PersonTest");
      cls.createProperty("firstname", PropertyType.STRING);
      cls.createProperty("friends", PropertyType.LINKSET);
    }
  }

  private void createEventClass() {
    if (session.getClass("Event") == null) {
      var cls = session.createClass("Event");
      cls.createProperty("name", PropertyType.STRING);
      cls.createProperty("date", PropertyType.DATE);
    }
  }

  private void createAgendaClass() {
    if (session.getClass("Agenda") == null) {
      var cls = session.createClass("Agenda");
      cls.createProperty("events", PropertyType.LINKLIST);
    }
  }

  private void createNonGenericClass() {
    if (session.getClass("JavaNoGenericCollectionsTestClass") == null) {
      var cls = session.createClass("JavaNoGenericCollectionsTestClass");
      cls.createProperty("list", PropertyType.LINKLIST);
      cls.createProperty("set", PropertyType.LINKSET);
      cls.createProperty("map", PropertyType.LINKMAP);
    }
  }

  private void createMediaClass() {
    if (session.getClass("Media") == null) {
      var cls = session.createClass("Media");
      cls.createProperty("content", PropertyType.LINK);
      cls.createProperty("name", PropertyType.STRING);
    }
  }

  private void createParentChildClasses() {
    if (session.getSchema().existsClass("EmbeddedChild")) {
      session.getSchema().dropClass("EmbeddedChild");
    }
    if (session.getSchema().existsClass("Parent")) {
      session.getSchema().dropClass("Parent");
    }

    var childCls = session.createAbstractClass("EmbeddedChild");
    childCls.createProperty("name", PropertyType.STRING);

    var parentCls = session.createClass("Parent");
    parentCls.createProperty("name", PropertyType.STRING);
    parentCls.createProperty("child", PropertyType.EMBEDDED,
        session.getClass("EmbeddedChild"));
    parentCls.createProperty("embeddedChild", PropertyType.EMBEDDED,
        session.getClass("EmbeddedChild"));
  }
}
