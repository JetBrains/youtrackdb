package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class DateIndexTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    final Schema schema = session.getMetadata().getSchema();

    var dateIndexTest = schema.createClass("DateIndexTest");

    dateIndexTest.createProperty("dateField", PropertyType.DATE);
    dateIndexTest.createProperty("dateTimeField", PropertyType.DATETIME);

    dateIndexTest.createProperty("dateList", PropertyType.EMBEDDEDLIST,
        PropertyType.DATE);
    dateIndexTest.createProperty("dateTimeList", PropertyType.EMBEDDEDLIST,
        PropertyType.DATETIME);

    dateIndexTest.createProperty("value", PropertyType.STRING);

    dateIndexTest.createIndex("DateIndexTestDateIndex", SchemaClass.INDEX_TYPE.UNIQUE,
        "dateField");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateIndex", SchemaClass.INDEX_TYPE.UNIQUE, "value", "dateField");

    dateIndexTest.createIndex(
        "DateIndexTestDateTimeIndex", SchemaClass.INDEX_TYPE.UNIQUE, "dateTimeField");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateTimeIndex", SchemaClass.INDEX_TYPE.UNIQUE, "value",
        "dateTimeField");

    dateIndexTest.createIndex(
        "DateIndexTestValueDateListIndex", SchemaClass.INDEX_TYPE.UNIQUE, "value", "dateList");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateTimeListIndex", SchemaClass.INDEX_TYPE.UNIQUE, "value",
        "dateTimeList");

    dateIndexTest.createIndex(
        "DateIndexTestDateHashIndex", SchemaClass.INDEX_TYPE.UNIQUE, "dateField");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateHashIndex",
        SchemaClass.INDEX_TYPE.UNIQUE,
        "value", "dateField");

    dateIndexTest.createIndex(
        "DateIndexTestDateTimeHashIndex", SchemaClass.INDEX_TYPE.UNIQUE,
        "dateTimeField");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateTimeHashIndex",
        SchemaClass.INDEX_TYPE.UNIQUE,
        "value", "dateTimeField");

    dateIndexTest.createIndex(
        "DateIndexTestValueDateListHashIndex",
        SchemaClass.INDEX_TYPE.UNIQUE,
        "value", "dateList");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateTimeListHashIndex",
        SchemaClass.INDEX_TYPE.UNIQUE,
        "value", "dateTimeList");
  }

  @Test
  @Order(1)
  void testDateIndexes() {

    session.begin();
    final var dateOne = new Date();

    final var dateTwo = new Date(dateOne.getTime() + 24 * 60 * 60 * 1000 + 100);

    final var dateDoc = ((EntityImpl) session.newEntity("DateIndexTest"));

    dateDoc.setProperty("dateField", dateOne);
    dateDoc.setProperty("dateTimeField", dateTwo);

    final List<Date> dateList = session.newEmbeddedList();

    final var dateThree = new Date(dateOne.getTime() + 100);
    final var dateFour = new Date(dateThree.getTime() + 24 * 60 * 60 * 1000 + 100);

    dateList.add(new Date(dateThree.getTime()));
    dateList.add(new Date(dateFour.getTime()));

    final List<Date> dateTimeList = session.newEmbeddedList();

    dateTimeList.add(new Date(dateThree.getTime()));
    dateTimeList.add(new Date(dateFour.getTime()));

    dateDoc.setProperty("dateList", dateList);
    dateDoc.setProperty("dateTimeList", dateTimeList);

    dateDoc.setProperty("value", "v1");

    session.commit();

    session.begin();
    final var dateIndexTestDateIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestDateIndex");
    try (var stream = dateIndexTestDateIndex.getRids(session, dateOne)) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = dateIndexTestDateIndex.getRids(session, dateTwo)) {
      assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestDateTimeIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestDateTimeIndex");
    try (var stream = dateIndexTestDateTimeIndex
        .getRids(session, dateTwo)) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = dateIndexTestDateTimeIndex
        .getRids(session, dateOne)) {
      assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateIndex");
    try (var stream =
        dateIndexTestValueDateIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream =
        dateIndexTestValueDateIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateTimeIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateTimeIndex");
    try (var stream =
        dateIndexTestValueDateTimeIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream =
        dateIndexTestValueDateTimeIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateListIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateListIndex");

    try (var stream =
        dateIndexTestValueDateListIndex
            .getRids(session, new CompositeKey("v1", dateThree))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream =
        dateIndexTestValueDateListIndex
            .getRids(session, new CompositeKey("v1", dateFour))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }

    final var dateIndexTestValueDateTimeListIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateListIndex");
    try (var stream =
        dateIndexTestValueDateTimeListIndex
            .getRids(session, new CompositeKey("v1", dateThree))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream =
        dateIndexTestValueDateTimeListIndex
            .getRids(session, new CompositeKey("v1", dateFour))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }

    final var dateIndexTestDateHashIndexIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestDateHashIndex");
    try (var stream = dateIndexTestDateHashIndexIndex
        .getRids(session, dateOne)) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = dateIndexTestDateHashIndexIndex
        .getRids(session, dateTwo)) {
      assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestDateTimeHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestDateTimeHashIndex");
    try (var stream = dateIndexTestDateTimeHashIndex
        .getRids(session, dateTwo)) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream = dateIndexTestDateTimeHashIndex
        .getRids(session, dateOne)) {
      assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateHashIndex");
    try (var stream =
        dateIndexTestValueDateHashIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream =
        dateIndexTestValueDateHashIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateTimeHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateTimeHashIndex");
    try (var stream =
        dateIndexTestValueDateTimeHashIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream =
        dateIndexTestValueDateTimeHashIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateListHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateListHashIndex");

    try (var stream =
        dateIndexTestValueDateListHashIndex
            .getRids(session, new CompositeKey("v1", dateThree))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream =
        dateIndexTestValueDateListHashIndex
            .getRids(session, new CompositeKey("v1", dateFour))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }

    final var dateIndexTestValueDateTimeListHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateListHashIndex");
    try (var stream =
        dateIndexTestValueDateTimeListHashIndex
            .getRids(session, new CompositeKey("v1", dateThree))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    try (var stream =
        dateIndexTestValueDateTimeListHashIndex
            .getRids(session, new CompositeKey("v1", dateFour))) {
      assertEquals(dateDoc.getIdentity(), stream.findAny().orElse(null));
    }
    session.rollback();
  }
}
