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
package com.jetbrains.youtrack.db.auto;

import static org.testng.Assert.fail;

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class CRUDTest extends BaseDBTest {

  protected long startRecordNumber;

  private Entity rome;

  @Parameters(value = "remote")
  public CRUDTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

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
  public void create() {
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

  @Test(dependsOnMethods = "create")
  public void testCreate() {
    Assert.assertEquals(session.countClass("Account") - startRecordNumber, TOT_RECORDS_ACCOUNT);
  }

  @Test(dependsOnMethods = "testCreate")
  public void testCreateClass() {
    var schema = session.getMetadata().getSchema();
    Assert.assertNull(schema.getClass("Dummy"));
    var dummyClass = schema.createClass("Dummy");
    dummyClass.createProperty("name", PropertyType.STRING);

    Assert.assertEquals(session.countClass("Dummy"), 0);
    Assert.assertNotNull(schema.getClass("Dummy"));
  }

  @Test
  public void testSimpleTypes() {
    session.begin();
    var element = session.newEntity("JavaSimpleTestClass");
    Assert.assertEquals(element.getProperty("text"), "initTest");

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
    Assert.assertEquals(loadedRecord.getProperty("text"), "test");
    Assert.assertEquals(loadedRecord.<Integer>getProperty("numberSimple"), 12345);
    Assert.assertEquals(loadedRecord.<Double>getProperty("doubleSimple"), 12.34d);
    Assert.assertEquals(loadedRecord.<Float>getProperty("floatSimple"), 123.45f);
    Assert.assertEquals(loadedRecord.<Long>getProperty("longSimple"), 12345678L);
    Assert.assertEquals(loadedRecord.<Byte>getProperty("byteSimple"), (byte) 1);
    Assert.assertEquals(loadedRecord.<Boolean>getProperty("flagSimple"), true);
    Assert.assertEquals(loadedRecord.getProperty("dateField"), date);
    session.commit();
  }

  @Test(dependsOnMethods = "testSimpleTypes")
  public void testSimpleArrayTypes() {
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
        fail("Should fail on array values");
        //
      } catch (DatabaseException ex) {

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

    Assert.assertNotNull(element.getProperty("text"));
    Assert.assertNotNull(element.getProperty("numberSimple"));
    Assert.assertNotNull(element.getProperty("longSimple"));
    Assert.assertNotNull(element.getProperty("doubleSimple"));
    Assert.assertNotNull(element.getProperty("floatSimple"));
    Assert.assertNotNull(element.getProperty("flagSimple"));
    Assert.assertNotNull(element.getProperty("dateField"));

    session.commit();
    var id = element.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loadedElement = session.load(id);
    Assert.assertNotNull(loadedElement.getProperty("text"));
    Assert.assertNotNull(loadedElement.getProperty("numberSimple"));
    Assert.assertNotNull(loadedElement.getProperty("longSimple"));
    Assert.assertNotNull(loadedElement.getProperty("doubleSimple"));
    Assert.assertNotNull(loadedElement.getProperty("floatSimple"));
    Assert.assertNotNull(loadedElement.getProperty("flagSimple"));
    Assert.assertNotNull(loadedElement.getProperty("dateField"));

    Assert.assertEquals(loadedElement.<List<String>>getProperty("text").size(), 10);
    Assert.assertEquals(loadedElement.<List<Integer>>getProperty("numberSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Long>>getProperty("longSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Double>>getProperty("doubleSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Float>>getProperty("floatSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Boolean>>getProperty("flagSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Date>>getProperty("dateField").size(), 10);

    for (var i = 0; i < 10; i++) {
      Assert.assertEquals(loadedElement.<List<String>>getProperty("text").get(i), i + "");
      Assert.assertEquals(loadedElement.<List<Integer>>getProperty("numberSimple").get(i), i);
      Assert.assertEquals(loadedElement.<List<Long>>getProperty("longSimple").get(i), i);
      Assert.assertEquals(loadedElement.<List<Double>>getProperty("doubleSimple").get(i), i);
      Assert.assertEquals(loadedElement.<List<Float>>getProperty("floatSimple").get(i), (float) i);
      Assert.assertEquals(
          loadedElement.<List<Boolean>>getProperty("flagSimple").get(i), (i % 2 == 0));
      cal.set(Calendar.DAY_OF_MONTH, (i + 1));
      Assert.assertEquals(loadedElement.<List<Date>>getProperty("dateField").get(i), cal.getTime());
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
    Assert.assertNotNull(loadedElement.getProperty("text"));
    Assert.assertNotNull(loadedElement.getProperty("numberSimple"));
    Assert.assertNotNull(loadedElement.getProperty("longSimple"));
    Assert.assertNotNull(loadedElement.getProperty("doubleSimple"));
    Assert.assertNotNull(loadedElement.getProperty("floatSimple"));
    Assert.assertNotNull(loadedElement.getProperty("flagSimple"));
    Assert.assertNotNull(loadedElement.getProperty("dateField"));

    Assert.assertEquals(loadedElement.<List<String>>getProperty("text").size(), 10);
    Assert.assertEquals(loadedElement.<List<Integer>>getProperty("numberSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Long>>getProperty("longSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Double>>getProperty("doubleSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Float>>getProperty("floatSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Boolean>>getProperty("flagSimple").size(), 10);
    Assert.assertEquals(loadedElement.<List<Date>>getProperty("dateField").size(), 10);

    for (var i = 0; i < 10; i++) {
      var j = i + 10;
      Assert.assertEquals(loadedElement.<List<String>>getProperty("text").get(i), j + "");
      Assert.assertEquals(loadedElement.<List<Integer>>getProperty("numberSimple").get(i), j);
      Assert.assertEquals(loadedElement.<List<Long>>getProperty("longSimple").get(i), j);
      Assert.assertEquals(loadedElement.<List<Double>>getProperty("doubleSimple").get(i), j);
      Assert.assertEquals(loadedElement.<List<Float>>getProperty("floatSimple").get(i), (float) j);
      Assert.assertEquals(
          loadedElement.<List<Boolean>>getProperty("flagSimple").get(i), (j % 2 == 0));

      cal.set(Calendar.DAY_OF_MONTH, (j + 1));
      Assert.assertEquals(loadedElement.<List<Date>>getProperty("dateField").get(i), cal.getTime());
    }

    session.commit();
    session.close();

    session = createSessionInstance();

    session.begin();
    loadedElement = session.load(id);

    Assert.assertTrue(
        ((Collection<?>) loadedElement.getProperty("text")).iterator().next() instanceof String);
    Assert.assertTrue(
        ((Collection<?>) loadedElement.getProperty("numberSimple")).iterator().next()
            instanceof Integer);
    Assert.assertTrue(
        ((Collection<?>) loadedElement.getProperty("longSimple")).iterator().next()
            instanceof Long);
    Assert.assertTrue(
        ((Collection<?>) loadedElement.getProperty("doubleSimple")).iterator().next()
            instanceof Double);
    Assert.assertTrue(
        ((Collection<?>) loadedElement.getProperty("floatSimple")).iterator().next()
            instanceof Float);
    Assert.assertTrue(
        ((Collection<?>) loadedElement.getProperty("flagSimple")).iterator().next()
            instanceof Boolean);
    Assert.assertTrue(
        ((Collection<?>) loadedElement.getProperty("dateField")).iterator().next() instanceof Date);

    session.delete(session.load(id));
    session.commit();
  }

  @Test(dependsOnMethods = "testSimpleTypes")
  public void testBinaryDataType() {
    session.begin();
    var element = session.newInstance("JavaBinaryTestClass");
    var bytes = new byte[10];
    for (var i = 0; i < 10; i++) {
      bytes[i] = (byte) i;
    }

    element.setProperty("binaryData", bytes);

    var fieldName = "binaryData";
    Assert.assertNotNull(element.getProperty(fieldName));

    session.commit();

    var id = element.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loadedElement = session.load(id);
    Assert.assertNotNull(loadedElement.getProperty(fieldName));

    Assert.assertEquals(loadedElement.<byte[]>getProperty("binaryData").length, 10);
    Assert.assertEquals(loadedElement.getProperty("binaryData"), bytes);

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
    Assert.assertNotNull(loadedElement.getProperty(fieldName));

    Assert.assertEquals(loadedElement.<byte[]>getProperty("binaryData").length, 10);
    Assert.assertEquals(loadedElement.getProperty("binaryData"), bytes);

    session.commit();
    session.close();

    session = createSessionInstance();

    session.begin();
    session.delete(session.load(id));
    session.commit();
  }

  @Test(dependsOnMethods = "testSimpleArrayTypes")
  public void collectionsDocumentTypeTestPhaseOne() {
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

    a = a;
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

  @Test(dependsOnMethods = "collectionsDocumentTypeTestPhaseOne")
  public void collectionsDocumentTypeTestPhaseTwo() {
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

    testLoadedEntity = testLoadedEntity;
    session.commit();

    session.freeze(false);
    session.release();

    session.begin();
    var activeTx = session.getActiveTransaction();
    checkCollectionImplementations(activeTx.load(testLoadedEntity));
    session.commit();
  }

  @Test(dependsOnMethods = "collectionsDocumentTypeTestPhaseTwo")
  public void collectionsDocumentTypeTestPhaseThree() {
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
    a = a;
    session.commit();

    var rid = a.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    var agendas = executeQuery("SELECT FROM " + rid);
    var testLoadedEntity = agendas.getFirst().asEntity();
    checkCollectionImplementations(testLoadedEntity);

    testLoadedEntity = testLoadedEntity;
    session.commit();

    session.freeze(false);
    session.release();

    session.begin();
    var activeTx = session.getActiveTransaction();
    checkCollectionImplementations(activeTx.load(testLoadedEntity));
    session.rollback();
  }

  protected static void checkCollectionImplementations(Entity doc) {
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

  @Test(dependsOnMethods = "testSimpleTypes")
  public void testDateInTransaction() {
    session.begin();
    var element = session.newEntity("JavaSimpleTestClass");
    var date = new Date();
    element.setProperty("dateField", date);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    element = activeTx.load(element);
    Assert.assertEquals(element.<List<Date>>getProperty("dateField"), date);
    session.commit();
  }

  @Test(dependsOnMethods = "testCreateClass")
  public void readAndBrowseDescendingAndCheckHoleUtilization() {
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
      Assert.assertTrue(ids.remove(id));

      Assert.assertEquals(a.<Integer>getProperty("id"), id);
      Assert.assertEquals(a.getProperty("name"), "Bill");
      Assert.assertEquals(a.getProperty("surname"), "Gates");
      Assert.assertEquals(a.<Float>getProperty("salary"), id + 300.1f);
      Assert.assertEquals(a.<List<Identifiable>>getProperty("addresses").size(), 1);
      Identifiable identifiable2 = a.<List<Identifiable>>getProperty("addresses")
          .getFirst();
      var transaction3 = session.getActiveTransaction();
      Assert.assertEquals(
          transaction3.<Entity>load(identifiable2)
              .getEntity("city")
              .getProperty("name"),
          rome.<String>getProperty("name"));
      var transaction = session.getActiveTransaction();
      Identifiable identifiable = transaction.<Entity>load(rome)
          .getProperty("country");
      var transaction1 = session.getActiveTransaction();
      Identifiable identifiable1 = a.<List<Identifiable>>getProperty("addresses")
          .getFirst();
      var transaction2 = session.getActiveTransaction();
      Assert.assertEquals(
          transaction2.<Entity>load(identifiable1)
              .getEntity("city")
              .getEntity("country")
              .getProperty("name"),
          transaction1.<Entity>load(identifiable)
              .<String>getProperty("name"));
    }

    Assert.assertTrue(ids.isEmpty());
    session.commit();
  }

  @Test(dependsOnMethods = "readAndBrowseDescendingAndCheckHoleUtilization")
  public void mapEnumAndInternalObjects() {
    session.executeInTxBatches(session.browseClass("OUser"),
        ((session, document) -> {

        }));

  }

  @Test(dependsOnMethods = "mapEnumAndInternalObjects")
  public void mapObjectsLinkTest() {
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
    Assert.assertFalse(cresult.isEmpty());

    var rid = p.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    var loaded = session.<Entity>load(rid);

    list = loaded.getProperty("list");
    Assert.assertEquals(list.size(), 4);
    var transaction7 = session.getActiveTransaction();
    Assert.assertEquals(
        Objects.requireNonNull(transaction7.<Entity>load(list.get(0)).getSchemaClass()).getName(
        ),
        "Child");
    var transaction6 = session.getActiveTransaction();
    Assert.assertEquals(
        Objects.requireNonNull(transaction6.<Entity>load(list.get(1)).getSchemaClass()).getName(
        ),
        "Child");
    var transaction5 = session.getActiveTransaction();
    Assert.assertEquals(
        Objects.requireNonNull(transaction5.<Entity>load(list.get(2)).getSchemaClass()).getName(
        ),
        "Child");
    var transaction4 = session.getActiveTransaction();
    Assert.assertEquals(
        Objects.requireNonNull(transaction4.<Entity>load(list.get(3)).getSchemaClass()).getName(
        ),
        "Child");
    var transaction3 = session.getActiveTransaction();
    Assert.assertEquals(transaction3.<Entity>load(list.get(0)).getProperty("name"), "Jack");
    var transaction2 = session.getActiveTransaction();
    Assert.assertEquals(transaction2.<Entity>load(list.get(1)).getProperty("name"), "Bob");
    var transaction1 = session.getActiveTransaction();
    Assert.assertEquals(transaction1.<Entity>load(list.get(2)).getProperty("name"), "Sam");
    var transaction = session.getActiveTransaction();
    Assert.assertEquals(transaction.<Entity>load(list.get(3)).getProperty("name"), "Dean");
    session.commit();
  }

  @Test(dependsOnMethods = "mapObjectsLinkTest")
  public void listObjectsLinkTest() {
    session.begin();
    var hanSolo = session.newInstance("PersonTest");
    hanSolo.setProperty("firstName", "Han");
    hanSolo = hanSolo;
    session.commit();

    session.begin();
    var obiWan = session.newInstance("PersonTest");
    obiWan.setProperty("firstName", "Obi-Wan");
    obiWan = obiWan;

    var luke = session.newInstance("PersonTest");
    luke.setProperty("firstName", "Luke");
    luke = luke;
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
    Assert.assertEquals(luke.<Set<Identifiable>>getProperty("friends").size(), 1);
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
    Assert.assertEquals(luke.<Set<Identifiable>>getProperty("friends").size(), 1);
    session.commit();
    // ============================== end 2
  }

  @Test(dependsOnMethods = "listObjectsLinkTest")
  public void listObjectsIterationTest() {
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
    for (var e : agenda.<List<?>>getProperty("events")) {
      // NO NEED TO DO ANYTHING, JUST NEED TO ITERATE THE LIST
    }

    agenda = agenda;
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

  @Test(dependsOnMethods = "listObjectsIterationTest")
  public void mapObjectsListEmbeddedTest() {
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

    Assert.assertEquals(childSize, cresult.size());

    var rid = p.getIdentity();
    session.commit();
    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    Assert.assertEquals(loaded.<List<Entity>>getProperty("embeddedList").size(), 4);
    Assert.assertTrue(loaded.<List<Entity>>getProperty("embeddedList").get(0).isEmbedded());
    Assert.assertTrue(loaded.<List<Entity>>getProperty("embeddedList").get(1).isEmbedded());
    Assert.assertTrue(loaded.<List<Entity>>getProperty("embeddedList").get(2).isEmbedded());
    Assert.assertTrue(loaded.<List<Entity>>getProperty("embeddedList").get(3).isEmbedded());
    var transaction3 = session.getActiveTransaction();
    Assert.assertEquals(
        Objects.requireNonNull(
                transaction3.<Entity>load(loaded
                        .<List<Entity>>getProperty("embeddedList")
                        .get(0))
                    .getSchemaClass())
            .getName(),
        "EmbeddedChild");
    var transaction2 = session.getActiveTransaction();
    Assert.assertEquals(
        Objects.requireNonNull(
                transaction2.<Entity>load(loaded
                        .<List<Entity>>getProperty("embeddedList")
                        .get(1))
                    .getSchemaClass())
            .getName(),
        "EmbeddedChild");
    var transaction1 = session.getActiveTransaction();
    Assert.assertEquals(
        Objects.requireNonNull(
                transaction1.loadEntity(loaded
                        .<List<Entity>>getProperty("embeddedList")
                        .get(2))
                    .getSchemaClass())
            .getName(),
        "EmbeddedChild");
    var transaction = session.getActiveTransaction();
    Assert.assertEquals(
        Objects.requireNonNull(
                transaction.loadEntity(loaded
                        .<List<Entity>>getProperty("embeddedList")
                        .get(3))
                    .getSchemaClass())
            .getName(),
        "EmbeddedChild");
    Assert.assertEquals(
        loaded.<List<Entity>>getProperty("embeddedList").get(0).getProperty("name"), "Jack");
    Assert.assertEquals(
        loaded.<List<Entity>>getProperty("embeddedList").get(1).getProperty("name"), "Bob");
    Assert.assertEquals(
        loaded.<List<Entity>>getProperty("embeddedList").get(2).getProperty("name"), "Sam");
    Assert.assertEquals(
        loaded.<List<Entity>>getProperty("embeddedList").get(3).getProperty("name"), "Dean");
    session.commit();
  }

  @Test(dependsOnMethods = "mapObjectsListEmbeddedTest")
  public void mapObjectsSetEmbeddedTest() {
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

    Assert.assertEquals(childSize, cresult.size());

    var rid = p.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    Assert.assertEquals(loaded.<Set<Entity>>getProperty("embeddedSet").size(), 5);
    for (var loadedC : loaded.<Set<Entity>>getProperty("embeddedSet")) {
      Assert.assertTrue(loadedC.isEmbedded());
      Assert.assertEquals(loadedC.getSchemaClassName(), "EmbeddedChild");
      Assert.assertTrue(
          loadedC.<String>getProperty("name").equals("John")
              || loadedC.<String>getProperty("name").equals("Jack")
              || loadedC.<String>getProperty("name").equals("Bob")
              || loadedC.<String>getProperty("name").equals("Sam")
              || loadedC.<String>getProperty("name").equals("Dean"));
    }
    session.commit();
  }

  @Test(dependsOnMethods = "mapObjectsSetEmbeddedTest")
  public void mapObjectsMapEmbeddedTest() {
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

    Assert.assertEquals(childSize, cresult.size());

    var rid = p.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);

    Assert.assertEquals(loaded.<Map<String, Entity>>getProperty("embeddedChildren").size(), 5);
    for (var key : loaded.<Map<String, Entity>>getProperty("embeddedChildren").keySet()) {
      var loadedC = loaded.<Map<String, Entity>>getProperty("embeddedChildren").get(key);
      Assert.assertTrue(loadedC.isEmbedded());
      Assert.assertEquals(loadedC.getSchemaClassName(), "EmbeddedChild");
      Assert.assertTrue(
          loadedC.<String>getProperty("name").equals("John")
              || loadedC.<String>getProperty("name").equals("Jack")
              || loadedC.<String>getProperty("name").equals("Bob")
              || loadedC.<String>getProperty("name").equals("Sam")
              || loadedC.<String>getProperty("name").equals("Dean"));
    }
    session.commit();
  }

  @Test(dependsOnMethods = "mapObjectsLinkTest")
  public void mapObjectsNonExistingKeyTest() {
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
    Assert.assertFalse(cresult.isEmpty());

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
    Assert.assertEquals(
        transaction3.loadEntity(identifiable3)
            .getProperty("name"),
        c1.<String>getProperty("name"));
    Identifiable identifiable2 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("second");
    var transaction2 = session.getActiveTransaction();
    Assert.assertEquals(
        transaction2.loadEntity(identifiable2)
            .getProperty("name"),
        c2.<String>getProperty("name"));
    Identifiable identifiable1 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("third");
    var transaction1 = session.getActiveTransaction();
    Assert.assertEquals(
        transaction1.loadEntity(identifiable1)
            .getProperty("name"),
        c3.<String>getProperty("name"));
    Identifiable identifiable = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("fourth");
    var transaction = session.getActiveTransaction();
    Assert.assertEquals(
        transaction.loadEntity(identifiable)
            .getProperty("name"),
        c4.<String>getProperty("name"));
    Assert.assertNull(loaded.<Map<String, Identifiable>>getProperty("children").get("fifth"));
    session.commit();
  }

  @Test(dependsOnMethods = "mapObjectsLinkTest")
  public void mapObjectsLinkTwoSaveTest() {
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
    Assert.assertFalse(cresult.isEmpty());

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
    Assert.assertEquals(
        transaction3.loadEntity(identifiable3)
            .getProperty("name"),
        c1.<String>getProperty("name"));
    Identifiable identifiable2 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("second");
    var transaction2 = session.getActiveTransaction();
    Assert.assertEquals(
        transaction2.loadEntity(identifiable2)
            .getProperty("name"),
        c2.<String>getProperty("name"));
    Identifiable identifiable1 = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("third");
    var transaction1 = session.getActiveTransaction();
    Assert.assertEquals(
        transaction1.loadEntity(identifiable1)
            .getProperty("name"),
        c3.<String>getProperty("name"));
    Identifiable identifiable = loaded
        .<Map<String, Identifiable>>getProperty("children")
        .get("fourth");
    var transaction = session.getActiveTransaction();
    Assert.assertEquals(
        transaction.loadEntity(identifiable)
            .getProperty("name"),
        c4.<String>getProperty("name"));
    session.commit();
  }

  @Test(dependsOnMethods = "mapObjectsLinkTest")
  public void mapObjectsLinkUpdateDatabaseNewInstanceTest() {
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
      Assert.assertTrue(
          key.equals("Peter")
              || key.equals("Walter")
              || key.equals("Olivia")
              || key.equals("Astrid"));
      Identifiable identifiable2 = loaded
          .<Map<String, Identifiable>>getProperty("children")
          .get(key);
      var transaction2 = session.getActiveTransaction();
      Assert.assertEquals(
          transaction2.loadEntity(identifiable2)
              .getSchemaClassName(),
          "Child");
      Identifiable identifiable1 = loaded
          .<Map<String, Identifiable>>getProperty("children")
          .get(key);
      var transaction1 = session.getActiveTransaction();
      Assert.assertEquals(
          key,
          transaction1.loadEntity(identifiable1)
              .getProperty("name"));
      switch (key) {
        case "Peter" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          Assert.assertEquals(
              transaction.loadEntity(identifiable)
                  .getProperty("name"),
              "Peter");
        }
        case "Walter" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          Assert.assertEquals(
              transaction.loadEntity(identifiable)
                  .getProperty("name"),
              "Walter");
        }
        case "Olivia" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          Assert.assertEquals(
              transaction.loadEntity(identifiable)
                  .getProperty("name"),
              "Olivia");
        }
        case "Astrid" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          Assert.assertEquals(
              transaction.loadEntity(identifiable)
                  .getProperty("name"),
              "Astrid");
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
      Assert.assertTrue(
          reloaded.<Map<String, Identifiable>>getProperty("children")
              .containsKey("The Observer"));
      Assert.assertNotNull(
          reloaded.<Map<String, Identifiable>>getProperty("children").get("The Observer"));
      Identifiable identifiable = reloaded
          .<Map<String, Identifiable>>getProperty("children")
          .get("The Observer");
      var transaction = session.getActiveTransaction();
      Assert.assertEquals(
          transaction.loadEntity(identifiable)
              .getProperty("name"),
          "The Observer");
      Assert.assertTrue(
          reloaded
              .<Map<String, Identifiable>>getProperty("children")
              .get("The Observer")
              .getIdentity()
              .isPersistent()
              && ((RecordId) reloaded
              .<Map<String, Identifiable>>getProperty("children")
              .get("The Observer")
              .getIdentity())
              .isValidPosition());
    }
    session.commit();
  }

  @Test(dependsOnMethods = "mapObjectsLinkUpdateDatabaseNewInstanceTest")
  public void mapObjectsLinkUpdateJavaNewInstanceTest() {
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
      Assert.assertTrue(
          key.equals("Peter")
              || key.equals("Walter")
              || key.equals("Olivia")
              || key.equals("Astrid"));
      Identifiable identifiable2 = loaded
          .<Map<String, Identifiable>>getProperty("children")
          .get(key);
      var transaction2 = session.getActiveTransaction();
      Assert.assertEquals(
          transaction2.loadEntity(identifiable2)
              .getSchemaClassName(),
          "Child");
      Identifiable identifiable1 = loaded
          .<Map<String, Identifiable>>getProperty("children")
          .get(key);
      var transaction1 = session.getActiveTransaction();
      Assert.assertEquals(
          key,
          transaction1.loadEntity(identifiable1)
              .getProperty("name"));
      switch (key) {
        case "Peter" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          Assert.assertEquals(
              transaction.loadEntity(identifiable)
                  .getProperty("name"),
              "Peter");
        }
        case "Walter" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          Assert.assertEquals(
              transaction.loadEntity(identifiable)
                  .getProperty("name"),
              "Walter");
        }
        case "Olivia" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          Assert.assertEquals(
              transaction.loadEntity(identifiable)
                  .getProperty("name"),
              "Olivia");
        }
        case "Astrid" -> {
          Identifiable identifiable = loaded
              .<Map<String, Identifiable>>getProperty("children")
              .get(key);
          var transaction = session.getActiveTransaction();
          Assert.assertEquals(
              transaction.loadEntity(identifiable)
                  .getProperty("name"),
              "Astrid");
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
      Assert.assertTrue(
          reloaded.<Map<String, Identifiable>>getProperty("children")
              .containsKey("The Observer"));
      Assert.assertNotNull(
          reloaded.<Map<String, Identifiable>>getProperty("children").get("The Observer"));
      Identifiable identifiable = reloaded
          .<Map<String, Identifiable>>getProperty("children")
          .get("The Observer");
      var transaction = session.getActiveTransaction();
      Assert.assertEquals(
          transaction.loadEntity(identifiable)
              .getProperty("name"),
          "The Observer");
      Assert.assertTrue(
          reloaded
              .<Map<String, Identifiable>>getProperty("children")
              .get("The Observer")
              .getIdentity()
              .isPersistent()
              && ((RecordId) reloaded
              .<Map<String, Identifiable>>getProperty("children")
              .get("The Observer")
              .getIdentity())
              .isValidPosition());
    }
    session.commit();
  }

  @Test(dependsOnMethods = "mapObjectsLinkUpdateJavaNewInstanceTest")
  public void mapStringTest() {
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
      Assert.assertEquals(
          entry.getValue(), p.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }

    session.commit();

    var rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    session.commit();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(), p.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(), p.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, String>>getProperty("stringMap"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, String>>getProperty("stringMap").get(entry.getKey()));
    }
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(loaded));
    session.commit();
  }

  @Test(dependsOnMethods = "mapStringTest")
  public void setStringTest() {
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
    Assert.assertEquals(roles.size(), testClassProxy.<Set<String>>getProperty("stringSet").size());
    for (var referenceRole : roles) {
      var activeTx = session.getActiveTransaction();
      testClassProxy = activeTx.load(testClassProxy);
      Assert.assertTrue(
          testClassProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }

    var orid = testClassProxy.getIdentity();
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    Entity loadedProxy = session.load(orid);
    Assert.assertEquals(roles.size(), loadedProxy.<Set<String>>getProperty("stringSet").size());
    for (var referenceRole : roles) {
      Assert.assertTrue(loadedProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }

    var activeTx3 = session.getActiveTransaction();
    activeTx3.load(loadedProxy);
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    loadedProxy = activeTx2.load(loadedProxy);
    Assert.assertEquals(roles.size(), loadedProxy.<Set<String>>getProperty("stringSet").size());
    for (var referenceRole : roles) {
      Assert.assertTrue(loadedProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }

    loadedProxy.<Set<String>>getProperty("stringSet").remove("developer");
    roles.remove("developer");
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    loadedProxy = activeTx1.load(loadedProxy);
    Assert.assertEquals(roles.size(), loadedProxy.<Set<String>>getProperty("stringSet").size());
    for (var referenceRole : roles) {
      Assert.assertTrue(loadedProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }
    session.commit();
    session.close();
    session = createSessionInstance();

    session.begin();
    var activeTx = session.getActiveTransaction();
    loadedProxy = activeTx.load(loadedProxy);
    Assert.assertEquals(roles.size(), loadedProxy.<Set<String>>getProperty("stringSet").size());
    for (var referenceRole : roles) {
      Assert.assertTrue(loadedProxy.<Set<String>>getProperty("stringSet").contains(referenceRole));
    }
    session.commit();
  }

  @Test(dependsOnMethods = "setStringTest")
  public void mapStringListTest() {
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
                e -> session.newEmbeddedList(e.getValue())
            ))
    ));

    for (var entry : songAndMovies.entrySet()) {
      Assert.assertEquals(
          entry.getValue(),
          p.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    session.commit();

    var rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    Entity loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, List<String>>>getProperty("stringListMap"));
    for (var entry : songAndMovies.entrySet()) {
      Assert.assertEquals(
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
                e -> session.newEmbeddedList(e.getValue())
            ))
    ));

    for (var entry : songAndMovies.entrySet()) {
      Assert.assertEquals(
          entry.getValue(),
          p.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, List<String>>>getProperty("stringListMap"));
    for (var entry : songAndMovies.entrySet()) {
      Assert.assertEquals(
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
                e -> session.newEmbeddedList(e.getValue())
            ))
    ));

    for (var entry : songAndMovies.entrySet()) {
      Assert.assertEquals(
          entry.getValue(),
          p.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, List<String>>>getProperty("stringListMap"));
    for (var entry : songAndMovies.entrySet()) {
      Assert.assertEquals(
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
                e -> session.newEmbeddedList(e.getValue())
            ))
    ));

    for (var entry : songAndMovies.entrySet()) {
      Assert.assertEquals(
          entry.getValue(),
          p.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, List<String>>>getProperty("stringListMap"));
    for (var entry : songAndMovies.entrySet()) {
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, List<String>>>getProperty("stringListMap").get(entry.getKey()));
    }

    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(loaded));
    session.commit();
  }

  @Test
  public void embeddedMapObjectTest() {
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
        "date", cal.getTime()
    );

    p.setProperty("mapObject", session.newEmbeddedMap(mapObject));

    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
          entry.getValue(), p.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    session.commit();

    var rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    Entity loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }
    session.commit();

    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(), p.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }
    session.commit();

    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(), p.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    p = p;
    session.commit();

    rid = p.getIdentity();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);
    Assert.assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
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
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }
    session.commit();
    session.close();
    session = createSessionInstance();
    session.begin();
    loaded = session.load(rid);

    Assert.assertNotNull(loaded.<Map<String, Object>>getProperty("mapObject"));
    for (var entry : relatives.entrySet()) {
      Assert.assertEquals(
          entry.getValue(),
          loaded.<Map<String, Object>>getProperty("mapObject").get(entry.getKey()));
    }

    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(loaded));
    session.commit();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test(dependsOnMethods = "embeddedMapObjectTest")
  public void testNoGenericCollections() {
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

    Assert.assertEquals(p.<List>getProperty("list").size(), 4);
    Assert.assertEquals(p.<Set>getProperty("set").size(), 4);
    Assert.assertEquals(p.<Map>getProperty("map").size(), 4);
    for (var i = 0; i < 4; i++) {
      var transaction1 = session.getActiveTransaction();
      var o = transaction1.loadEntity(p.getLinkList("list").get(i));
      Assert.assertEquals(o.getProperty("name"), (i + 1) + "");
      Identifiable identifiable = p.getLinkMap("map").get((i + 1) + "");
      var transaction = session.getActiveTransaction();
      o = transaction.loadEntity(identifiable);
      Assert.assertEquals(o.getProperty("name"), (i + 1) + "");
    }
    for (var r : p.getLinkSet("set")) {
      var transaction = session.getActiveTransaction();
      var o = transaction.loadEntity(r);
      var nameToInt = Integer.parseInt(o.getProperty("name"));
      Assert.assertTrue(nameToInt > 0 && nameToInt < 5);
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
    Assert.assertEquals(p.getLinkList("list").size(), 5);
    Assert.assertEquals(p.getLinkSet("set").size(), 5);
    Assert.assertEquals(p.getLinkMap("map").size(), 5);
    session.commit();
  }

  public void oidentifableFieldsTest() {
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

    Assert.assertNotNull(loaded.getBlob("byteArray"));
    try {
      try (var out = new ByteArrayOutputStream()) {
        loaded.getBlob("byteArray").toOutputStream(out);
        Assert.assertEquals(
            "this is a bytearray test. if you read this Object database has stored it correctly"
                .getBytes(),
            out.toByteArray());
        Assert.assertEquals(
            out.toString(),
            "this is a bytearray test. if you read this Object database has stored it correctly");
      }
    } catch (IOException ioe) {
      fail();
      LogManager.instance().error(this, "Error reading byte[]", ioe);
    }
    Assert.assertTrue(loaded.getEntity("document") instanceof EntityImpl);
    Assert.assertEquals(
        loaded.getEntity("document").getProperty("testField"), "testValue");
    Assert.assertTrue(loaded.getEntity("document").getIdentity().isPersistent());

    Assert.assertTrue(loaded.getEntity("embeddedDocument") instanceof EntityImpl);
    Assert.assertEquals(
        loaded.getEntity("embeddedDocument").getProperty("testEmbeddedField"),
        "testEmbeddedValue");
    Assert.assertFalse(
        ((RecordId) loaded.getEntity("embeddedDocument").getIdentity()).isValidPosition());

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

    p = p;
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    p = activeTx1.load(p);
    Assert.assertNotNull(p.getBlob("byteArray"));
    try {
      try (var out = new ByteArrayOutputStream()) {
        p.getBlob("byteArray").toOutputStream(out);
        Assert.assertEquals(
            "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
                .getBytes(),
            out.toByteArray());
        Assert.assertEquals(
            out.toString(),
            "this is a bytearray test. if you read this Object database has stored it"
                + " correctlyVERSION2");
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

    Assert.assertNotNull(loaded.getBlob("byteArray"));
    try {
      try (var out = new ByteArrayOutputStream()) {
        loaded.getBlob("byteArray").toOutputStream(out);
        Assert.assertEquals(
            "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
                .getBytes(),
            out.toByteArray());
        Assert.assertEquals(
            out.toString(),
            "this is a bytearray test. if you read this Object database has stored it"
                + " correctlyVERSION2");
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

    p = p;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    p = activeTx.load(p);
    Assert.assertNotNull(p.getBlob("byteArray"));
    try {
      try (var out = new ByteArrayOutputStream()) {
        p.getBlob("byteArray").toOutputStream(out);
        Assert.assertEquals(
            "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
                .getBytes(),
            out.toByteArray());
        Assert.assertEquals(
            out.toString(),
            "this is a bytearray test. if you read this Object database has stored it"
                + " correctlyVERSION2");
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
        Assert.assertEquals(
            "this is a bytearray test. if you read this Object database has stored it correctlyVERSION2"
                .getBytes(),
            out.toByteArray());
        Assert.assertEquals(
            out.toString(),
            "this is a bytearray test. if you read this Object database has stored it"
                + " correctlyVERSION2");
      }
    } catch (IOException ioe) {
      fail();
      LogManager.instance().error(this, "Error reading byte[]", ioe);
    }
    session.commit();
  }

  @Test
  public void testObjectDelete() {
    session.begin();
    var media = session.newEntity("Media");
    var testRecord = session.newBlob("This is a test".getBytes());

    media.setProperty("content", testRecord);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    media = activeTx1.load(media);
    Assert.assertEquals(new String(media.getBlob("content").toStream()), "This is a test");

    // try to delete
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(media));
    session.commit();
  }

  @Test(dependsOnMethods = "mapEnumAndInternalObjects")
  public void update() {
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

  @Test(dependsOnMethods = "update")
  public void testUpdate() {
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
            transaction.<Entity>load(identifiable)
                .getProperty("name"),
            "Spain");
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
            transaction1.<Entity>load(identifiable1)
                .getProperty("name"),
            "Italy");
      }

      Assert.assertEquals(a.<Float>getProperty("salary"), i + 500.1f);

      i++;
    }
    session.commit();
  }

  @Test(dependsOnMethods = "testUpdate")
  public void checkLazyLoadingOff() {
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
    Assert.assertEquals(session.countClass("Profile"), profiles + 3);

    var entityIterator = session.browseClass("Profile");

    while (entityIterator.hasNext()) {
      var obj = entityIterator.next();
      var followersList = obj.<Set<Identifiable>>getProperty("followers");
      Assert.assertTrue(followersList == null || followersList instanceof EntityLinkSetImpl);
      if (obj.<String>getProperty("nick").equals("Neo")) {
        Assert.assertEquals(obj.<Set<Identifiable>>getProperty("followers").size(), 2);
        Identifiable identifiable = obj.<Set<Identifiable>>getProperty("followers")
            .iterator()
            .next();
        var transaction = session.getActiveTransaction();
        Assert.assertEquals(
            transaction.loadEntity(identifiable)
                .getSchemaClassName(),
            "Profile");
      } else if (obj.<String>getProperty("nick").equals("Morpheus")
          || obj.<String>getProperty("nick").equals("Trinity")) {
        Assert.assertNull(obj.<Set<Identifiable>>getProperty("followers"));
      }
    }
    session.commit();
  }

  @Test(dependsOnMethods = "checkLazyLoadingOff")
  public void queryPerFloat() {
    session.begin();
    var resultSet = executeQuery("select * from Account where salary = 500.10");

    Assert.assertFalse(resultSet.isEmpty());

    Entity account;
    for (var entries : resultSet) {
      account = entries.asEntityOrNull();
      Assert.assertEquals(account.<Float>getProperty("salary"), 500.10f);
    }
    session.commit();
  }

  @Test(dependsOnMethods = "checkLazyLoadingOff")
  public void queryCross3Levels() {
    session.begin();
    var resultSet =
        executeQuery("select from Profile where location.city.country.name = 'Spain'");

    Assert.assertFalse(resultSet.isEmpty());

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
      Assert.assertEquals(
          transaction2.<Entity>load(identifiable2)
              .getProperty("name"),
          "Spain");
    }
    session.commit();
  }

  @Test(dependsOnMethods = "queryCross3Levels")
  public void deleteFirst() {
    startRecordNumber = session.countClass("Account");

    // DELETE ALL THE RECORD IN THE CLASS
    session.forEachInTx(session.browseClass("Account"),
        ((session, document) -> {
          session.delete(document);
          return false;
        }));

    Assert.assertEquals(session.countClass("Account"), startRecordNumber - 1);
  }

  @Test
  public void commandWithPositionalParameters() {
    session.begin();
    var resultSet =
        executeQuery("select from Profile where name = ? and surname = ?", "Barack", "Obama");

    Assert.assertFalse(resultSet.isEmpty());
    session.commit();
  }

  @Test
  public void queryWithPositionalParameters() {
    session.begin();
    var resultSet =
        executeQuery("select from Profile where name = ? and surname = ?", "Barack", "Obama");

    Assert.assertFalse(resultSet.isEmpty());
    session.commit();
  }

  @Test
  public void queryWithRidAsParameters() {
    addGaribaldiAndBonaparte();
    session.begin();
    Entity profile = session.browseClass("Profile").next();
    var resultSet =
        executeQuery("select from Profile where @rid = ?", profile.getIdentity());

    Assert.assertEquals(resultSet.size(), 1);
    session.commit();
  }

  @Test
  public void queryWithRidStringAsParameters() {
    addBarackObamaAndFollowers();
    session.begin();
    Entity profile = session.browseClass("Profile").next();
    var resultSet =
        executeQuery("select from Profile where @rid = ?", profile.getIdentity());

    Assert.assertEquals(resultSet.size(), 1);
    session.commit();
  }

  @Test
  public void commandWithNamedParameters() {
    addBarackObamaAndFollowers();

    var params = new HashMap<String, String>();
    params.put("name", "Barack");
    params.put("surname", "Obama");

    session.begin();
    var resultSet =
        executeQuery("select from Profile where name = :name and surname = :surname", params);
    Assert.assertFalse(resultSet.isEmpty());
    session.commit();
  }

  @Test
  public void commandWithWrongNamedParameters() {
    try {
      var params = new HashMap<String, String>();
      params.put("name", "Barack");
      params.put("surname", "Obama");

      executeQuery("select from Profile where name = :name and surname = :surname%", params);
      fail();
    } catch (CommandSQLParsingException e) {
      Assert.assertTrue(true);
    }
  }

  @Test
  public void queryConcatAttrib() {
    session.begin();
    Assert.assertFalse(executeQuery("select from City where country.@class = 'Country'").isEmpty());
    Assert.assertEquals(
        executeQuery("select from City where country.@class = 'Country22'").size(), 0);
    session.commit();
  }

  @Test
  public void queryPreparedTwice() {
    try (var db = acquireSession()) {
      db.begin();

      var params = new HashMap<String, String>();
      params.put("name", "Barack");
      params.put("surname", "Obama");

      var result =
          db.query("select from Profile where name = :name and surname = :surname", params)
              .entityStream()
              .toList();
      Assert.assertFalse(result.isEmpty());

      result =
          db.query("select from Profile where name = :name and surname = :surname", params)
              .entityStream()
              .toList();
      Assert.assertFalse(result.isEmpty());
      db.commit();
    }
  }

  @Test(dependsOnMethods = "oidentifableFieldsTest")
  public void testEmbeddedDeletion() {
    session.begin();
    var parent = session.newInstance("Parent");
    parent.setProperty("name", "Big Parent");

    var embedded = session.newEmbeddedEntity("EmbeddedChild");
    embedded.setProperty("name", "Little Child");

    parent.setProperty("embeddedChild", embedded);

    parent = parent;

    var presult = executeQuery("select from Parent");
    var cresult = executeQuery("select from EmbeddedChild");
    Assert.assertEquals(presult.size(), 1);
    Assert.assertEquals(cresult.size(), 0);

    var child = session.newEmbeddedEntity("EmbeddedChild");
    child.setProperty("name", "Little Child");
    parent.setProperty("child", child);

    parent = parent;
    session.commit();

    session.begin();
    presult = executeQuery("select from Parent");
    cresult = executeQuery("select from EmbeddedChild");
    Assert.assertEquals(presult.size(), 1);
    Assert.assertEquals(cresult.size(), 0);

    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<EntityImpl>load(parent));
    session.commit();

    session.begin();
    presult = executeQuery("select * from Parent");
    cresult = executeQuery("select * from EmbeddedChild");

    Assert.assertEquals(presult.size(), 0);
    Assert.assertEquals(cresult.size(), 0);
    session.commit();
  }

  @Test(enabled = false, dependsOnMethods = "testCreate")
  public void testEmbeddedBinary() {
    var a = session.newEntity("Account");
    a.setProperty("name", "Chris");
    a.setProperty("surname", "Martin");
    a.setProperty("id", 0);
    a.setProperty("thumbnail", new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

    a = a;
    session.commit();

    session.close();

    session = createSessionInstance();
    Entity aa = session.load(a.getIdentity());
    Assert.assertNotNull(a.getProperty("thumbnail"));
    Assert.assertNotNull(aa.getProperty("thumbnail"));
    byte[] b = aa.getProperty("thumbnail");
    for (var i = 0; i < 10; ++i) {
      Assert.assertEquals(b[i], i);
    }
  }

  @Test
  public void queryById() {
    session.begin();
    var result1 = executeQuery("select from Profile limit 1");
    var result2 =
        executeQuery("select from Profile where @rid = ?", result1.getFirst().getIdentity());

    Assert.assertFalse(result2.isEmpty());
    session.commit();
  }

  @Test
  public void queryByIdNewApi() {
    session.begin();
    session.execute("insert into Profile set nick = 'foo', name='foo'").close();
    session.commit();

    session.begin();
    var result1 = executeQuery("select from Profile where nick = 'foo'");

    Assert.assertEquals(result1.size(), 1);
    Assert.assertEquals(result1.getFirst().asEntityOrNull().getSchemaClassName(), "Profile");
    var profile = result1.getFirst().asEntityOrNull();

    Assert.assertEquals(profile.getProperty("nick"), "foo");
    session.commit();
  }

  @Test(dependsOnMethods = "testUpdate")
  public void testSaveMultiCircular() {
    session = createSessionInstance();
    try {
      startRecordNumber = session.countClusterElements("Profile");
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
