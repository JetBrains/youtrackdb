package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class DocumentValidationTest extends BaseMemoryInternalDatabase {

  @Test
  public void testRequiredValidation() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    Identifiable id = ((DBRecord) doc).getIdentity();
    session.commit();

    var embeddedClazz = session.getMetadata().getSchema().createClass("EmbeddedValidation");
    embeddedClazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);

    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);
    clazz.createProperty(session, "long", PropertyType.LONG).setMandatory(session, true);
    clazz.createProperty(session, "float", PropertyType.FLOAT).setMandatory(session, true);
    clazz.createProperty(session, "boolean", PropertyType.BOOLEAN).setMandatory(session, true);
    clazz.createProperty(session, "binary", PropertyType.BINARY).setMandatory(session, true);
    clazz.createProperty(session, "byte", PropertyType.BYTE).setMandatory(session, true);
    clazz.createProperty(session, "date", PropertyType.DATE).setMandatory(session, true);
    clazz.createProperty(session, "datetime", PropertyType.DATETIME).setMandatory(session, true);
    clazz.createProperty(session, "decimal", PropertyType.DECIMAL).setMandatory(session, true);
    clazz.createProperty(session, "double", PropertyType.DOUBLE).setMandatory(session, true);
    clazz.createProperty(session, "short", PropertyType.SHORT).setMandatory(session, true);
    clazz.createProperty(session, "string", PropertyType.STRING).setMandatory(session, true);
    clazz.createProperty(session, "link", PropertyType.LINK).setMandatory(session, true);
    clazz.createProperty(session, "embedded", PropertyType.EMBEDDED, embeddedClazz)
        .setMandatory(session, true);

    clazz.createProperty(session, "embeddedListNoClass", PropertyType.EMBEDDEDLIST)
        .setMandatory(session, true);
    clazz.createProperty(session, "embeddedSetNoClass", PropertyType.EMBEDDEDSET).setMandatory(
        session, true);
    clazz.createProperty(session, "embeddedMapNoClass", PropertyType.EMBEDDEDMAP).setMandatory(
        session, true);

    clazz.createProperty(session, "embeddedList", PropertyType.EMBEDDEDLIST, embeddedClazz)
        .setMandatory(session, true);
    clazz.createProperty(session, "embeddedSet", PropertyType.EMBEDDEDSET, embeddedClazz)
        .setMandatory(session, true);
    clazz.createProperty(session, "embeddedMap", PropertyType.EMBEDDEDMAP, embeddedClazz)
        .setMandatory(session, true);

    clazz.createProperty(session, "linkList", PropertyType.LINKLIST).setMandatory(session, true);
    clazz.createProperty(session, "linkSet", PropertyType.LINKSET).setMandatory(session, true);
    clazz.createProperty(session, "linkMap", PropertyType.LINKMAP).setMandatory(session, true);

    session.begin();
    var entity = (EntityImpl) session.newEntity(clazz);
    entity.setInt("int", 10);
    entity.setLong("long", 10L);
    entity.setFloat("float", 10f);
    entity.setBoolean("boolean", true);
    entity.setBinary("binary", new byte[]{});
    entity.setByte("byte", (byte) 10);
    entity.setDate("date", new Date());
    entity.setDateTime("datetime", new Date());
    entity.setDecimal("decimal", BigDecimal.valueOf(10));
    entity.setDouble("double", 10d);
    entity.setShort("short", (short) 10);
    entity.setString("string", "yeah");
    entity.setLink("link", id);
    entity.newLinkList("linkList");
    entity.newLinkSet("linkSet");
    entity.newLinkMap("linkMap");

    entity.newEmbeddedList("embeddedListNoClass");
    entity.newEmbeddedSet("embeddedSetNoClass");
    entity.newEmbeddedMap("embeddedMapNoClass");

    var embedded = session.newEmbededEntity("EmbeddedValidation");
    embedded.setInt("int", 20);
    embedded.setLong("long", 20L);
    entity.setEmbeddedEntity("embedded", embedded);

    var embeddedInList = session.newEmbededEntity("EmbeddedValidation");
    embeddedInList.setInt("int", 30);
    embeddedInList.setLong("long", 30L);

    final var embeddedList = session.newEmbeddedList();
    embeddedList.add(embeddedInList);
    entity.setEmbeddedList("embeddedList", embeddedList);

    var embeddedInSet = session.newEmbededEntity("EmbeddedValidation");
    embeddedInSet.setInt("int", 30);
    embeddedInSet.setLong("long", 30L);
    var embeddedSet = session.newEmbeddedSet();
    embeddedSet.add(embeddedInSet);
    entity.setEmbeddedSet("embeddedSet", embeddedSet);

    var embeddedInMap = session.newEmbededEntity("EmbeddedValidation");
    embeddedInMap.setInt("int", 30);
    embeddedInMap.setLong("long", 30L);
    final Map<String, Entity> embeddedMap = session.newEmbeddedMap();
    embeddedMap.put("testEmbedded", embeddedInMap);
    entity.setEmbeddedMap("embeddedMap", embeddedMap);

    entity.validate();

    checkRequireField(entity, "int");
    checkRequireField(entity, "long");
    checkRequireField(entity, "float");
    checkRequireField(entity, "boolean");
    checkRequireField(entity, "binary");
    checkRequireField(entity, "byte");
    checkRequireField(entity, "date");
    checkRequireField(entity, "datetime");
    checkRequireField(entity, "decimal");
    checkRequireField(entity, "double");
    checkRequireField(entity, "short");
    checkRequireField(entity, "string");
    checkRequireField(entity, "link");
    checkRequireField(entity, "embedded");
    checkRequireField(entity, "embeddedList");
    checkRequireField(entity, "embeddedSet");
    checkRequireField(entity, "embeddedMap");
    checkRequireField(entity, "linkList");
    checkRequireField(entity, "linkSet");
    checkRequireField(entity, "linkMap");
    session.rollback();
  }

  @Test
  public void testValidationNotValidEmbedded() {
    var embeddedClazz = session.getMetadata().getSchema().createClass("EmbeddedValidation");
    embeddedClazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);

    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);
    clazz.createProperty(session, "long", PropertyType.LONG).setMandatory(session, true);
    clazz.createProperty(session, "embedded", PropertyType.EMBEDDED, embeddedClazz)
        .setMandatory(session, true);
    var clazzNotVertex = session.getMetadata().getSchema().createClass("NotVertex");
    clazzNotVertex.createProperty(session, "embeddedSimple", PropertyType.EMBEDDED);

    session.begin();
    var d = (EntityImpl) session.newEntity(clazz);
    d.field("int", 30);
    d.field("long", 30);
    d.field("embedded",
        ((EntityImpl) session.newEntity("EmbeddedValidation")).field("test", "test"));
    try {
      d.validate();
      fail("Validation doesn't throw exception");
    } catch (ValidationException e) {
      Assert.assertTrue(e.toString().contains("EmbeddedValidation.int"));
      session.rollback();
    }

    session.begin();
    d = (EntityImpl) session.newEntity(clazzNotVertex);
    checkField(d, "embeddedSimple", session.newVertex());
    checkField(d, "embeddedSimple",
        session.newStatefulEdge(session.newVertex(), session.newVertex()));
    session.rollback();
  }

  @Test
  public void testValidationNotValidEmbeddedSet() {
    var embeddedClazz = session.getMetadata().getSchema().createClass("EmbeddedValidation");
    embeddedClazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);
    embeddedClazz.createProperty(session, "long", PropertyType.LONG).setMandatory(session, true);

    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);
    clazz.createProperty(session, "long", PropertyType.LONG).setMandatory(session, true);
    clazz.createProperty(session, "embeddedSet", PropertyType.EMBEDDEDSET, embeddedClazz)
        .setMandatory(session, true);

    session.begin();
    var entity = session.newEntity(clazz);
    entity.setInt("int", 30);
    entity.setLong("long", 30L);
    final Set<Entity> embeddedSet = session.newEmbeddedSet();
    entity.setEmbeddedSet("embeddedSet", embeddedSet);

    var embeddedInSet = session.newEmbededEntity("EmbeddedValidation");
    embeddedInSet.setInt("int", 30);
    embeddedInSet.setLong("long", 30L);
    embeddedSet.add(embeddedInSet);

    var embeddedInSet2 = session.newEmbededEntity("EmbeddedValidation");
    embeddedInSet2.setInt("int", 30);
    embeddedSet.add(embeddedInSet2);

    try {
      ((EntityImpl) entity).validate();
      fail("Validation doesn't throw exception");
    } catch (ValidationException e) {
      session.rollback();
      Assert.assertTrue(e.toString().contains("EmbeddedValidation.long"));
    }
  }

  @Test
  public void testValidationNotValidEmbeddedList() {
    var embeddedClazz = session.getMetadata().getSchema().createClass("EmbeddedValidation");
    embeddedClazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);
    embeddedClazz.createProperty(session, "long", PropertyType.LONG).setMandatory(session, true);

    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);
    clazz.createProperty(session, "long", PropertyType.LONG).setMandatory(session, true);
    clazz.createProperty(session, "embeddedList", PropertyType.EMBEDDEDLIST, embeddedClazz)
        .setMandatory(session, true);

    session.begin();
    var entity = session.newEntity(clazz);
    entity.setInt("int", 30);
    entity.setLong("long", 30L);
    final var embeddedList = entity.newEmbeddedList("embeddedList");

    var embeddedInList = session.newEmbededEntity("EmbeddedValidation");
    embeddedInList.setInt("int", 30);
    embeddedInList.setLong("long", 30L);
    embeddedList.add(embeddedInList);

    var embeddedInList2 = session.newEmbededEntity("EmbeddedValidation");
    embeddedInList2.setInt("int", 30);
    embeddedList.add(embeddedInList2);

    try {
      ((EntityImpl) entity).validate();
      fail("Validation doesn't throw exception");
    } catch (ValidationException e) {
      session.rollback();
      Assert.assertTrue(e.toString().contains("EmbeddedValidation.long"));
    }
  }

  @Test
  public void testValidationNotValidEmbeddedMap() {
    var embeddedClazz = session.getMetadata().getSchema().createClass("EmbeddedValidation");
    embeddedClazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);
    embeddedClazz.createProperty(session, "long", PropertyType.LONG).setMandatory(session, true);

    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "int", PropertyType.INTEGER).setMandatory(session, true);
    clazz.createProperty(session, "long", PropertyType.LONG).setMandatory(session, true);
    clazz.createProperty(session, "embeddedMap", PropertyType.EMBEDDEDMAP, embeddedClazz)
        .setMandatory(session, true);

    session.begin();
    var entity = session.newEmbededEntity(clazz);
    entity.setInt("int", 30);
    entity.setLong("long", 30L);
    var embeddedMap = entity.newEmbeddedMap("embeddedMap");

    var embeddedInMap = session.newEmbededEntity("EmbeddedValidation");
    embeddedInMap.setInt("int", 30);
    embeddedInMap.setLong("long", 30L);
    embeddedMap.put("1", embeddedInMap);

    var embeddedInMap2 = session.newEmbededEntity("EmbeddedValidation");
    embeddedInMap2.setInt("int", 30);
    embeddedMap.put("2", embeddedInMap2);

    try {
      ((EntityImpl) entity).validate();
      fail("Validation doesn't throw exception");
    } catch (ValidationException e) {
      session.rollback();
      Assert.assertTrue(e.toString().contains("EmbeddedValidation.long"));
    }
  }

  private static void checkRequireField(Entity toCheck, String fieldName) {
    try {
      var session = toCheck.getBoundedToSession();
      var newD = (EntityImpl) session.newEntity(toCheck.getSchemaClass());

      newD.unsetDirty();
      newD.fromStream(((EntityImpl) toCheck).toStream());
      newD.removeField(fieldName);
      newD.validate();
      fail();
    } catch (ValidationException v) {
      //ignore
    }
  }

  @Test
  public void testMaxValidation() {
    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "int", PropertyType.INTEGER).setMax(session, "11");
    clazz.createProperty(session, "long", PropertyType.LONG).setMax(session, "11");
    clazz.createProperty(session, "float", PropertyType.FLOAT).setMax(session, "11");
    clazz.createProperty(session, "binary", PropertyType.BINARY).setMax(session, "11");
    clazz.createProperty(session, "byte", PropertyType.BYTE).setMax(session, "11");
    var cal = Calendar.getInstance();
    cal.add(Calendar.HOUR, cal.get(Calendar.HOUR) == 11 ? 0 : 1);
    var format = session.getStorage().getConfiguration().getDateFormatInstance();
    clazz.createProperty(session, "date", PropertyType.DATE)
        .setMax(session, format.format(cal.getTime()));
    cal = Calendar.getInstance();
    cal.add(Calendar.HOUR, 1);
    format = session.getStorage().getConfiguration().getDateTimeFormatInstance();
    clazz.createProperty(session, "datetime", PropertyType.DATETIME)
        .setMax(session, format.format(cal.getTime()));

    clazz.createProperty(session, "decimal", PropertyType.DECIMAL).setMax(session, "11");
    clazz.createProperty(session, "double", PropertyType.DOUBLE).setMax(session, "11");
    clazz.createProperty(session, "short", PropertyType.SHORT).setMax(session, "11");
    clazz.createProperty(session, "string", PropertyType.STRING).setMax(session, "11");
    // clazz.createProperty("link", PropertyType.LINK) no meaning
    // clazz.createProperty("embedded", PropertyType.EMBEDDED) no meaning

    clazz.createProperty(session, "embeddedList", PropertyType.EMBEDDEDLIST).setMax(session, "2");
    clazz.createProperty(session, "embeddedSet", PropertyType.EMBEDDEDSET).setMax(session, "2");
    clazz.createProperty(session, "embeddedMap", PropertyType.EMBEDDEDMAP).setMax(session, "2");

    clazz.createProperty(session, "linkList", PropertyType.LINKLIST).setMax(session, "2");
    clazz.createProperty(session, "linkSet", PropertyType.LINKSET).setMax(session, "2");
    clazz.createProperty(session, "linkMap", PropertyType.LINKMAP).setMax(session, "2");
    clazz.createProperty(session, "linkBag", PropertyType.LINKBAG).setMax(session, "2");

    session.begin();
    var entity = session.newEntity(clazz);
    entity.setInt("int", 11);
    entity.setLong("long", 11L);
    entity.setFloat("float", 11f);
    entity.setBinary("binary", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
    entity.setByte("byte", (byte) 11);
    entity.setDate("date", new Date());
    entity.setDateTime("datetime", new Date());
    entity.setDecimal("decimal", BigDecimal.valueOf(10));
    entity.setDouble("double", 10d);
    entity.setShort("short", (short) 10);
    entity.setString("string", "yeah");

    var embeddedList = session.newEmbeddedList();
    embeddedList.add("a");
    embeddedList.add("b");

    entity.setEmbeddedList("embeddedList", embeddedList);

    var embeddedSet = session.newEmbeddedSet();
    embeddedSet.add("a");
    embeddedSet.add("b");

    entity.setEmbeddedSet("embeddedSet", embeddedSet);

    var cont = session.<String>newEmbeddedMap();
    cont.put("one", "one");
    cont.put("two", "one");
    entity.setEmbeddedMap("embeddedMap", cont);

    var links = session.newLinkList();
    links.addAll(Arrays.asList(new RecordId(40, 30), new RecordId(40, 34)));
    entity.setLinkList("linkList", links);

    var linkSet = session.newLinkSet();
    linkSet.addAll(Arrays.asList(new RecordId(40, 30), new RecordId(40, 34)));
    entity.setLinkSet("linkSet", linkSet);

    var cont1 = session.newLinkMap();
    cont1.put("one", new RecordId(30, 30));
    cont1.put("two", new RecordId(30, 30));

    entity.setLinkMap("linkMap", cont1);
    var bag1 = new RidBag(session);
    bag1.add(new RecordId(40, 30));
    bag1.add(new RecordId(40, 33));
    entity.setProperty("linkBag", bag1);

    ((EntityImpl) entity).validate();

    checkField(entity, "int", 12);
    checkField(entity, "long", 12);
    checkField(entity, "float", 20);
    checkField(entity, "binary", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});

    checkField(entity, "byte", 20);
    cal = Calendar.getInstance();
    cal.add(Calendar.DAY_OF_MONTH, 1);
    checkField(entity, "date", cal.getTime());
    checkField(entity, "datetime", cal.getTime());
    checkField(entity, "decimal", 20);
    checkField(entity, "double", 20);
    checkField(entity, "short", 20);
    checkField(entity, "string", "0123456789101112");

    var embeddedList1 = session.newEmbeddedList();
    embeddedList1.add("a");
    embeddedList1.add("b");
    embeddedList1.add("d");

    checkField(entity, "embeddedList", embeddedList1);

    var embeddedSet1 = session.newEmbeddedSet();
    embeddedSet1.add("a");
    embeddedSet1.add("b");
    embeddedSet1.add("d");

    checkField(entity, "embeddedSet", embeddedSet1);

    var con1 = session.newEmbeddedMap();
    con1.put("one", "one");
    con1.put("two", "one");
    con1.put("three", "one");

    var links1 = session.newLinkList();
    links1.addAll(Arrays.asList(new RecordId(40, 30), new RecordId(40, 33), new RecordId(40, 31)));

    checkField(entity, "embeddedMap", con1);
    checkField(
        entity,
        "linkList",
        links1);

    var linkSet1 = session.newLinkSet();
    linkSet1.addAll(
        Arrays.asList(new RecordId(40, 30), new RecordId(40, 33), new RecordId(40, 31)));

    var cont3 = session.newLinkMap();
    cont3.put("one", new RecordId(30, 30));
    cont3.put("two", new RecordId(30, 30));
    cont3.put("three", new RecordId(30, 30));
    checkField(entity, "linkMap", cont3);

    var bag2 = new RidBag(session);
    bag2.add(new RecordId(40, 30));
    bag2.add(new RecordId(40, 33));
    bag2.add(new RecordId(40, 31));
    checkField(entity, "linkBag", bag2);
    session.rollback();
  }

  @Test
  public void testMinValidation() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    Identifiable id = ((DBRecord) doc).getIdentity();
    session.commit();

    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "int", PropertyType.INTEGER).setMin(session, "11");
    clazz.createProperty(session, "long", PropertyType.LONG).setMin(session, "11");
    clazz.createProperty(session, "float", PropertyType.FLOAT).setMin(session, "11");
    clazz.createProperty(session, "binary", PropertyType.BINARY).setMin(session, "11");
    clazz.createProperty(session, "byte", PropertyType.BYTE).setMin(session, "11");
    var cal = Calendar.getInstance();
    cal.add(Calendar.HOUR, cal.get(Calendar.HOUR) == 11 ? 0 : 1);
    var format = session.getStorage().getConfiguration().getDateFormatInstance();
    clazz.createProperty(session, "date", PropertyType.DATE)
        .setMin(session, format.format(cal.getTime()));
    cal = Calendar.getInstance();
    cal.add(Calendar.HOUR, 1);
    format = session.getStorage().getConfiguration().getDateTimeFormatInstance();
    clazz.createProperty(session, "datetime", PropertyType.DATETIME)
        .setMin(session, format.format(cal.getTime()));

    clazz.createProperty(session, "decimal", PropertyType.DECIMAL).setMin(session, "11");
    clazz.createProperty(session, "double", PropertyType.DOUBLE).setMin(session, "11");
    clazz.createProperty(session, "short", PropertyType.SHORT).setMin(session, "11");
    clazz.createProperty(session, "string", PropertyType.STRING).setMin(session, "11");

    clazz.createProperty(session, "embeddedList", PropertyType.EMBEDDEDLIST).setMin(session, "1");
    clazz.createProperty(session, "embeddedSet", PropertyType.EMBEDDEDSET).setMin(session, "1");
    clazz.createProperty(session, "embeddedMap", PropertyType.EMBEDDEDMAP).setMin(session, "1");

    clazz.createProperty(session, "linkList", PropertyType.LINKLIST).setMin(session, "1");
    clazz.createProperty(session, "linkSet", PropertyType.LINKSET).setMin(session, "1");
    clazz.createProperty(session, "linkMap", PropertyType.LINKMAP).setMin(session, "1");
    clazz.createProperty(session, "linkBag", PropertyType.LINKBAG).setMin(session, "1");

    session.begin();
    var entity = session.newEntity(clazz);
    entity.setInt("int", 11);
    entity.setLong("long", 11L);
    entity.setFloat("float", 11f);
    entity.setBinary("binary", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
    entity.setByte("byte", (byte) 11);

    cal = Calendar.getInstance();
    cal.add(Calendar.DAY_OF_MONTH, 1);
    entity.setDate("date", new Date());
    entity.setDateTime("datetime", cal.getTime());
    entity.setDecimal("decimal", BigDecimal.valueOf(12));
    entity.setDouble("double", 12d);
    entity.setShort("short", (short) 12);
    entity.setString("string", "yeahyeahyeah");
    entity.setLink("link", id);

    entity.newEmbeddedList("embeddedList").add("a");
    entity.newEmbeddedSet("embeddedSet").add("a");

    Map<String, String> map = session.newEmbeddedMap();
    map.put("some", "value");
    entity.setEmbeddedMap("embeddedMap", map);
    entity.newLinkList("linkList").add(new RecordId(40, 50));
    entity.newLinkSet("linkSet").add(new RecordId(40, 50));

    var map1 = session.newLinkMap();
    map1.put("some", new RecordId(40, 50));
    entity.setLinkMap("linkMap", map1);

    var bag1 = new RidBag(session);
    bag1.add(new RecordId(40, 50));
    ((EntityImpl) entity).setPropertyInternal("linkBag", bag1);
    ((EntityImpl) entity).validate();

    checkField(entity, "int", 10);
    checkField(entity, "long", 10);
    checkField(entity, "float", 10);
    checkField(entity, "binary", new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
    checkField(entity, "byte", 10);

    cal = Calendar.getInstance();
    cal.add(Calendar.DAY_OF_MONTH, -1);
    checkField(entity, "date", cal.getTime());
    checkField(entity, "datetime", new Date());
    checkField(entity, "decimal", 10);
    checkField(entity, "double", 10);
    checkField(entity, "short", 10);
    checkField(entity, "string", "01234");
    checkField(entity, "embeddedList", session.newEmbeddedList());
    checkField(entity, "embeddedSet", session.newEmbeddedSet());
    checkField(entity, "embeddedMap", session.newEmbeddedMap());
    checkField(entity, "linkList", session.newLinkList());
    checkField(entity, "linkSet", session.newLinkSet());
    checkField(entity, "linkMap", session.newLinkMap());
    checkField(entity, "linkBag", new RidBag(session));
    session.rollback();
  }

  @Test
  public void testNotNullValidation() {
    session.begin();
    var entity = session.newEntity();
    Identifiable id = entity.getIdentity();
    session.commit();

    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "int", PropertyType.INTEGER).setNotNull(session, true);
    clazz.createProperty(session, "long", PropertyType.LONG).setNotNull(session, true);
    clazz.createProperty(session, "float", PropertyType.FLOAT).setNotNull(session, true);
    clazz.createProperty(session, "boolean", PropertyType.BOOLEAN).setNotNull(session, true);
    clazz.createProperty(session, "binary", PropertyType.BINARY).setNotNull(session, true);
    clazz.createProperty(session, "byte", PropertyType.BYTE).setNotNull(session, true);
    clazz.createProperty(session, "date", PropertyType.DATE).setNotNull(session, true);
    clazz.createProperty(session, "datetime", PropertyType.DATETIME).setNotNull(session, true);
    clazz.createProperty(session, "decimal", PropertyType.DECIMAL).setNotNull(session, true);
    clazz.createProperty(session, "double", PropertyType.DOUBLE).setNotNull(session, true);
    clazz.createProperty(session, "short", PropertyType.SHORT).setNotNull(session, true);
    clazz.createProperty(session, "string", PropertyType.STRING).setNotNull(session, true);
    clazz.createProperty(session, "link", PropertyType.LINK).setNotNull(session, true);
    clazz.createProperty(session, "embedded", PropertyType.EMBEDDED).setNotNull(session, true);

    clazz.createProperty(session, "embeddedList", PropertyType.EMBEDDEDLIST)
        .setNotNull(session, true);
    clazz.createProperty(session, "embeddedSet", PropertyType.EMBEDDEDSET)
        .setNotNull(session, true);
    clazz.createProperty(session, "embeddedMap", PropertyType.EMBEDDEDMAP)
        .setNotNull(session, true);

    clazz.createProperty(session, "linkList", PropertyType.LINKLIST).setNotNull(session, true);
    clazz.createProperty(session, "linkSet", PropertyType.LINKSET).setNotNull(session, true);
    clazz.createProperty(session, "linkMap", PropertyType.LINKMAP).setNotNull(session, true);

    session.begin();
    var e = session.newEntity(clazz);
    e.setInt("int", 12);
    e.setLong("long", 12L);
    e.setFloat("float", 12f);
    e.setBoolean("boolean", true);
    e.setBinary("binary", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
    e.setByte("byte", (byte) 12);
    e.setDate("date", new Date());
    e.setDateTime("datetime", new Date());
    e.setDecimal("decimal", BigDecimal.valueOf(12));
    e.setDouble("double", 12d);
    e.setShort("short", (short) 12);
    e.setString("string", "yeah");
    e.setLink("link", id);

    var embedded = session.newEmbededEntity();
    embedded.setString("test", "test");
    e.setEmbeddedEntity("embedded", embedded);

    e.setEmbeddedList("embeddedList", session.newEmbeddedList());
    e.setEmbeddedSet("embeddedSet", session.newEmbeddedSet());
    e.setEmbeddedMap("embeddedMap", session.newEmbeddedMap());
    e.setLinkList("linkList", session.newLinkList());
    e.setLinkSet("linkSet", session.newLinkSet());
    e.setLinkMap("linkMap", session.newLinkMap());
    ((EntityImpl) e).validate();

    checkField(e, "int", null);
    checkField(e, "long", null);
    checkField(e, "float", null);
    checkField(e, "boolean", null);
    checkField(e, "binary", null);
    checkField(e, "byte", null);
    checkField(e, "date", null);
    checkField(e, "datetime", null);
    checkField(e, "decimal", null);
    checkField(e, "double", null);
    checkField(e, "short", null);
    checkField(e, "string", null);
    checkField(e, "link", null);
    checkField(e, "embedded", null);
    checkField(e, "embeddedList", null);
    checkField(e, "embeddedSet", null);
    checkField(e, "embeddedMap", null);
    checkField(e, "linkList", null);
    checkField(e, "linkSet", null);
    checkField(e, "linkMap", null);
    session.rollback();
  }

  @Test
  public void testRegExpValidation() {
    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "string", PropertyType.STRING).setRegexp(session, "[^Z]*");

    session.begin();
    var entity = session.newEntity(clazz);
    entity.setString("string", "yeah");
    ((EntityImpl) entity).validate();

    checkField(entity, "string", "yaZah");
    session.rollback();
  }

  @Test
  public void testLinkedTypeValidation() {
    var clazz = session.getMetadata().getSchema().createClass("Validation");
    clazz.createProperty(session, "embeddedList", PropertyType.EMBEDDEDLIST)
        .setLinkedType(session, PropertyType.INTEGER);
    clazz.createProperty(session, "embeddedSet", PropertyType.EMBEDDEDSET)
        .setLinkedType(session, PropertyType.INTEGER);
    clazz.createProperty(session, "embeddedMap", PropertyType.EMBEDDEDMAP)
        .setLinkedType(session, PropertyType.INTEGER);

    session.begin();
    var entity = session.newEntity(clazz);
    var list = Arrays.asList(1, 2);
    entity.newEmbeddedSet("embeddedList").addAll(list);
    Set<Integer> set = session.newEmbeddedSet();
    set.addAll(list);
    entity.setEmbeddedSet("embeddedSet", set);

    Map<String, Integer> map = session.newEmbeddedMap();
    map.put("a", 1);
    map.put("b", 2);
    entity.setEmbeddedMap("embeddedMap", map);

    ((EntityImpl) entity).validate();

    var newList = session.newEmbeddedList();
    newList.addAll(Arrays.asList(1, 2, "a3"));
    checkField(entity, "embeddedList", newList);

    var newSet = session.newEmbeddedSet();
    newSet.addAll(Arrays.asList(1, 2, "a3"));
    checkField(entity, "embeddedSet", newSet);

    Map<String, String> map1 = session.newEmbeddedMap();
    map1.put("a", "a1");
    map1.put("b", "a2");

    checkField(entity, "embeddedMap", map1);
    session.rollback();
  }

  @Test
  public void testLinkedClassValidation() {
    var clazz = session.getMetadata().getSchema().createClass("Validation");
    var clazz1 = session.getMetadata().getSchema().createClass("Validation1");
    clazz.createProperty(session, "link", PropertyType.LINK).setLinkedClass(session, clazz1);
    clazz.createProperty(session, "embedded", PropertyType.EMBEDDED)
        .setLinkedClass(session, clazz1);
    clazz.createProperty(session, "linkList", PropertyType.LINKLIST)
        .setLinkedClass(session, clazz1);
    clazz.createProperty(session, "embeddedList", PropertyType.EMBEDDEDLIST)
        .setLinkedClass(session, clazz1);
    clazz.createProperty(session, "embeddedSet", PropertyType.EMBEDDEDSET)
        .setLinkedClass(session, clazz1);
    clazz.createProperty(session, "linkSet", PropertyType.LINKSET).setLinkedClass(session, clazz1);
    clazz.createProperty(session, "linkMap", PropertyType.LINKMAP).setLinkedClass(session, clazz1);
    clazz.createProperty(session, "linkBag", PropertyType.LINKBAG).setLinkedClass(session, clazz1);

    session.begin();
    var entity = session.newEntity(clazz);
    entity.setLink("link", session.newEntity(clazz1));
    entity.setEmbeddedEntity("embedded", session.newEmbededEntity(clazz1));
    var list = entity.getOrCreateLinkList("linkList");
    list.add(session.newEntity(clazz1));
    entity.getOrCreateLinkSet("linkSet").addAll(list);

    var embeddedList = entity.newEmbeddedList("embeddedList");
    embeddedList.add(session.newEmbededEntity(clazz1));
    embeddedList.add(null);

    entity.newEmbeddedSet("embeddedSet").addAll(embeddedList);

    var map = session.newLinkMap();
    map.put("a", session.newEntity(clazz1));
    entity.setLinkMap("linkMap", map);

    ((EntityImpl) entity).validate();

    checkField(entity, "link", session.newEntity(clazz));
    checkField(entity, "embedded", session.newEmbededEntity(clazz));

    var newLinkList = session.newEmbeddedList();
    newLinkList.addAll(Arrays.asList("a", "b"));
    checkField(entity, "linkList", newLinkList);

    var newLinkSet = session.newEmbeddedSet();
    newLinkList.addAll(Arrays.asList("a", "b"));
    checkField(entity, "linkSet", newLinkList);

    Map<String, String> map1 = session.newEmbeddedMap();
    map1.put("a", "a1");
    map1.put("b", "a2");
    checkField(entity, "linkMap", map1);

    var newLinkList2 = session.newLinkList();
    newLinkList2.add(session.newEntity(clazz));
    checkField(entity, "linkList", newLinkList2);

    var newLinkSet2 = session.newLinkSet();
    newLinkSet2.add(session.newEntity(clazz));
    checkField(entity, "linkSet", newLinkSet2);

    var newEmbeddedList = session.newEmbeddedList();
    newEmbeddedList.add(session.newEmbededEntity(clazz));
    checkField(entity, "embeddedList", newEmbeddedList);

    var newEmbeddedSet = session.newEmbeddedSet();
    newEmbeddedSet.add(session.newEmbededEntity(clazz));

    checkField(entity, "embeddedSet", newEmbeddedSet);

    var bag = new RidBag(session);
    bag.add(session.newEntity(clazz).getIdentity());
    checkField(entity, "linkBag", bag);
    var map2 = session.newLinkMap();
    map2.put("a", session.newEntity(clazz));
    checkField(entity, "linkMap", map2);
    session.rollback();
  }

  @Test
  public void testValidLinkCollectionsUpdate() {
    var clazz = session.getMetadata().getSchema().createClass("Validation");
    var clazz1 = session.getMetadata().getSchema().createClass("Validation1");
    clazz.createProperty(session, "linkList", PropertyType.LINKLIST)
        .setLinkedClass(session, clazz1);
    clazz.createProperty(session, "linkSet", PropertyType.LINKSET).setLinkedClass(session, clazz1);
    clazz.createProperty(session, "linkMap", PropertyType.LINKMAP).setLinkedClass(session, clazz1);
    clazz.createProperty(session, "linkBag", PropertyType.LINKBAG).setLinkedClass(session, clazz1);

    session.begin();
    var entity = session.newEntity(clazz);
    entity.setLink("link", session.newEntity(clazz1));
    entity.setEmbeddedEntity("embedded", session.newEmbededEntity(clazz1));

    var list = entity.newLinkList("linkList");
    list.add(session.newEntity(clazz1));

    entity.newLinkSet("linkSet");
    ((EntityImpl) entity).setPropertyInternal("linkBag", new RidBag(session));

    var map = session.newLinkMap();
    map.put("a", session.newEntity(clazz1));
    entity.setLinkMap("linkMap", map);
    session.commit();

    try {
      session.begin();
      entity = session.bindToSession(entity);
      entity.getLinkList("linkList").add(session.newEntity(clazz));
      ((EntityImpl) entity).validate();
      fail();
    } catch (ValidationException v) {
      session.rollback();
    }

    try {
      session.begin();
      entity = session.bindToSession(entity);
      entity.getLinkSet("linkSet").add(session.newEntity(clazz));
      ((EntityImpl) entity).validate();
      fail();
    } catch (ValidationException v) {
      session.rollback();
    }

    try {
      session.begin();
      entity = session.bindToSession(entity);
      ((RidBag) entity.getProperty("linkBag")).add(session.newEntity(clazz).getIdentity());
      session.commit();
      fail();
    } catch (ValidationException v) {
      session.rollback();
    }

    try {
      session.begin();
      entity = session.bindToSession(entity);
      entity.getLinkMap("linkMap").put("a", session.newEntity(clazz));
      ((EntityImpl) entity).validate();
      fail();
    } catch (ValidationException v) {
      session.rollback();
    }
  }

  private static void checkField(Entity toCheck, String field, Object newValue) {
    try {
      var session = toCheck.getBoundedToSession();
      var newD = (EntityImpl) session.newEntity(toCheck.getSchemaClass());

      newD.unsetDirty();
      newD.fromStream(((EntityImpl) toCheck).toStream());

      newD.setProperty(field, newValue);
      newD.validate();
      fail();
    } catch (ValidationException v) {
      //ignore
    }
  }
}
