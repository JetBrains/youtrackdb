package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class DocumentValidationTest extends BaseMemoryInternalDatabase {

  @Test
  public void testRequiredValidation() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    Identifiable id = ((DBRecord) doc).getIdentity();
    session.commit();

    var className = "Validation";
    var embeddedClassName = "EmbeddedValidation";

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.createAbstractSchemaClass(embeddedClassName,
                __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true)
            )
            .createSchemaClass(className,
                __.createSchemaProperty("long", PropertyType.LONG).mandatoryAttr(true),
                __.createSchemaProperty("float", PropertyType.FLOAT).mandatoryAttr(true),
                __.createSchemaProperty("boolean", PropertyType.BOOLEAN).mandatoryAttr(true),
                __.createSchemaProperty("binary", PropertyType.BINARY).mandatoryAttr(true),
                __.createSchemaProperty("byte", PropertyType.BYTE).mandatoryAttr(true),
                __.createSchemaProperty("date", PropertyType.DATE).mandatoryAttr(true),
                __.createSchemaProperty("datetime", PropertyType.DATETIME).mandatoryAttr(true),
                __.createSchemaProperty("decimal", PropertyType.DECIMAL).mandatoryAttr(true),
                __.createSchemaProperty("double", PropertyType.DOUBLE).mandatoryAttr(true),
                __.createSchemaProperty("short", PropertyType.SHORT).mandatoryAttr(true),
                __.createSchemaProperty("string", PropertyType.STRING).mandatoryAttr(true),
                __.createSchemaProperty("link", PropertyType.LINK).mandatoryAttr(true),
                __.createSchemaProperty("embedded", PropertyType.EMBEDDED,
                    embeddedClassName).mandatoryAttr(true),
                __.createSchemaProperty("embeddedListNoClass", PropertyType.EMBEDDEDLIST)
                    .mandatoryAttr(true),
                __.createSchemaProperty("embeddedSetNoClass", PropertyType.EMBEDDEDSET)
                    .mandatoryAttr(true),
                __.createSchemaProperty("embeddedMapNoClass", PropertyType.EMBEDDEDMAP)
                    .mandatoryAttr(true),
                __.createSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST,
                    embeddedClassName).mandatoryAttr(true),
                __.createSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET,
                    embeddedClassName).mandatoryAttr(true),
                __.createSchemaProperty("embeddedMap", PropertyType.EMBEDDEDMAP,
                    embeddedClassName).mandatoryAttr(true),
                __.createSchemaProperty("linkList", PropertyType.LINKLIST).
                    mandatoryAttr(true),
                __.createSchemaProperty("linkSet", PropertyType.LINKSET).
                    mandatoryAttr(true),
                __.createSchemaProperty("linkMap", PropertyType.LINKMAP).mandatoryAttr(true)
            )
    );

    session.begin();
    var entity = (EntityImpl) session.newEntity(className);
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

    var embedded = session.newEmbeddedEntity(embeddedClassName);
    embedded.setInt("int", 20);
    embedded.setLong("long", 20L);
    entity.setEmbeddedEntity("embedded", embedded);

    var embeddedInList = session.newEmbeddedEntity(embeddedClassName);
    embeddedInList.setInt("int", 30);
    embeddedInList.setLong("long", 30L);

    final var embeddedList = session.newEmbeddedList();
    embeddedList.add(embeddedInList);
    entity.setEmbeddedList("embeddedList", embeddedList);

    var embeddedInSet = session.newEmbeddedEntity(embeddedClassName);
    embeddedInSet.setInt("int", 30);
    embeddedInSet.setLong("long", 30L);
    var embeddedSet = session.newEmbeddedSet();
    embeddedSet.add(embeddedInSet);
    entity.setEmbeddedSet("embeddedSet", embeddedSet);

    var embeddedInMap = session.newEmbeddedEntity(embeddedClassName);
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

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.createAbstractSchemaClass("EmbeddedValidation",
                __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true)
            ).
            createSchemaClass("Validation",
                __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true),
                __.createSchemaProperty("long", PropertyType.LONG).mandatoryAttr(true),
                __.createSchemaProperty("embedded", PropertyType.EMBEDDED, "EmbeddedValidation")
                    .mandatoryAttr(true)
            )
    );

    session.begin();
    var d = (EntityImpl) session.newEntity("Validation");
    d.setProperty("int", 30);
    d.setProperty("long", 30);
    var entity = ((EntityImpl) session.newEmbeddedEntity("EmbeddedValidation"));
    entity.setProperty("test", "test");
    d.setProperty("embedded", entity);
    try {
      d.validate();
      fail("Validation doesn't throw exception");
    } catch (ValidationException e) {
      Assert.assertTrue(e.toString().contains("EmbeddedValidation.int"));
      session.rollback();
    }
  }

  @Test
  public void testValidationNotValidEmbeddedSet() {
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.createAbstractSchemaClass("EmbeddedValidation",
            __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true),
            __.createSchemaProperty("long", PropertyType.LONG).mandatoryAttr(true)
        ).createSchemaClass("Validation",
            __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true),
            __.createSchemaProperty("long", PropertyType.LONG).mandatoryAttr(true),
            __.createSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET, "EmbeddedValidation")
                .mandatoryAttr(true)
        )
    );

    session.begin();
    var entity = session.newEntity("Validation");
    entity.setInt("int", 30);
    entity.setLong("long", 30L);
    final var embeddedSet = session.newEmbeddedSet();
    entity.setEmbeddedSet("embeddedSet", embeddedSet);

    var embeddedInSet = session.newEmbeddedEntity("EmbeddedValidation");
    embeddedInSet.setInt("int", 30);
    embeddedInSet.setLong("long", 30L);
    embeddedSet.add(embeddedInSet);

    var embeddedInSet2 = session.newEmbeddedEntity("EmbeddedValidation");
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
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.createAbstractSchemaClass("EmbeddedValidation",
            __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true),
            __.createSchemaProperty("long", PropertyType.LONG).mandatoryAttr(true)
        ).createSchemaClass("Validation",
            __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true),
            __.createSchemaProperty("long", PropertyType.LONG).mandatoryAttr(true),
            __.createSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST,
                "EmbeddedValidation").mandatoryAttr(true)
        )
    );

    session.begin();
    var entity = session.newEntity("Validation");
    entity.setInt("int", 30);
    entity.setLong("long", 30L);
    final var embeddedList = entity.newEmbeddedList("embeddedList");

    var embeddedInList = session.newEmbeddedEntity("EmbeddedValidation");
    embeddedInList.setInt("int", 30);
    embeddedInList.setLong("long", 30L);
    embeddedList.add(embeddedInList);

    var embeddedInList2 = session.newEmbeddedEntity("EmbeddedValidation");
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
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.createAbstractSchemaClass("EmbeddedValidation",
            __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true),
            __.createSchemaProperty("long", PropertyType.LONG).mandatoryAttr(true)
        ).createSchemaClass("Validation",
            __.createSchemaProperty("int", PropertyType.INTEGER).mandatoryAttr(true),
            __.createSchemaProperty("long", PropertyType.LONG).mandatoryAttr(true),
            __.createSchemaProperty("embeddedMap", PropertyType.EMBEDDEDMAP, "EmbeddedValidation")
                .mandatoryAttr(true)
        )
    );

    session.begin();
    var entity = session.newEntity("Validation");
    entity.setInt("int", 30);
    entity.setLong("long", 30L);
    var embeddedMap = entity.newEmbeddedMap("embeddedMap");

    var embeddedInMap = session.newEmbeddedEntity("EmbeddedValidation");
    embeddedInMap.setInt("int", 30);
    embeddedInMap.setLong("long", 30L);
    embeddedMap.put("1", embeddedInMap);

    var embeddedInMap2 = session.newEmbeddedEntity("EmbeddedValidation");
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
      var newD = (EntityImpl) session.getActiveTransaction().newEntity(toCheck.getSchemaClass());

      newD.unsetDirty();
      newD.fromStream(((EntityImpl) toCheck).toStream());
      newD.removeProperty(fieldName);
      newD.validate();
      fail();
    } catch (ValidationException v) {
      //ignore
    }
  }

  @Test
  public void testMaxValidation() {
    graph.executeInTx(g -> {
          var cal = Calendar.getInstance();
          cal.add(Calendar.HOUR, cal.get(Calendar.HOUR) == 11 ? 0 : 1);
          var format = session.getStorage().getConfiguration().getDateFormatInstance();

      var traversal = g.createSchemaClass("Validation",
          __.createSchemaProperty("int", PropertyType.INTEGER).maxAttr("11"),
          __.createSchemaProperty("long", PropertyType.LONG).maxAttr("11"),
          __.createSchemaProperty("float", PropertyType.FLOAT, "11"),
          __.createSchemaProperty("binary", PropertyType.BINARY).maxAttr("11"),
          __.createSchemaProperty("byte", PropertyType.BYTE).maxAttr("11"),
          __.createSchemaProperty("date", PropertyType.DATE).maxAttr(format.format(cal.getTime()))
          );

          cal = Calendar.getInstance();
          cal.add(Calendar.HOUR, 1);
          format = session.getStorage().getConfiguration().getDateTimeFormatInstance();

          //noinspection unchecked
      traversal.schemaClass("Validation").insertSchemaProperties(
          __.createSchemaProperty("datetime", PropertyType.DATETIME)
                  .maxAttr(format.format(cal.getTime())),
          __.createSchemaProperty("decimal", PropertyType.DECIMAL).maxAttr("11"),
          __.createSchemaProperty("double", PropertyType.DOUBLE).maxAttr("11"),
          __.createSchemaProperty("short", PropertyType.SHORT).maxAttr("11"),
          __.createSchemaProperty("string", PropertyType.STRING).maxAttr("11"),

          __.createSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST).maxAttr("2"),
          __.createSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET).maxAttr("2"),
          __.createSchemaProperty("embeddedMap", PropertyType.EMBEDDEDMAP).maxAttr("2"),

          __.createSchemaProperty("linkList", PropertyType.LINKLIST).maxAttr("2"),
          __.createSchemaProperty("linkSet", PropertyType.LINKSET).maxAttr("2"),
          __.createSchemaProperty("linkMap", PropertyType.LINKMAP).maxAttr("2"),
          __.createSchemaProperty("linkBag", PropertyType.LINKBAG).maxAttr("2")
          );

          traversal.iterate();
        }
    );

    session.begin();
    var entity = session.newEntity("Validation");
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
    var bag1 = new LinkBag(session);
    bag1.add(new RecordId(40, 30));
    bag1.add(new RecordId(40, 33));
    entity.setProperty("linkBag", bag1);

    ((EntityImpl) entity).validate();

    checkField(entity, "int", 12);
    checkField(entity, "long", 12);
    checkField(entity, "float", 20);
    checkField(entity, "binary", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});

    checkField(entity, "byte", 20);
    var cal = Calendar.getInstance();
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

    var bag2 = new LinkBag(session);
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

    graph.executeInTx(g -> {
      var traversal = g.createSchemaClass("Validation",
          __.createSchemaProperty("int", PropertyType.INTEGER).minAttr("11"),
          __.createSchemaProperty("long", PropertyType.LONG).minAttr("11"),
          __.createSchemaProperty("float", PropertyType.FLOAT).minAttr("11"),
          __.createSchemaProperty("binary", PropertyType.BINARY).minAttr("11"),
          __.createSchemaProperty("byte", PropertyType.BYTE).minAttr("11")
          );

          var cal = Calendar.getInstance();
          cal.add(Calendar.HOUR, cal.get(Calendar.HOUR) == 11 ? 0 : 1);
          var format = session.getStorage().getConfiguration().getDateFormatInstance();

      traversal.schemaClass("Validation").createSchemaProperty("date", PropertyType.DATE)
              .minAttr(format.format(cal.getTime()));

          cal = Calendar.getInstance();
          cal.add(Calendar.HOUR, 1);
          format = session.getStorage().getConfiguration().getDateTimeFormatInstance();

          //noinspection unchecked
      traversal.schemaClass("Validation").insertSchemaProperties(
          __.createSchemaProperty("datetime", PropertyType.DATETIME)
                  .minAttr(format.format(cal.getTime())),
          __.createSchemaProperty("decimal", PropertyType.DECIMAL).minAttr("11"),
          __.createSchemaProperty("double", PropertyType.DOUBLE).minAttr("11"),
          __.createSchemaProperty("short", PropertyType.SHORT).minAttr("11"),
          __.createSchemaProperty("string", PropertyType.STRING).minAttr("11"),

          __.createSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST).minAttr("1"),
          __.createSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET).minAttr("1"),
          __.createSchemaProperty("embeddedMap", PropertyType.EMBEDDEDMAP).minAttr("1"),
          __.createSchemaProperty("linkList", PropertyType.LINKLIST).minAttr("1"),
          __.createSchemaProperty("linkSet", PropertyType.LINKSET).minAttr("1"),
          __.createSchemaProperty("linkMap", PropertyType.LINKMAP).minAttr("1"),
          __.createSchemaProperty("linkBag", PropertyType.LINKBAG).minAttr("1")
          ).iterate();
        }
    );

    session.begin();
    var entity = session.newEntity("Validation");
    entity.setInt("int", 11);
    entity.setLong("long", 11L);
    entity.setFloat("float", 11f);
    entity.setBinary("binary", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
    entity.setByte("byte", (byte) 11);

    var cal = Calendar.getInstance();
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

    var bag1 = new LinkBag(session);
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
    checkField(entity, "linkBag", new LinkBag(session));
    session.rollback();
  }

  @Test
  public void testNotNullValidation() {
    session.begin();
    var entity = session.newEntity();
    Identifiable id = entity.getIdentity();
    session.commit();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Validation",
            __.createSchemaProperty("int", PropertyType.INTEGER).notNullAttr(true),
            __.createSchemaProperty("long", PropertyType.LONG).notNullAttr(true),
            __.createSchemaProperty("float", PropertyType.FLOAT).notNullAttr(true),
            __.createSchemaProperty("boolean", PropertyType.BOOLEAN).notNullAttr(true),
            __.createSchemaProperty("binary", PropertyType.BINARY).notNullAttr(true),
            __.createSchemaProperty("byte", PropertyType.BYTE).notNullAttr(true),
            __.createSchemaProperty("date", PropertyType.DATE).notNullAttr(true),
            __.createSchemaProperty("datetime", PropertyType.DATETIME).notNullAttr(true),
            __.createSchemaProperty("decimal", PropertyType.DECIMAL).notNullAttr(true),
            __.createSchemaProperty("double", PropertyType.DOUBLE).notNullAttr(true),
            __.createSchemaProperty("short", PropertyType.SHORT).notNullAttr(true),
            __.createSchemaProperty("string", PropertyType.STRING).notNullAttr(true),
            __.createSchemaProperty("link", PropertyType.LINK).notNullAttr(true),
            __.createSchemaProperty("embedded", PropertyType.EMBEDDED).notNullAttr(true),
            __.createSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST).notNullAttr(true),
            __.createSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET).notNullAttr(true),
            __.createSchemaProperty("embeddedMap", PropertyType.EMBEDDEDMAP).notNullAttr(true),
            __.createSchemaProperty("linkList", PropertyType.LINKLIST).notNullAttr(true),
            __.createSchemaProperty("linkSet", PropertyType.LINKSET).notNullAttr(true),
            __.createSchemaProperty("linkMap", PropertyType.LINKMAP).notNullAttr(true)
        )
    );

    session.begin();
    var e = session.newEntity("Validation");
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

    var embedded = session.newEmbeddedEntity();
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
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Validation").createSchemaProperty("string", PropertyType.STRING)
            .regExpAttr("[^Z]*")
    );

    session.begin();
    var entity = session.newEntity("Validation");
    entity.setString("string", "yeah");
    ((EntityImpl) entity).validate();

    checkField(entity, "string", "yaZah");
    session.rollback();
  }

  @Test
  public void testLinkedTypeValidation() {
    graph.autoExecuteInTx(
        g -> g.createSchemaClass("Validation",
            __.createSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST,
                PropertyType.INTEGER),
            __.createSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET, PropertyType.INTEGER),
            __.createSchemaProperty("embeddedMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER)
        )
    );

    var clazz = session.getMetadata().getSlowMutableSchema().createClass("Validation");

    session.begin();
    var entity = session.newEntity(clazz);
    var list = Arrays.asList(1, 2);
    entity.newEmbeddedList("embeddedList").addAll(list);
    var set = session.<Integer>newEmbeddedSet();
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
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Validation").
            createSchemaClass("Validation1").
            createAbstractSchemaClass("ValidationEmbedded").
            createAbstractSchemaClass("ValidationEmbedded2").
            schemaClass("Validation").insertSchemaProperties(
                __.createSchemaProperty("link", PropertyType.LINK, "Validation1"),
                __.createSchemaProperty("embedded", PropertyType.EMBEDDED, "ValidationEmbedded"),
                __.createSchemaProperty("linkList", PropertyType.LINKLIST, "Validation1"),
                __.createSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST,
                    "ValidationEmbedded"),
                __.createSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET, "ValidationEmbedded"),
                __.createSchemaProperty("linkSet", PropertyType.LINKSET, "Validation1"),
                __.createSchemaProperty("linkMap", PropertyType.LINKMAP, "Validation1"),
                __.createSchemaProperty("linkBag", PropertyType.LINKBAG, "Validation1")
            )
    );

    session.begin();
    var entity = session.newEntity("Validation");
    entity.setLink("link", session.newEntity("Validation1"));
    entity.setEmbeddedEntity("embedded", session.newEmbeddedEntity("ValidationEmbedded"));
    var list = entity.getOrCreateLinkList("linkList");
    list.add(session.newEntity("Validation1"));
    entity.getOrCreateLinkSet("linkSet").addAll(list);

    var embeddedList = entity.newEmbeddedList("embeddedList");
    embeddedList.add(session.newEmbeddedEntity("ValidationEmbedded"));
    embeddedList.add(null);

    entity.newEmbeddedSet("embeddedSet").addAll(embeddedList);

    var map = session.newLinkMap();
    map.put("a", session.newEntity("Validation1"));
    entity.setLinkMap("linkMap", map);

    ((EntityImpl) entity).validate();

    checkField(entity, "link", session.newEntity("Validation"));
    checkField(entity, "embedded", session.newEmbeddedEntity("ValidationEmbedded2"));

    var newLinkList = session.newEmbeddedList();
    newLinkList.addAll(Arrays.asList("a", "b"));
    checkField(entity, "linkList", newLinkList);

    var newLinkSet = session.newEmbeddedSet();
    newLinkList.addAll(Arrays.asList("a", "b"));
    checkField(entity, "linkSet", newLinkSet);

    Map<String, String> map1 = session.newEmbeddedMap();
    map1.put("a", "a1");
    map1.put("b", "a2");
    checkField(entity, "linkMap", map1);

    var newLinkList2 = session.newLinkList();
    newLinkList2.add(session.newEntity("Validation"));
    checkField(entity, "linkList", newLinkList2);

    var newLinkSet2 = session.newLinkSet();
    newLinkSet2.add(session.newEntity("Validation"));
    checkField(entity, "linkSet", newLinkSet2);

    var newEmbeddedList = session.newEmbeddedList();
    newEmbeddedList.add(session.newEmbeddedEntity("ValidationEmbedded2"));
    checkField(entity, "embeddedList", newEmbeddedList);

    var newEmbeddedSet = session.newEmbeddedSet();
    newEmbeddedSet.add(session.newEmbeddedEntity("ValidationEmbedded2"));

    checkField(entity, "embeddedSet", newEmbeddedSet);

    var bag = new LinkBag(session);
    bag.add(session.newEntity("Validation").getIdentity());
    checkField(entity, "linkBag", bag);
    var map2 = session.newLinkMap();
    map2.put("a", session.newEntity("Validation"));
    checkField(entity, "linkMap", map2);
    session.rollback();
  }

  @Test
  public void testValidLinkCollectionsUpdate() {
    //noinspection unchecked
    graph.autoExecuteInTx(
        g ->
            g.createSchemaClass("Validation").
                createSchemaClass("Validation1").
                createAbstractSchemaClass("EmbeddedValidation").
                schemaClass("Validation").insertSchemaProperties(
                    __.createSchemaProperty("linkList", PropertyType.LINKLIST, "Validation1"),
                    __.createSchemaProperty("linkSet", PropertyType.LINKSET, "Validation1"),
                    __.createSchemaProperty("linkMap", PropertyType.LINKMAP, "Validation1"),
                    __.createSchemaProperty("linkBag", PropertyType.LINKBAG, "Validation1")
                )
    );

    session.begin();
    var entity = session.newEntity("Validation");
    entity.setLink("link", session.newEntity("Validation1"));
    entity.setEmbeddedEntity("embedded", session.newEmbeddedEntity("ValidationEmbedded"));

    var list = entity.newLinkList("linkList");
    list.add(session.newEntity("Validation1"));

    entity.newLinkSet("linkSet");
    ((EntityImpl) entity).setPropertyInternal("linkBag", new LinkBag(session));

    var map = session.newLinkMap();
    map.put("a", session.newEntity("Validation1"));
    entity.setLinkMap("linkMap", map);
    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      entity = activeTx.load(entity);
      entity.getLinkList("linkList").add(session.newEntity("Validation"));
      ((EntityImpl) entity).validate();
      fail();
    } catch (ValidationException v) {
      session.rollback();
    }

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      entity = activeTx.load(entity);
      entity.getLinkSet("linkSet").add(session.newEntity("Validation"));
      ((EntityImpl) entity).validate();
      fail();
    } catch (ValidationException v) {
      session.rollback();
    }

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      entity = activeTx.load(entity);
      ((LinkBag) entity.getProperty("linkBag")).add(session.newEntity("Validation").getIdentity());
      session.commit();
      fail();
    } catch (ValidationException v) {
      session.rollback();
    }

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      entity = activeTx.load(entity);
      entity.getLinkMap("linkMap").put("a", session.newEntity("Validation"));
      ((EntityImpl) entity).validate();
      fail();
    } catch (ValidationException v) {
      session.rollback();
    }
  }

  private static void checkField(Entity toCheck, String field, Object newValue) {
    try {
      var session = toCheck.getBoundedToSession();
      var newD = (EntityImpl) session.getActiveTransaction().newEntity(toCheck.getSchemaClass());
      newD.copyProperties((EntityImpl) toCheck);
      newD.setProperty(field, newValue);
      newD.validate();
      fail();
    } catch (NumberFormatException | ValidationException v) {
      //ignore
    }
  }
}
