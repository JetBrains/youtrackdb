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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
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
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class CRUDTest extends BaseDBJUnit5Test {
  protected long startRecordNumber;

  private Entity rome;

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

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

  @Test
  @Order(1)
  void create() {
    session.begin();
    startRecordNumber = session.countClass("Account");
    session.rollback();

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

  @Test
  @Order(2)
  void testCreate() {
    session.begin();
    assertEquals(TOT_RECORDS_ACCOUNT, session.countClass("Account") - startRecordNumber);
    session.rollback();
  }

  @Test
  @Order(3)
  void testCreateClass() {
    var schema = session.getMetadata().getSchema();
    assertNull(schema.getClass("Dummy"));
    var dummyClass = schema.createClass("Dummy");
    dummyClass.createProperty("name", PropertyType.STRING);

    session.begin();
    assertEquals(0, session.countClass("Dummy"));
    session.rollback();
    assertNotNull(schema.getClass("Dummy"));
  }

  @Test
  @Order(4)
  void testSimpleTypes() {
    session.begin();
    var element = session.newEntity("JavaSimpleTestClass");
    assertEquals("initTest", element.getProperty("text"));

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
    assertEquals("test", loadedRecord.getProperty("text"));
    assertEquals(12345, loadedRecord.<Integer>getProperty("numberSimple"));
    assertEquals(12.34d, loadedRecord.<Double>getProperty("doubleSimple"));
    assertEquals(123.45f, loadedRecord.<Float>getProperty("floatSimple"));
    assertEquals(12345678L, loadedRecord.<Long>getProperty("longSimple"));
    assertEquals((byte) 1, loadedRecord.<Byte>getProperty("byteSimple"));
    assertTrue(loadedRecord.<Boolean>getProperty("flagSimple"));
    assertEquals(date, loadedRecord.getProperty("dateField"));
    session.commit();
  }

  @Test
  @Order(5)
  void testSimpleArrayTypes() {
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
        new Pair<>("numberSimple", intArray));

    for (var p : values) {

      try {
        element.setProperty(p.getKey(), p.getValue());
        fail("Should fail on array values");
        //
      } catch (IllegalArgumentException ex) {
        //ignore
      }
    }

    element.setProperty("bytes", byteArray);

    element.setProperty("text", session.newEmbeddedList(textArray));
    element.setProperty("dateField", session.newEmbeddedList(dateArray));
    element.setProperty("doubleSimple", session.newEmbeddedList(doubleArray));
    element.setProperty("flagSimple", session.newEmbeddedList(booleanArray));
    element.setProperty("floatSimple", session.newEmbeddedList(floatArray));
    element.setProperty("longSimple", session.newEmbeddedList(longArray));
    element.setProperty("numberSimple", session.newEmbeddedList(intArray));

    assertNotNull(element.getProperty("text"));
    assertNotNull(element.getProperty("numberSimple"));
    assertNotNull(element.getProperty("longSimple"));
    assertNotNull(element.getProperty("doubleSimple"));
    assertNotNull(element.getProperty("floatSimple"));
    assertNotNull(element.getProperty("flagSimple"));
    assertNotNull(element.getProperty("dateField"));

    session.commit();
    var id = element.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loadedElement = session.load(id);
    assertNotNull(loadedElement.getProperty("text"));
    assertNotNull(loadedElement.getProperty("numberSimple"));
    assertNotNull(loadedElement.getProperty("longSimple"));
    assertNotNull(loadedElement.getProperty("doubleSimple"));
    assertNotNull(loadedElement.getProperty("floatSimple"));
    assertNotNull(loadedElement.getProperty("flagSimple"));
    assertNotNull(loadedElement.getProperty("dateField"));

    assertEquals(10, loadedElement.<List<String>>getProperty("text").size());
    assertEquals(10, loadedElement.<List<Integer>>getProperty("numberSimple").size());
    assertEquals(10, loadedElement.<List<Long>>getProperty("longSimple").size());
    assertEquals(10, loadedElement.<List<Double>>getProperty("doubleSimple").size());
    assertEquals(10, loadedElement.<List<Float>>getProperty("floatSimple").size());
    assertEquals(10, loadedElement.<List<Boolean>>getProperty("flagSimple").size());
    assertEquals(10, loadedElement.<List<Date>>getProperty("dateField").size());

    for (var i = 0; i < 10; i++) {
      assertEquals(i + "", loadedElement.<List<String>>getProperty("text").get(i));
      assertEquals(i, loadedElement.<List<Integer>>getProperty("numberSimple").get(i));
      assertEquals(i, loadedElement.<List<Long>>getProperty("longSimple").get(i));
      assertEquals(i, loadedElement.<List<Double>>getProperty("doubleSimple").get(i));
      assertEquals((float) i, loadedElement.<List<Float>>getProperty("floatSimple").get(i));
      assertEquals(
          (i % 2 == 0), loadedElement.<List<Boolean>>getProperty("flagSimple").get(i));
      cal.set(Calendar.DAY_OF_MONTH, (i + 1));
      assertEquals(cal.getTime(), loadedElement.<List<Date>>getProperty("dateField").get(i));
    }

    for (var i = 0; i < 10; i++) {
      var j = i + 10;
      textArray[i] = j + "";
      intArray[i] = j;
      longArray[i] = j;
      doubleArray[i] = j;
      floatArray[i] = j;
      booleanArray[i] = (j % 2 == 0);
      cal.set(Calendar.DAY_OF_MONTH, (j + 1));
      dateArray[i] = cal.getTime();
    }
    loadedElement.setProperty("text", session.newEmbeddedList(textArray));
    loadedElement.setProperty("dateField", session.newEmbeddedList(dateArray));
    loadedElement.setProperty("doubleSimple", session.newEmbeddedList(doubleArray));
    loadedElement.setProperty("flagSimple", session.newEmbeddedList(booleanArray));
    loadedElement.setProperty("floatSimple", session.newEmbeddedList(floatArray));
    loadedElement.setProperty("longSimple", session.newEmbeddedList(longArray));
    loadedElement.setProperty("numberSimple", session.newEmbeddedList(intArray));

    session.commit();
    session.close();

    session = createSessionInstance();
    session.begin();
    loadedElement = session.load(id);
    assertNotNull(loadedElement.getProperty("text"));
    assertNotNull(loadedElement.getProperty("numberSimple"));
    assertNotNull(loadedElement.getProperty("longSimple"));
    assertNotNull(loadedElement.getProperty("doubleSimple"));
    assertNotNull(loadedElement.getProperty("floatSimple"));
    assertNotNull(loadedElement.getProperty("flagSimple"));
    assertNotNull(loadedElement.getProperty("dateField"));

    assertEquals(10, loadedElement.<List<String>>getProperty("text").size());
    assertEquals(10, loadedElement.<List<Integer>>getProperty("numberSimple").size());
    assertEquals(10, loadedElement.<List<Long>>getProperty("longSimple").size());
    assertEquals(10, loadedElement.<List<Double>>getProperty("doubleSimple").size());
    assertEquals(10, loadedElement.<List<Float>>getProperty("floatSimple").size());
    assertEquals(10, loadedElement.<List<Boolean>>getProperty("flagSimple").size());
    assertEquals(10, loadedElement.<List<Date>>getProperty("dateField").size());

    for (var i = 0; i < 10; i++) {
      var j = i + 10;
      assertEquals(j + "", loadedElement.<List<String>>getProperty("text").get(i));
      assertEquals(j, loadedElement.<List<Integer>>getProperty("numberSimple").get(i));
      assertEquals(j, loadedElement.<List<Long>>getProperty("longSimple").get(i));
      assertEquals(j, loadedElement.<List<Double>>getProperty("doubleSimple").get(i));
      assertEquals((float) j, loadedElement.<List<Float>>getProperty("floatSimple").get(i));
      assertEquals(
          (j % 2 == 0), loadedElement.<List<Boolean>>getProperty("flagSimple").get(i));

      cal.set(Calendar.DAY_OF_MONTH, (j + 1));
      assertEquals(cal.getTime(), loadedElement.<List<Date>>getProperty("dateField").get(i));
    }

    session.commit();
    session.close();

    session = createSessionInstance();

    session.begin();
    loadedElement = session.load(id);

    assertTrue(
        ((Collection<?>) loadedElement.getProperty("text")).iterator().next() instanceof String);
    assertTrue(
        ((Collection<?>) loadedElement.getProperty("numberSimple")).iterator()
            .next() instanceof Integer);
    assertTrue(
        ((Collection<?>) loadedElement.getProperty("longSimple")).iterator()
            .next() instanceof Long);
    assertTrue(
        ((Collection<?>) loadedElement.getProperty("doubleSimple")).iterator()
            .next() instanceof Double);
    assertTrue(
        ((Collection<?>) loadedElement.getProperty("floatSimple")).iterator()
            .next() instanceof Float);
    assertTrue(
        ((Collection<?>) loadedElement.getProperty("flagSimple")).iterator()
            .next() instanceof Boolean);
    assertTrue(
        ((Collection<?>) loadedElement.getProperty("dateField")).iterator().next() instanceof Date);

    session.delete(session.load(id));
    session.commit();
  }

  @Test
  @Order(6)
  void testBinaryDataType() {
    session.begin();
    var element = session.newInstance("JavaBinaryTestClass");
    var bytes = new byte[10];
    for (var i = 0; i < 10; i++) {
      bytes[i] = (byte) i;
    }

    element.setProperty("binaryData", bytes);

    var fieldName = "binaryData";
    assertNotNull(element.getProperty(fieldName));

    session.commit();

    var id = element.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loadedElement = session.load(id);
    assertNotNull(loadedElement.getProperty(fieldName));

    assertEquals(10, loadedElement.<byte[]>getProperty("binaryData").length);
    assertArrayEquals(bytes, loadedElement.getProperty("binaryData"));

    for (var i = 0; i < 10; i++) {
      var j = i + 10;
      bytes[i] = (byte) j;
    }
    loadedElement.setProperty("binaryData", bytes);

    session.commit();
    session.close();

    session = createSessionInstance();
    session.begin();
    loadedElement = session.load(id);
    assertNotNull(loadedElement.getProperty(fieldName));

    assertEquals(10, loadedElement.<byte[]>getProperty("binaryData").length);
    assertArrayEquals(bytes, loadedElement.getProperty("binaryData"));

    session.commit();
    session.close();

    session = createSessionInstance();

    session.begin();
    session.delete(session.load(id));
    session.commit();
  }

  @Test
  @Order(7)
  void testDateInTransaction() {
    session.begin();
    var element = session.newEntity("JavaSimpleTestClass");
    var date = new Date();
    element.setProperty("dateField", date);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    element = activeTx.load(element);
    assertEquals(date, element.<List<Date>>getProperty("dateField"));
    session.commit();
  }

  @Test
  @Order(8)
  void collectionsDocumentTypeTestPhaseOne() {
    session.begin();
    var a = session.newInstance("JavaComplexTestClass");

    for (var i = 0; i < 3; i++) {
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
    var testLoadedEntity = (EntityImpl) agendas.getFirst().asEntityOrNull();

    checkCollectionImplementations(testLoadedEntity);

    session.commit();

    session.freeze(false);
    session.release();

    session.begin();

    testLoadedEntity = session.load(rid);

    checkCollectionImplementations(testLoadedEntity);
    session.commit();
  }

  @Test
  @Order(9)
  void collectionsDocumentTypeTestPhaseTwo() {
    session.begin();
    var a = session.newInstance("JavaComplexTestClass");

    for (var i = 0; i < 10; i++) {
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
    var testLoadedEntity = (EntityImpl) agendas.getFirst().asEntityOrNull();

    checkCollectionImplementations(testLoadedEntity);

    session.commit();

    session.freeze(false);
    session.release();

    session.begin();
    var activeTx = session.getActiveTransaction();
    checkCollectionImplementations(activeTx.load(testLoadedEntity));
    session.commit();
  }

  @Test
  @Order(10)
  void collectionsDocumentTypeTestPhaseThree() {
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
    checkCollectionImplementations(testLoadedEntity);

    session.commit();

    session.freeze(false);
    session.release();

    session.begin();
    var activeTx = session.getActiveTransaction();
    checkCollectionImplementations(activeTx.load(testLoadedEntity));
    session.rollback();
  }

  static void checkCollectionImplementations(Entity doc) {
    var collectionObj = doc.getProperty("list");
    var validImplementation =
        collectionObj instanceof EntityEmbeddedListImpl<?> ||
            collectionObj instanceof EntityLinkListImpl;
    if (!validImplementation) {
      fail(
          "Document list implementation "
              + collectionObj.getClass().getName()
              + " not compatible with current Object Database loading management");
    }
    collectionObj = doc.getProperty("set");
    validImplementation = collectionObj instanceof EntityEmbeddedSetImpl<?> ||
        collectionObj instanceof EntityLinkSetImpl;
    if (!validImplementation) {
      fail(
          "Document set implementation "
              + collectionObj.getClass().getName()
              + " not compatible with current Object Database management");
    }
    collectionObj = doc.getProperty("children");
    validImplementation = collectionObj instanceof EntityLinkMapIml ||
        collectionObj instanceof EntityEmbeddedMapImpl;
    if (!validImplementation) {
      fail(
          "Document map implementation "
              + collectionObj.getClass().getName()
              + " not compatible with current Object Database management");
    }
  }

  @Test
  @Order(11)
  void readAndBrowseDescendingAndCheckHoleUtilization() {
    session.begin();
    var activeTx = session.getActiveTransaction();
    rome = activeTx.load(rome);
    Set<Integer> ids = new HashSet<>(TOT_RECORDS_ACCOUNT);
    for (var i = 0; i < TOT_RECORDS_ACCOUNT; i++) {
      ids.add(i);
    }

    var entityIterator = session.browseClass("Account");
    while (entityIterator.hasNext()) {
      var a = entityIterator.next();
      int id = a.<Integer>getProperty("id");
      assertTrue(ids.remove(id));

      assertEquals(id, a.<Integer>getProperty("id"));
      assertEquals("Bill", a.getProperty("name"));
      assertEquals("Gates", a.getProperty("surname"));
      assertEquals(id + 300.1f, a.<Float>getProperty("salary"));
      assertEquals(1, a.<List<Identifiable>>getProperty("addresses").size());
      Identifiable identifiable2 = a.<List<Identifiable>>getProperty("addresses")
          .getFirst();
      var transaction3 = session.getActiveTransaction();
      assertEquals(
          rome.<String>getProperty("name"),
          transaction3.<Entity>load(identifiable2)
              .getEntity("city")
              .getProperty("name"));
      var transaction = session.getActiveTransaction();
      Identifiable identifiable = transaction.<Entity>load(rome)
          .getProperty("country");
      var transaction1 = session.getActiveTransaction();
      Identifiable identifiable1 = a.<List<Identifiable>>getProperty("addresses")
          .getFirst();
      var transaction2 = session.getActiveTransaction();
      assertEquals(
          transaction1.<Entity>load(identifiable)
              .<String>getProperty("name"),
          transaction2.<Entity>load(identifiable1)
              .getEntity("city")
              .getEntity("country")
              .getProperty("name"));
    }

    assertTrue(ids.isEmpty());
    session.commit();
  }

  @Test
  @Order(12)
  void mapEnumAndInternalObjects() {
    session.executeInTxBatches(session.browseClass("OUser"),
        (session, document) -> {

        });

  }

  @Test
  @Order(13)
  void mapObjectsLinkTest() {
    session.begin();
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Silvester");

    var c = session.newInstance("Child");
    c.setProperty("name", "John");

    var c1 = session.newInstance("Child");
    c1.setProperty("name", "Jack");

    var c2 = session.newInstance("Child");
    c2.setProperty("name", "Bob");

    var c3 = session.newInstance("Child");
    c3.setProperty("name", "Sam");

    var c4 = session.newInstance("Child");
    c4.setProperty("name", "Dean");

    var list = session.newLinkList();
    list.add(c1);
    list.add(c2);
    list.add(c3);
    list.add(c4);

    p.setProperty("list", list);

    var children = session.newLinkMap();
    children.put("first", c);
    p.setProperty("children", children);

    session.commit();

    session.begin();
    var cresult = executeQuery("select * from Child");
    assertFalse(cresult.isEmpty());

    var rid = p.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    var loaded = session.<Entity>load(rid);

    list = loaded.getProperty("list");
    assertEquals(4, list.size());
    var transaction7 = session.getActiveTransaction();
    assertEquals(
        "Child",
        Objects.requireNonNull(transaction7.<Entity>load(list.get(0)).getSchemaClass()).getName());
    var transaction6 = session.getActiveTransaction();
    assertEquals(
        "Child",
        Objects.requireNonNull(transaction6.<Entity>load(list.get(1)).getSchemaClass()).getName());
    var transaction5 = session.getActiveTransaction();
    assertEquals(
        "Child",
        Objects.requireNonNull(transaction5.<Entity>load(list.get(2)).getSchemaClass()).getName());
    var transaction4 = session.getActiveTransaction();
    assertEquals(
        "Child",
        Objects.requireNonNull(transaction4.<Entity>load(list.get(3)).getSchemaClass()).getName());
    var transaction3 = session.getActiveTransaction();
    assertEquals("Jack", transaction3.<Entity>load(list.get(0)).getProperty("name"));
    var transaction2 = session.getActiveTransaction();
    assertEquals("Bob", transaction2.<Entity>load(list.get(1)).getProperty("name"));
    var transaction1 = session.getActiveTransaction();
    assertEquals("Sam", transaction1.<Entity>load(list.get(2)).getProperty("name"));
    var transaction = session.getActiveTransaction();
    assertEquals("Dean", transaction.<Entity>load(list.get(3)).getProperty("name"));
    session.commit();
  }

  @Test
  @Order(14)
  void listObjectsLinkTest() {
    session.begin();
    var hanSolo = session.newInstance("PersonTest");
    hanSolo.setProperty("firstName", "Han");
    session.commit();

    session.begin();
    var obiWan = session.newInstance("PersonTest");
    obiWan.setProperty("firstName", "Obi-Wan");

    var luke = session.newInstance("PersonTest");
    luke.setProperty("firstName", "Luke");
    session.commit();

    // ============================== step 1
    // add new information to luke
    session.begin();
    var activeTx5 = session.getActiveTransaction();
    luke = activeTx5.load(luke);
    var friends = session.newLinkSet();
    var activeTx4 = session.getActiveTransaction();
    friends.add(activeTx4.<EntityImpl>load(hanSolo));

    luke.setProperty("friends", friends);
    session.commit();

    session.begin();
    var activeTx3 = session.getActiveTransaction();
    luke = activeTx3.load(luke);
    assertEquals(1, luke.<Set<Identifiable>>getProperty("friends").size());
    friends = session.newLinkSet();
    var activeTx2 = session.getActiveTransaction();
    friends.add(activeTx2.<EntityImpl>load(obiWan));
    luke.setProperty("friends", friends);

    var activeTx1 = session.getActiveTransaction();
    activeTx1.load(luke);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    luke = activeTx.load(luke);
    assertEquals(1, luke.<Set<Identifiable>>getProperty("friends").size());
    session.commit();
    // ============================== end 2
  }

  @Test
  @Order(15)
  void listObjectsIterationTest() {
    session.begin();
    var a = session.newInstance("Agenda");

    for (var i = 0; i < 10; i++) {
      a.setProperty("events", session.newLinkList(List.of(session.newInstance("Event"))));
    }
    session.commit();
    var rid = a.getIdentity();

    session.close();

    session = createSessionInstance();
    session.begin();
    var agendas = executeQuery("SELECT FROM " + rid);
    var agenda = agendas.getFirst().asEntityOrNull();
    //noinspection unused,StatementWithEmptyBody
    for (var unusedE : agenda.<List<?>>getProperty("events")) {
      // NO NEED TO DO ANYTHING, JUST NEED TO ITERATE THE LIST
    }

    session.commit();

    session.freeze(false);
    session.release();

    session.begin();
    var activeTx = session.getActiveTransaction();
    agenda = activeTx.load(agenda);
    try {
      for (var i = 0; i < agenda.<List<Entity>>getProperty("events").size(); i++) {
        var transaction = session.getActiveTransaction();
        @SuppressWarnings("unused")
        var e = transaction.loadEntity(agenda.getLinkList("events").get(i));
        // NO NEED TO DO ANYTHING, JUST NEED TO ITERATE THE LIST
      }
    } catch (ConcurrentModificationException cme) {
      fail("Error iterating Object list", cme);
    }

    if (session.getTransactionInternal().isActive()) {
      session.rollback();
    }
  }

  @Test
  @Order(16)
  void mapObjectsListEmbeddedTest() {
    session.begin();
    var cresult = executeQuery("select * from EmbeddedChild");

    var childSize = cresult.size();

    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Silvester");

    var c = session.newEmbeddedEntity("EmbeddedChild");
    c.setProperty("name", "John");

    var c1 = session.newEmbeddedEntity("EmbeddedChild");
    c1.setProperty("name", "Jack");

    var c2 = session.newEmbeddedEntity("EmbeddedChild");
    c2.setProperty("name", "Bob");

    var c3 = session.newEmbeddedEntity("EmbeddedChild");
    c3.setProperty("name", "Sam");

    var c4 = session.newEmbeddedEntity("EmbeddedChild");
    c4.setProperty("name", "Dean");

    var list = session.newEmbeddedList();
    list.add(c1);
    list.add(c2);
    list.add(c3);
    list.add(c4);

    p.setProperty("embeddedList", list);

    session.commit();

    session.begin();
    cresult = executeQuery("select * from EmbeddedChild");

    assertEquals(cresult.size(), childSize);

    var rid = p.getIdentity();
    session.commit();
    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    assertEquals(4, loaded.<List<Entity>>getProperty("embeddedList").size());
    assertTrue(loaded.<List<Entity>>getProperty("embeddedList").get(0).isEmbedded());
    assertTrue(loaded.<List<Entity>>getProperty("embeddedList").get(1).isEmbedded());
    assertTrue(loaded.<List<Entity>>getProperty("embeddedList").get(2).isEmbedded());
    assertTrue(loaded.<List<Entity>>getProperty("embeddedList").get(3).isEmbedded());
    var transaction3 = session.getActiveTransaction();
    assertEquals(
        "EmbeddedChild",
        Objects.requireNonNull(
            transaction3.<Entity>load(loaded
                .<List<Entity>>getProperty("embeddedList")
                .get(0))
                .getSchemaClass())
            .getName());
    var transaction2 = session.getActiveTransaction();
    assertEquals(
        "EmbeddedChild",
        Objects.requireNonNull(
            transaction2.<Entity>load(loaded
                .<List<Entity>>getProperty("embeddedList")
                .get(1))
                .getSchemaClass())
            .getName());
    var transaction1 = session.getActiveTransaction();
    assertEquals(
        "EmbeddedChild",
        Objects.requireNonNull(
            transaction1.loadEntity(loaded
                .<List<Entity>>getProperty("embeddedList")
                .get(2))
                .getSchemaClass())
            .getName());
    var transaction = session.getActiveTransaction();
    assertEquals(
        "EmbeddedChild",
        Objects.requireNonNull(
            transaction.loadEntity(loaded
                .<List<Entity>>getProperty("embeddedList")
                .get(3))
                .getSchemaClass())
            .getName());
    assertEquals(
        "Jack", loaded.<List<Entity>>getProperty("embeddedList").get(0).getProperty("name"));
    assertEquals(
        "Bob", loaded.<List<Entity>>getProperty("embeddedList").get(1).getProperty("name"));
    assertEquals(
        "Sam", loaded.<List<Entity>>getProperty("embeddedList").get(2).getProperty("name"));
    assertEquals(
        "Dean", loaded.<List<Entity>>getProperty("embeddedList").get(3).getProperty("name"));
    session.commit();
  }

  @Test
  @Order(17)
  void mapObjectsSetEmbeddedTest() {
    session.begin();
    var cresult = executeQuery("select * from EmbeddedChild");
    var childSize = cresult.size();

    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Silvester");

    var c = session.newEmbeddedEntity("EmbeddedChild");
    c.setProperty("name", "John");

    var c1 = session.newEmbeddedEntity("EmbeddedChild");
    c1.setProperty("name", "Jack");

    var c2 = session.newEmbeddedEntity("EmbeddedChild");
    c2.setProperty("name", "Bob");

    var c3 = session.newEmbeddedEntity("EmbeddedChild");
    c3.setProperty("name", "Sam");

    var c4 = session.newEmbeddedEntity("EmbeddedChild");
    c4.setProperty("name", "Dean");

    var embeddedSet = session.newEmbeddedSet();
    embeddedSet.add(c);
    embeddedSet.add(c1);
    embeddedSet.add(c2);
    embeddedSet.add(c3);
    embeddedSet.add(c4);

    p.setProperty("embeddedSet", embeddedSet);

    session.commit();

    session.begin();
    cresult = executeQuery("select * from EmbeddedChild");

    assertEquals(cresult.size(), childSize);

    var rid = p.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    assertEquals(5, loaded.<Set<Entity>>getProperty("embeddedSet").size());
    for (var loadedC : loaded.<Set<Entity>>getProperty("embeddedSet")) {
      assertTrue(loadedC.isEmbedded());
      assertEquals("EmbeddedChild", loadedC.getSchemaClassName());
      assertTrue(
          loadedC.<String>getProperty("name").equals("John")
              || loadedC.<String>getProperty("name").equals("Jack")
              || loadedC.<String>getProperty("name").equals("Bob")
              || loadedC.<String>getProperty("name").equals("Sam")
              || loadedC.<String>getProperty("name").equals("Dean"));
    }
    session.commit();
  }

  @Test
  @Order(18)
  void mapObjectsMapEmbeddedTest() {
    session.begin();
    var cresult = executeQuery("select * from EmbeddedChild");

    var childSize = cresult.size();
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Silvester");

    var c = session.newEmbeddedEntity("EmbeddedChild");
    c.setProperty("name", "John");

    var c1 = session.newEmbeddedEntity("EmbeddedChild");
    c1.setProperty("name", "Jack");

    var c2 = session.newEmbeddedEntity("EmbeddedChild");
    c2.setProperty("name", "Bob");

    var c3 = session.newEmbeddedEntity("EmbeddedChild");
    c3.setProperty("name", "Sam");

    var c4 = session.newEmbeddedEntity("EmbeddedChild");
    c4.setProperty("name", "Dean");

    var embeddedChildren = session.newEmbeddedMap();
    embeddedChildren.put(c.getProperty("name"), c);
    embeddedChildren.put(c1.getProperty("name"), c1);
    embeddedChildren.put(c2.getProperty("name"), c2);
    embeddedChildren.put(c3.getProperty("name"), c3);
    embeddedChildren.put(c4.getProperty("name"), c4);

    p.setProperty("embeddedChildren", embeddedChildren);

    session.commit();

    session.begin();
    cresult = executeQuery("select * from EmbeddedChild");

    assertEquals(cresult.size(), childSize);

    var rid = p.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    assertEquals(5, loaded.<Map<String, Entity>>getProperty("embeddedChildren").size());
    for (var key : loaded.<Map<String, Entity>>getProperty("embeddedChildren").keySet()) {
      var loadedC = loaded.<Map<String, Entity>>getProperty("embeddedChildren").get(key);
      assertTrue(loadedC.isEmbedded());
      assertEquals("EmbeddedChild", loadedC.getSchemaClassName());
      assertTrue(
          loadedC.<String>getProperty("name").equals("John")
              || loadedC.<String>getProperty("name").equals("Jack")
              || loadedC.<String>getProperty("name").equals("Bob")
              || loadedC.<String>getProperty("name").equals("Sam")
              || loadedC.<String>getProperty("name").equals("Dean"));
    }
    session.commit();
  }

  @Test
  @Order(19)
  void mapObjectsNonExistingKeyTest() {
    session.begin();
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Silvester");

    var c1 = session.newInstance("Child");
    c1.setProperty("name", "John");

    var c2 = session.newInstance("Child");
    c2.setProperty("name", "Jack");

    var children = session.newLinkMap();
    children.put("first", c1);
    children.put("second", c2);

    p.setProperty("children", children);

    session.commit();

    session.begin();
    var c3 = session.newInstance("Child");
    c3.setProperty("name", "Olivia");
    var c4 = session.newInstance("Child");
    c4.setProperty("name", "Peter");

    var activeTx4 = session.getActiveTransaction();
    p = activeTx4.load(p);
    p.<Map<String, Identifiable>>getProperty("children").put("third", c3);
    p.<Map<String, Identifiable>>getProperty("children").put("fourth", c4);

    session.commit();

    session.begin();
    var cresult = executeQuery("select * from Child");
    assertFalse(cresult.isEmpty());

    var rid = p.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    var activeTx3 = session.getActiveTransaction();
    c1 = activeTx3.load(c1);
    var activeTx2 = session.getActiveTransaction();
    c2 = activeTx2.load(c2);
    var activeTx1 = session.getActiveTransaction();
    c3 = activeTx1.load(c3);
    var activeTx = session.getActiveTransaction();
    c4 = activeTx.load(c4);

    Entity loaded = session.load(rid);

    Identifiable identifiable3 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("first");
    var transaction3 = session.getActiveTransaction();
    assertEquals(
        c1.<String>getProperty("name"),
        transaction3.loadEntity(identifiable3)
            .getProperty("name"));
    Identifiable identifiable2 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("second");
    var transaction2 = session.getActiveTransaction();
    assertEquals(
        c2.<String>getProperty("name"),
        transaction2.loadEntity(identifiable2)
            .getProperty("name"));
    Identifiable identifiable1 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("third");
    var transaction1 = session.getActiveTransaction();
    assertEquals(
        c3.<String>getProperty("name"),
        transaction1.loadEntity(identifiable1)
            .getProperty("name"));
    Identifiable identifiable = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("fourth");
    var transaction = session.getActiveTransaction();
    assertEquals(
        c4.<String>getProperty("name"),
        transaction.loadEntity(identifiable)
            .getProperty("name"));
    assertNull(loaded.<Map<String, Identifiable>>getProperty("children").get("fifth"));
    session.commit();
  }

  @Test
  @Order(20)
  void mapObjectsLinkTwoSaveTest() {
    session.begin();
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Silvester");

    var c1 = session.newInstance("Child");
    c1.setProperty("name", "John");

    var c2 = session.newInstance("Child");
    c2.setProperty("name", "Jack");

    var children = session.newLinkMap();
    children.put("first", c1);
    children.put("second", c2);

    p.setProperty("children", children);

    var c3 = session.newInstance("Child");
    c3.setProperty("name", "Olivia");
    var c4 = session.newInstance("Child");
    c4.setProperty("name", "Peter");

    p.<Map<String, Identifiable>>getProperty("children").put("third", c3);
    p.<Map<String, Identifiable>>getProperty("children").put("fourth", c4);

    session.commit();

    session.begin();
    var cresult = executeQuery("select * from Child");
    assertFalse(cresult.isEmpty());

    var rid = p.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    var activeTx3 = session.getActiveTransaction();
    c1 = activeTx3.load(c1);
    var activeTx2 = session.getActiveTransaction();
    c2 = activeTx2.load(c2);
    var activeTx1 = session.getActiveTransaction();
    c3 = activeTx1.load(c3);
    var activeTx = session.getActiveTransaction();
    c4 = activeTx.load(c4);

    Identifiable identifiable3 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("first");
    var transaction3 = session.getActiveTransaction();
    assertEquals(
        c1.<String>getProperty("name"),
        transaction3.loadEntity(identifiable3)
            .getProperty("name"));
    Identifiable identifiable2 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("second");
    var transaction2 = session.getActiveTransaction();
    assertEquals(
        c2.<String>getProperty("name"),
        transaction2.loadEntity(identifiable2)
            .getProperty("name"));
    Identifiable identifiable1 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("third");
    var transaction1 = session.getActiveTransaction();
    assertEquals(
        c3.<String>getProperty("name"),
        transaction1.loadEntity(identifiable1)
            .getProperty("name"));
    Identifiable identifiable = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("fourth");
    var transaction = session.getActiveTransaction();
    assertEquals(
        c4.<String>getProperty("name"),
        transaction.loadEntity(identifiable)
            .getProperty("name"));
    session.commit();
  }

  @Test
  @Order(21)
  void mapObjectsLinkUpdateDatabaseNewInstanceTest() {
    // TEST WITH NEW INSTANCE
    session.begin();
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Fringe");

    var c = session.newInstance("Child");
    c.setProperty("name", "Peter");
    var c1 = session.newInstance("Child");
    c1.setProperty("name", "Walter");
    var c2 = session.newInstance("Child");
    c2.setProperty("name", "Olivia");
    var c3 = session.newInstance("Child");
    c3.setProperty("name", "Astrid");

    Map<String, Identifiable> children = session.newLinkMap();
    children.put(c.getProperty("name"), c);
    children.put(c1.getProperty("name"), c1);
    children.put(c2.getProperty("name"), c2);
    children.put(c3.getProperty("name"), c3);

    p.setProperty("children", children);

    session.commit();

    var rid = p.getIdentity();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    for (var key : loaded.<Map<String, Identifiable>>getProperty("children").keySet()) {
      assertTrue(
          key.equals("Peter")
              || key.equals("Walter")
              || key.equals("Olivia")
              || key.equals("Astrid"));
      Identifiable identifiable2 = loaded
          .<Map<String, Identifiable>>getProperty("children")
          .get(key);
      var transaction2 = session.getActiveTransaction();
      assertEquals(
          "Child",
          transaction2.loadEntity(identifiable2)
              .getSchemaClassName());
      Identifiable identifiable1 = loaded
          .<Map<String, Identifiable>>getProperty("children")
          .get(key);
      var transaction1 = session.getActiveTransaction();
      assertEquals(
          key,
          transaction1.loadEntity(identifiable1)
              .getProperty("name"));
      switch (key) {
        case "Peter" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          assertEquals(
              "Peter",
              transaction.loadEntity(identifiable)
                  .getProperty("name"));
        }
        case "Walter" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          assertEquals(
              "Walter",
              transaction.loadEntity(identifiable)
                  .getProperty("name"));
        }
        case "Olivia" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          assertEquals(
              "Olivia",
              transaction.loadEntity(identifiable)
                  .getProperty("name"));
        }
        case "Astrid" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          assertEquals(
              "Astrid",
              transaction.loadEntity(identifiable)
                  .getProperty("name"));
        }
      }
    }
    session.commit();

    session.begin();
    var entityIterator = session.browseClass("JavaComplexTestClass");
    while (entityIterator.hasNext()) {
      var reloaded = entityIterator.next();
      var c4 = session.newInstance("Child");
      c4.setProperty("name", "The Observer");

      children = reloaded.getProperty("children");
      if (children == null) {
        children = session.newLinkMap();
        reloaded.setProperty("children", children);
      }

      children.put(c4.getProperty("name"), c4);

    }
    session.commit();

    session.close();
    session = createSessionInstance();
    session.begin();
    entityIterator = session.browseClass("JavaComplexTestClass");
    while (entityIterator.hasNext()) {
      var reloaded = entityIterator.next();
      assertTrue(
          reloaded.<Map<String, Identifiable>>getProperty("children")
              .containsKey("The Observer"));
      assertNotNull(
          reloaded.<Map<String, Identifiable>>getProperty("children").get("The Observer"));
      Identifiable identifiable = reloaded
          .<Map<String, Identifiable>>getProperty("children")
          .get("The Observer");
      var transaction = session.getActiveTransaction();
      assertEquals(
          "The Observer",
          transaction.loadEntity(identifiable)
              .getProperty("name"));
      assertTrue(
          reloaded
              .<Map<String, Identifiable>>getProperty("children")
              .get("The Observer")
              .getIdentity()
              .isPersistent()
              && ((RecordIdInternal) reloaded
                  .<Map<String, Identifiable>>getProperty("children")
                  .get("The Observer")
                  .getIdentity())
                  .isValidPosition());
    }
    session.commit();
  }

  @Test
  @Order(22)
  void mapObjectsLinkUpdateJavaNewInstanceTest() {
    // TEST WITH NEW INSTANCE
    session.begin();
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Fringe");

    var c = session.newInstance("Child");
    c.setProperty("name", "Peter");
    var c1 = session.newInstance("Child");
    c1.setProperty("name", "Walter");
    var c2 = session.newInstance("Child");
    c2.setProperty("name", "Olivia");
    var c3 = session.newInstance("Child");
    c3.setProperty("name", "Astrid");

    var children = session.newLinkMap();
    children.put(c.getProperty("name"), c);
    children.put(c1.getProperty("name"), c1);
    children.put(c2.getProperty("name"), c2);
    children.put(c3.getProperty("name"), c3);

    p.setProperty("children", children);

    session.commit();

    var rid = p.getIdentity();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    for (var key : loaded.<Map<String, Identifiable>>getProperty("children").keySet()) {
      assertTrue(
          key.equals("Peter")
              || key.equals("Walter")
              || key.equals("Olivia")
              || key.equals("Astrid"));
      Identifiable identifiable2 = loaded
          .<Map<String, Identifiable>>getProperty("children")
          .get(key);
      var transaction2 = session.getActiveTransaction();
      assertEquals(
          "Child",
          transaction2.loadEntity(identifiable2)
              .getSchemaClassName());
      Identifiable identifiable1 = loaded
          .<Map<String, Identifiable>>getProperty("children")
          .get(key);
      var transaction1 = session.getActiveTransaction();
      assertEquals(
          key,
          transaction1.loadEntity(identifiable1)
              .getProperty("name"));
      switch (key) {
        case "Peter" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          assertEquals(
              "Peter",
              transaction.loadEntity(identifiable)
                  .getProperty("name"));
        }
        case "Walter" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          assertEquals(
              "Walter",
              transaction.loadEntity(identifiable)
                  .getProperty("name"));
        }
        case "Olivia" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          assertEquals(
              "Olivia",
              transaction.loadEntity(identifiable)
                  .getProperty("name"));
        }
        case "Astrid" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          assertEquals(
              "Astrid",
              transaction.loadEntity(identifiable)
                  .getProperty("name"));
        }
      }
    }

    var entityIterator = session.browseClass("JavaComplexTestClass");
    while (entityIterator.hasNext()) {
      var reloaded = entityIterator.next();
      var c4 = session.newInstance("Child");
      c4.setProperty("name", "The Observer");

      reloaded.<Map<String, Identifiable>>getProperty("children").put(c4.getProperty("name"), c4);

    }
    session.commit();

    session.close();
    session = createSessionInstance();
    session.begin();
    entityIterator = session.browseClass("JavaComplexTestClass");
    while (entityIterator.hasNext()) {
      var reloaded = entityIterator.next();
      assertTrue(
          reloaded.<Map<String, Identifiable>>getProperty("children")
              .containsKey("The Observer"));
      assertNotNull(
          reloaded.<Map<String, Identifiable>>getProperty("children").get("The Observer"));
      Identifiable identifiable = reloaded
          .<Map<String, Identifiable>>getProperty("children")
          .get("The Observer");
      var transaction = session.getActiveTransaction();
      assertEquals(
          "The Observer",
          transaction.loadEntity(identifiable)
              .getProperty("name"));
      assertTrue(
          reloaded
              .<Map<String, Identifiable>>getProperty("children")
              .get("The Observer")
              .getIdentity()
              .isPersistent()
              && ((RecordIdInternal) reloaded
                  .<Map<String, Identifiable>>getProperty("children")
                  .get("The Observer")
                  .getIdentity())
                  .isValidPosition());
    }
    session.commit();
  }

  @Test
  @Order(23)
  void mapStringTest() {
    session.begin();
    Map<String, String> relatives = new HashMap<>();
    relatives.put("father", "Mike");
    relatives.put("mother", "Julia");

    // TEST WITH OBJECT DATABASE NEW INSTANCE AND HANDLER MANAGEMENT
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");

    var stringMap = session.newEmbeddedMap();
    stringMap.put("father", "Mike");
    stringMap.put("mother", "Julia");

    p.setProperty("stringMap", stringMap);

    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(), p.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }

    session.commit();

    var rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    loaded.<Map<String, String>>getProperty("stringMap").put("brother", "Nike");
    relatives.put("brother", "Nike");

    session.commit();

    session.begin();
    var activeTx3 = session.getActiveTransaction();
    loaded = activeTx3.load(loaded);
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    session.commit();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }

    var activeTx2 = session.getActiveTransaction();
    session.delete(activeTx2.<Entity>load(loaded));
    session.commit();

    session.begin();
    // TEST WITH OBJECT DATABASE NEW INSTANCE AND MAP DIRECT SET
    p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");
    p.setProperty("stringMap", session.newEmbeddedMap(relatives));

    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(), p.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    loaded.<Map<String, String>>getProperty("stringMap").put("brother", "Nike");
    relatives.put("brother", "Nike");

    session.commit();

    session.begin();
    for (var entry : relatives.entrySet()) {
      var activeTx = session.getActiveTransaction();
      loaded = activeTx.load(loaded);
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }

    var activeTx1 = session.getActiveTransaction();
    session.delete(activeTx1.<Entity>load(loaded));
    session.commit();

    session.begin();
    // TEST WITH JAVA CONSTRUCTOR
    p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");
    p.setProperty("stringMap", session.newEmbeddedMap(relatives));

    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(), p.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    loaded.<Map<String, String>>getProperty("stringMap").put("brother", "Nike");
    relatives.put("brother", "Nike");

    session.commit();

    session.begin();
    for (var entry : relatives.entrySet()) {
      var activeTx = session.getActiveTransaction();
      loaded = activeTx.load(loaded);
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(loaded));
    session.commit();
  }

  @Test
  @Order(24)
  void setStringTest() {
    session.begin();
    var testClass = session.newInstance("JavaComplexTestClass");
    Set<String> roles = new HashSet<>();

    roles.add("manager");
    roles.add("developer");
    testClass.setProperty("stringSet", session.newEmbeddedSet(roles));

    Entity testClassProxy = testClass;
    session.commit();

    session.begin();
    var activeTx4 = session.getActiveTransaction();
    testClassProxy = activeTx4.load(testClassProxy);
    assertEquals(testClassProxy.<Set<String>>getProperty("stringSet").size(), roles.size());
    for (var referenceRole : roles) {
      var activeTx = session.getActiveTransaction();
      testClassProxy = activeTx.load(testClassProxy);
      assertTrue(
          testClassProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }

    var orid = testClassProxy.getIdentity();
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    Entity loadedProxy = session.load(orid);
    assertEquals(loadedProxy.<Set<String>>getProperty("stringSet").size(), roles.size());
    for (var referenceRole : roles) {
      assertTrue(loadedProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }

    var activeTx3 = session.getActiveTransaction();
    activeTx3.load(loadedProxy);
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    loadedProxy = activeTx2.load(loadedProxy);
    assertEquals(loadedProxy.<Set<String>>getProperty("stringSet").size(), roles.size());
    for (var referenceRole : roles) {
      assertTrue(loadedProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }

    loadedProxy.<Set<String>>getProperty("stringSet").remove("developer");
    roles.remove("developer");
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    loadedProxy = activeTx1.load(loadedProxy);
    assertEquals(loadedProxy.<Set<String>>getProperty("stringSet").size(), roles.size());
    for (var referenceRole : roles) {
      assertTrue(loadedProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    var activeTx0 = session.getActiveTransaction();
    loadedProxy = activeTx0.load(loadedProxy);
    assertEquals(loadedProxy.<Set<String>>getProperty("stringSet").size(), roles.size());
    for (var referenceRole : roles) {
      assertTrue(loadedProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }
    session.commit();
  }

  @Test
  @Order(25)
  void mapStringListTest() {
    session.begin();
    Map<String, List<String>> songAndMovies = new HashMap<>();
    List<String> movies = new ArrayList<>();
    List<String> songs = new ArrayList<>();

    movies.add("Star Wars");
    movies.add("Star Wars: The Empire Strikes Back");
    movies.add("Star Wars: The return of the Jedi");
    songs.add("Metallica - Master of Puppets");
    songs.add("Daft Punk - Harder, Better, Faster, Stronger");
    songs.add("Johnny Cash - Cocaine Blues");
    songs.add("Skrillex - Scary Monsters & Nice Sprites");
    songAndMovies.put("movies", movies);
    songAndMovies.put("songs", songs);

    // TEST WITH OBJECT DATABASE NEW INSTANCE AND HANDLER MANAGEMENT
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");

    p.setProperty("stringListMap", session.newEmbeddedMap(
        songAndMovies.entrySet().stream()
            .collect(Collectors.toMap(
                Entry::getKey,
                e -> session.newEmbeddedList(e.getValue())))));

    for (var entry : songAndMovies.entrySet()) {
      assertEquals(
          entry.getValue(),
          p.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    session.commit();

    var rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    Entity loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, List<String>>>getProperty("stringListMap"));
    for (var entry : songAndMovies.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    var activeTx3 = session.getActiveTransaction();
    session.delete(activeTx3.<Entity>load(loaded));
    session.commit();

    session.begin();
    // TEST WITH OBJECT DATABASE NEW INSTANCE AND MAP DIRECT SET
    p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");
    p.setProperty("stringListMap", session.newEmbeddedMap(
        songAndMovies.entrySet().stream()
            .collect(Collectors.toMap(
                Entry::getKey,
                e -> session.newEmbeddedList(e.getValue())))));

    for (var entry : songAndMovies.entrySet()) {
      assertEquals(
          entry.getValue(),
          p.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, List<String>>>getProperty("stringListMap"));
    for (var entry : songAndMovies.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    var activeTx2 = session.getActiveTransaction();
    session.delete(activeTx2.<Entity>load(loaded));
    session.commit();

    session.begin();
    // TEST WITH OBJECT DATABASE NEW INSTANCE LIST DIRECT ADD
    p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");

    var stringListMap = new HashMap<String, List<String>>();
    stringListMap.put("movies", new ArrayList<>());
    stringListMap.get("movies").add("Star Wars");
    stringListMap.get("movies").add("Star Wars: The Empire Strikes Back");
    stringListMap.get("movies").add("Star Wars: The return of the Jedi");

    stringListMap.put("songs", new ArrayList<>());
    stringListMap.get("songs").add("Metallica - Master of Puppets");
    stringListMap.get("songs").add("Daft Punk - Harder, Better, Faster, Stronger");
    stringListMap.get("songs").add("Johnny Cash - Cocaine Blues");
    stringListMap.get("songs").add("Skrillex - Scary Monsters & Nice Sprites");

    p.setProperty("stringListMap", session.newEmbeddedMap(
        stringListMap.entrySet().stream()
            .collect(Collectors.toMap(
                Entry::getKey,
                e -> session.newEmbeddedList(e.getValue())))));

    for (var entry : songAndMovies.entrySet()) {
      assertEquals(
          entry.getValue(),
          p.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, List<String>>>getProperty("stringListMap"));
    for (var entry : songAndMovies.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    var activeTx1 = session.getActiveTransaction();
    session.delete(activeTx1.<Entity>load(loaded));
    session.commit();

    // TEST WITH JAVA CONSTRUCTOR
    session.begin();
    p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");
    p.setProperty("stringListMap", session.newEmbeddedMap(
        songAndMovies.entrySet().stream()
            .collect(Collectors.toMap(
                Entry::getKey,
                e -> session.newEmbeddedList(e.getValue())))));

    for (var entry : songAndMovies.entrySet()) {
      assertEquals(
          entry.getValue(),
          p.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, List<String>>>getProperty("stringListMap"));
    for (var entry : songAndMovies.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(loaded));
    session.commit();
  }

  @Test
  @Order(26)
  void update() {
    var i = new int[] {0};

    session.executeInTxBatches(session.browseClass("Account"),
        (session, a) -> {
          if (i[0] % 2 == 0) {
            a.<List<Identifiable>>getProperty("addresses");
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

  @Test
  @Order(27)
  void testUpdate() {
    var i = 0;
    session.begin();
    Entity a;
    for (var iterator = session.query("select from Account"); iterator.hasNext();) {
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
        assertEquals(
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
        assertEquals(
            "Italy",
            transaction1.<Entity>load(identifiable1)
                .getProperty("name"));
      }

      assertEquals(i + 500.1f, a.<Float>getProperty("salary"));

      i++;
    }
    session.commit();
  }

  @Test
  @Order(28)
  void checkLazyLoadingOff() {
    session.begin();
    var profiles = session.countClass("Profile");
    session.rollback();

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

    var morpheus = session.newEntity("Profile");
    morpheus.setProperty("nick", "Morpheus");

    var trinity = session.newEntity("Profile");
    trinity.setProperty("nick", "Trinity");

    var followers = session.newLinkSet();
    followers.add(trinity);
    followers.add(morpheus);

    neo.setProperty("followers", followers);
    neo.setProperty("location", address);

    session.commit();

    session.begin();
    assertEquals(profiles + 3, session.countClass("Profile"));

    var entityIterator = session.browseClass("Profile");

    while (entityIterator.hasNext()) {
      var obj = entityIterator.next();
      var followersList = obj.<Set<Identifiable>>getProperty("followers");
      assertTrue(followersList == null || followersList instanceof EntityLinkSetImpl);
      if (obj.<String>getProperty("nick").equals("Neo")) {
        assertEquals(2, obj.<Set<Identifiable>>getProperty("followers").size());
        Identifiable identifiable = obj.<Set<Identifiable>>getProperty("followers")
            .iterator()
            .next();
        var transaction = session.getActiveTransaction();
        assertEquals(
            "Profile",
            transaction.loadEntity(identifiable)
                .getSchemaClassName());
      } else if (obj.<String>getProperty("nick").equals("Morpheus")
          || obj.<String>getProperty("nick").equals("Trinity")) {
        assertNull(obj.<Set<Identifiable>>getProperty("followers"));
      }
    }
    session.commit();
  }

  @Test
  @Order(29)
  void queryPerFloat() {
    session.begin();
    var resultSet = executeQuery("select * from Account where salary = 500.10");

    assertFalse(resultSet.isEmpty());

    Entity account;
    for (var entries : resultSet) {
      account = entries.asEntityOrNull();
      assertEquals(500.10f, account.<Float>getProperty("salary"));
    }
    session.commit();
  }

  @Test
  @Order(30)
  void queryCross3Levels() {
    session.begin();
    var resultSet =
        executeQuery("select from Profile where location.city.country.name = 'Spain'");

    assertFalse(resultSet.isEmpty());

    Entity profile;
    for (var entries : resultSet) {
      profile = entries.asEntityOrNull();
      Identifiable identifiable3 = profile
          .getEntity("location");
      var transaction3 = session.getActiveTransaction();
      Identifiable identifiable = transaction3.<Entity>load(identifiable3)
          .getProperty("city");
      var transaction = session.getActiveTransaction();
      Identifiable identifiable1 = transaction.<Entity>load(identifiable);
      var transaction1 = session.getActiveTransaction();
      Identifiable identifiable2 = transaction1.<Entity>load(identifiable1)
          .getProperty("country");
      var transaction2 = session.getActiveTransaction();
      assertEquals(
          "Spain",
          transaction2.<Entity>load(identifiable2)
              .getProperty("name"));
    }
    session.commit();
  }

  @Test
  @Order(31)
  void deleteFirst() {
    session.begin();
    startRecordNumber = session.countClass("Account");
    session.rollback();

    // DELETE ALL THE RECORD IN THE CLASS
    session.forEachInTx(session.browseClass("Account"),
        (session, document) -> {
          session.delete(document);
          return false;
        });

    session.begin();
    assertEquals(startRecordNumber - 1, session.countClass("Account"));
    session.rollback();
  }

  @Test
  @Order(32)
  void testSaveMultiCircular() {
    session = createSessionInstance();
    try {
      session.begin();
      startRecordNumber = session.countCollectionElements("Profile");
      session.rollback();
      session.begin();
      var bObama = session.newInstance("Profile");
      bObama.setProperty("nick", "TheUSPresident");
      bObama.setProperty("name", "Barack");
      bObama.setProperty("surname", "Obama");

      var address = session.newInstance("Address");
      address.setProperty("type", "Residence");

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

  @Test
  @Order(33)
  void embeddedMapObjectTest() {
    var cal = Calendar.getInstance();
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    Map<String, Object> relatives = new HashMap<>();
    relatives.put("father", "Mike");
    relatives.put("mother", "Julia");
    relatives.put("number", 10);
    relatives.put("date", cal.getTime());

    session.begin();
    // TEST WITH OBJECT DATABASE NEW INSTANCE AND HANDLER MANAGEMENT
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");

    var mapObject = Map.of(
        "father", "Mike",
        "mother", "Julia",
        "number", 10,
        "date", cal.getTime());

    p.setProperty("mapObject", session.newEmbeddedMap(mapObject));

    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(), p.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    session.commit();

    var rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }
    loaded.<Map<String, Object>>getProperty("mapObject").put("brother", "Nike");
    relatives.put("brother", "Nike");

    session.commit();

    session.begin();
    for (var entry : relatives.entrySet()) {
      var activeTx = session.getActiveTransaction();
      loaded = activeTx.load(loaded);
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }
    session.commit();

    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    var activeTx2 = session.getActiveTransaction();
    session.delete(activeTx2.<Entity>load(loaded));
    session.commit();

    // TEST WITH OBJECT DATABASE NEW INSTANCE AND MAP DIRECT SET
    session.begin();
    p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");
    p.setProperty("mapObject", session.newEmbeddedMap(relatives));

    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(), p.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    loaded.<Map<String, Object>>getProperty("mapObject").put("brother", "Nike");
    relatives.put("brother", "Nike");

    session.commit();

    session.begin();
    for (var entry : relatives.entrySet()) {
      var activeTx = session.getActiveTransaction();
      loaded = activeTx.load(loaded);
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }
    session.commit();

    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    var activeTx1 = session.getActiveTransaction();
    session.delete(activeTx1.<Entity>load(loaded));
    session.commit();
    session.begin();

    p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Chuck");
    p.setProperty("mapObject", session.newEmbeddedMap(relatives));

    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(), p.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }
    loaded.<Map<String, Object>>getProperty("mapObject").put("brother", "Nike");

    relatives.put("brother", "Nike");
    session.commit();

    session.begin();
    for (var entry : relatives.entrySet()) {
      var activeTx = session.getActiveTransaction();
      loaded = activeTx.load(loaded);
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }
    session.commit();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);

    assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(loaded));
    session.commit();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  @Order(34)
  void testNoGenericCollections() {
    session.begin();
    var p = session.newInstance("JavaNoGenericCollectionsTestClass");
    var c1 = session.newInstance("Child");
    c1.setProperty("name", "1");
    var c2 = session.newInstance("Child");
    c2.setProperty("name", "2");
    var c3 = session.newInstance("Child");
    c3.setProperty("name", "3");
    var c4 = session.newInstance("Child");
    c4.setProperty("name", "4");

    var list = session.newLinkList();
    var set = session.newLinkSet();
    var map = session.newLinkMap();

    list.add(c1);
    list.add(c2);
    list.add(c3);
    list.add(c4);

    set.add(c1);
    set.add(c2);
    set.add(c3);
    set.add(c4);

    map.put("1", c1);
    map.put("2", c2);
    map.put("3", c3);
    map.put("4", c4);

    p.setProperty("list", list);
    p.setProperty("set", set);
    p.setProperty("map", map);

    session.commit();

    var rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    p = session.load(rid);

    assertEquals(4, p.<List>getProperty("list").size());
    assertEquals(4, p.<Set>getProperty("set").size());
    assertEquals(4, p.<Map>getProperty("map").size());
    for (var i = 0; i < 4; i++) {
      var transaction1 = session.getActiveTransaction();
      var o = transaction1.loadEntity(p.getLinkList("list").get(i));
      assertEquals((i + 1) + "", o.getProperty("name"));
      Identifiable identifiable = p.getLinkMap("map").get((i + 1) + "");
      var transaction = session.getActiveTransaction();
      o = transaction.loadEntity(identifiable);
      assertEquals((i + 1) + "", o.getProperty("name"));
    }
    for (var r : p.getLinkSet("set")) {
      var transaction = session.getActiveTransaction();
      var o = transaction.loadEntity(r);
      var nameToInt = Integer.parseInt(o.getProperty("name"));
      assertTrue(nameToInt > 0 && nameToInt < 5);
    }

    var other = session.newEntity("JavaSimpleTestClass");
    p.<List>getProperty("list").add(other);
    p.<Set>getProperty("set").add(other);
    p.<Map>getProperty("map").put("5", other);

    session.commit();

    session.close();
    session = createSessionInstance();
    session.begin();
    p = session.load(rid);
    assertEquals(5, p.getLinkList("list").size());
    assertEquals(5, p.getLinkSet("set").size());
    assertEquals(5, p.getLinkMap("map").size());
    session.commit();
  }

  @Test
  @Order(35)
  void oidentifableFieldsTest() {
    session.begin();
    var p = session.newInstance("JavaComplexTestClass");
    p.setProperty("name", "Dean Winchester");

    var testEmbeddedDocument = ((EntityImpl) session.newEmbeddedEntity());
    testEmbeddedDocument.setProperty("testEmbeddedField", "testEmbeddedValue");

    p.setProperty("embeddedDocument", testEmbeddedDocument);
    var testDocument = ((EntityImpl) session.newEntity());
    testDocument.setProperty("testField", "testValue");

    session.commit();

    session.begin();
    var activeTx3 = session.getActiveTransaction();
    p = activeTx3.load(p);
    var activeTx2 = session.getActiveTransaction();
    testDocument = activeTx2.load(testDocument);
    p.setProperty("document", testDocument);

    var testRecordBytes =
        session.newBlob(
            "this is a bytearray test. if you read this Object database has stored it correctly"
                .getBytes());

    p.setProperty("byteArray", testRecordBytes);

    session.commit();

    var rid = p.getIdentity();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    assertNotNull(loaded.getBlob("byteArray"));
    try {
      try (var out = new ByteArrayOutputStream()) {
        loaded.getBlob("byteArray").toOutputStream(out);
        assertArrayEquals(
            "this is a bytearray test. if you read this Object database has stored it correctly"
                .getBytes(),
            out.toByteArray());
        assertEquals(
            "this is a bytearray test. if you read this Object database has stored it correctly",
            out.toString());
      }
    } catch (IOException ioe) {
      fail();
      LogManager.instance().error(this, "Error reading byte[]", ioe);
    }
    assertTrue(loaded.getEntity("document") instanceof EntityImpl);
    assertEquals(
        "testValue", loaded.getEntity("document").getProperty("testField"));
    assertTrue(loaded.getEntity("document").getIdentity().isPersistent());

    assertTrue(loaded.getEntity("embeddedDocument") instanceof EntityImpl);
    assertEquals(
        "testEmbeddedValue",
        loaded.getEntity("embeddedDocument").getProperty("testEmbeddedField"));
    assertFalse(
        ((RecordIdInternal) loaded.getEntity("embeddedDocument").getIdentity()).isValidPosition());

    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    p = session.newInstance("JavaComplexTestClass");
    var thumbnailImageBytes =
        "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
            .getBytes();
    var oRecordBytes = session.newBlob(thumbnailImageBytes);

    p.setProperty("byteArray", oRecordBytes);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    p = activeTx1.load(p);
    assertNotNull(p.getBlob("byteArray"));
    try {
      try (var out = new ByteArrayOutputStream()) {
        p.getBlob("byteArray").toOutputStream(out);
        assertArrayEquals(
            "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
                .getBytes(),
            out.toByteArray());
        assertEquals(
            "this is a bytearray test. if you read this Object database has stored it"
                + " correctlyVERSION2",
            out.toString());
      }
    } catch (IOException ioe) {
      fail();
      LogManager.instance().error(this, "Error reading byte[]", ioe);
    }
    rid = p.getIdentity();

    session.commit();
    session.close();

    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);

    assertNotNull(loaded.getBlob("byteArray"));
    try {
      try (var out = new ByteArrayOutputStream()) {
        loaded.getBlob("byteArray").toOutputStream(out);
        assertArrayEquals(
            "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
                .getBytes(),
            out.toByteArray());
        assertEquals(
            "this is a bytearray test. if you read this Object database has stored it"
                + " correctlyVERSION2",
            out.toString());
      }
    } catch (IOException ioe) {
      fail();
      LogManager.instance().error(this, "Error reading byte[]", ioe);
      throw new RuntimeException(ioe);
    }
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    p = session.newInstance("JavaComplexTestClass");
    thumbnailImageBytes =
        "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
            .getBytes();

    oRecordBytes = session.newBlob(thumbnailImageBytes);
    p.setProperty("byteArray", oRecordBytes);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    p = activeTx.load(p);
    assertNotNull(p.getBlob("byteArray"));
    try {
      try (var out = new ByteArrayOutputStream()) {
        p.getBlob("byteArray").toOutputStream(out);
        assertArrayEquals(
            "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
                .getBytes(),
            out.toByteArray());
        assertEquals(
            "this is a bytearray test. if you read this Object database has stored it"
                + " correctlyVERSION2",
            out.toString());
      }
    } catch (IOException ioe) {
      fail();
    }
    rid = p.getIdentity();

    session.commit();
    session.close();

    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);

    loaded.getBlob("byteArray");
    try {
      try (var out = new ByteArrayOutputStream()) {
        loaded.getBlob("byteArray").toOutputStream(out);
        assertArrayEquals(
            "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
                .getBytes(),
            out.toByteArray());
        assertEquals(
            "this is a bytearray test. if you read this Object database has stored it"
                + " correctlyVERSION2",
            out.toString());
      }
    } catch (IOException ioe) {
      fail();
      LogManager.instance().error(this, "Error reading byte[]", ioe);
    }
    session.commit();
  }

  @Test
  @Order(36)
  void testEmbeddedDeletion() {
    session.begin();
    var parent = session.newInstance("Parent");
    parent.setProperty("name", "Big Parent");

    var embedded = session.newEmbeddedEntity("EmbeddedChild");
    embedded.setProperty("name", "Little Child");

    parent.setProperty("embeddedChild", embedded);

    var presult = executeQuery("select from Parent");
    var cresult = executeQuery("select from EmbeddedChild");
    assertEquals(1, presult.size());
    assertEquals(0, cresult.size());

    var child = session.newEmbeddedEntity("EmbeddedChild");
    child.setProperty("name", "Little Child");
    parent.setProperty("child", child);

    session.commit();

    session.begin();
    presult = executeQuery("select from Parent");
    cresult = executeQuery("select from EmbeddedChild");
    assertEquals(1, presult.size());
    assertEquals(0, cresult.size());

    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<EntityImpl>load(parent));
    session.commit();

    session.begin();
    presult = executeQuery("select * from Parent");
    cresult = executeQuery("select * from EmbeddedChild");

    assertEquals(0, presult.size());
    assertEquals(0, cresult.size());
    session.commit();
  }

  @Test
  @Order(37)
  void testObjectDelete() {
    session.begin();
    var media = session.newEntity("Media");
    var testRecord = session.newBlob("This is a test".getBytes());

    media.setProperty("content", testRecord);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    media = activeTx1.load(media);
    assertEquals("This is a test", new String(media.getBlob("content").toStream()));

    // try to delete
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(media));
    session.commit();
  }

  @Test
  @Order(38)
  void commandWithPositionalParameters() {
    session.begin();
    var resultSet =
        executeQuery("select from Profile where name = ? and surname = ?", "Barack", "Obama");

    assertFalse(resultSet.isEmpty());
    session.commit();
  }

  @Test
  @Order(39)
  void queryWithPositionalParameters() {
    session.begin();
    var resultSet =
        executeQuery("select from Profile where name = ? and surname = ?", "Barack", "Obama");

    assertFalse(resultSet.isEmpty());
    session.commit();
  }

  @Test
  @Order(40)
  void queryWithRidAsParameters() {
    addGaribaldiAndBonaparte();
    session.begin();
    Entity profile = session.browseClass("Profile").next();
    var resultSet =
        executeQuery("select from Profile where @rid = ?", profile.getIdentity());

    assertEquals(1, resultSet.size());
    session.commit();
  }

  @Test
  @Order(41)
  void queryWithRidStringAsParameters() {
    addBarackObamaAndFollowers();
    session.begin();
    Entity profile = session.browseClass("Profile").next();
    var resultSet =
        executeQuery("select from Profile where @rid = ?", profile.getIdentity());

    assertEquals(1, resultSet.size());
    session.commit();
  }

  @Test
  @Order(42)
  void commandWithNamedParameters() {
    addBarackObamaAndFollowers();

    var params = new HashMap<String, String>();
    params.put("name", "Barack");
    params.put("surname", "Obama");

    session.begin();
    var resultSet =
        executeQuery("select from Profile where name = :name and surname = :surname", params);
    assertFalse(resultSet.isEmpty());
    session.commit();
  }

  @Test
  @Order(43)
  void commandWithWrongNamedParameters() {
    try {
      var params = new HashMap<String, String>();
      params.put("name", "Barack");
      params.put("surname", "Obama");

      executeQuery("select from Profile where name = :name and surname = :surname%", params);
      fail();
    } catch (CommandSQLParsingException e) {
      assertTrue(true);
    }
  }

  @Test
  @Order(44)
  void queryConcatAttrib() {
    session.begin();
    assertFalse(executeQuery("select from City where country.@class = 'Country'").isEmpty());
    assertEquals(
        0, executeQuery("select from City where country.@class = 'Country22'").size());
    session.commit();
  }

  @Test
  @Order(45)
  void queryPreparedTwice() {
    try (var db = acquireSession()) {
      db.begin();

      var params = new HashMap<String, String>();
      params.put("name", "Barack");
      params.put("surname", "Obama");

      var result =
          db.query("select from Profile where name = :name and surname = :surname", params)
              .entityStream()
              .toList();
      assertFalse(result.isEmpty());

      result =
          db.query("select from Profile where name = :name and surname = :surname", params)
              .entityStream()
              .toList();
      assertFalse(result.isEmpty());
      db.commit();
    }
  }

  @Test
  @Order(46)
  void queryById() {
    session.begin();
    var result1 = executeQuery("select from Profile limit 1");
    var result2 =
        executeQuery("select from Profile where @rid = ?", result1.getFirst().getIdentity());

    assertFalse(result2.isEmpty());
    session.commit();
  }

  @Test
  @Order(47)
  void queryByIdNewApi() {
    session.begin();
    session.execute("insert into Profile set nick = 'foo', name='foo'").close();
    session.commit();

    session.begin();
    var result1 = executeQuery("select from Profile where nick = 'foo'");

    assertEquals(1, result1.size());
    assertEquals("Profile", result1.getFirst().asEntityOrNull().getSchemaClassName());
    var profile = result1.getFirst().asEntityOrNull();

    assertEquals("foo", profile.getProperty("nick"));
    session.commit();
  }

  @Test
  @Disabled("Embedded binary field storage not yet implemented")
  @Order(48)
  void testEmbeddedBinary() {
    var a = session.newEntity("Account");
    a.setProperty("name", "Chris");
    a.setProperty("surname", "Martin");
    a.setProperty("id", 0);
    a.setProperty("thumbnail", new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

    session.commit();

    session.close();

    session = createSessionInstance();
    Entity aa = session.load(a.getIdentity());
    assertNotNull(a.getProperty("thumbnail"));
    assertNotNull(aa.getProperty("thumbnail"));
    byte[] b = aa.getProperty("thumbnail");
    for (var i = 0; i < 10; ++i) {
      assertEquals(i, b[i]);
    }
  }

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
    if (session.getSchema().existsClass("Parent")) {
      session.getSchema().dropClass("Parent");
    }
    if (session.getSchema().existsClass("EmbeddedChild")) {
      session.getSchema().dropClass("EmbeddedChild");
    }

    var parentCls = session.createClass("Parent");
    parentCls.createProperty("name", PropertyType.STRING);
    parentCls.createProperty("child", PropertyType.EMBEDDED,
        session.getClass("EmbeddedChild"));
    parentCls.createProperty("embeddedChild", PropertyType.EMBEDDED,
        session.getClass("EmbeddedChild"));

    var childCls = session.createAbstractClass("EmbeddedChild");
    childCls.createProperty("name", PropertyType.STRING);
  }
}
