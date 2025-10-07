package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Date;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class DateIndexTest extends BaseDBTest {
  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSlowMutableSchema();

    var dateIndexTest = schema.createClass("DateIndexTest");

    dateIndexTest.createProperty("dateField", PropertyType.DATE);
    dateIndexTest.createProperty("dateTimeField", PropertyType.DATETIME);

    dateIndexTest.createProperty("dateList", PropertyType.EMBEDDEDLIST,
        PropertyType.DATE);
    dateIndexTest.createProperty("dateTimeList", PropertyType.EMBEDDEDLIST,
        PropertyType.DATETIME);

    dateIndexTest.createProperty("value", PropertyType.STRING);

    dateIndexTest.createIndex("DateIndexTestDateIndex", IndexType.UNIQUE,
        "dateField");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateIndex", IndexType.UNIQUE, "value", "dateField");

    dateIndexTest.createIndex(
        "DateIndexTestDateTimeIndex", IndexType.UNIQUE, "dateTimeField");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateTimeIndex", IndexType.UNIQUE, "value",
        "dateTimeField");

    dateIndexTest.createIndex(
        "DateIndexTestValueDateListIndex", IndexType.UNIQUE, "value", "dateList");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateTimeListIndex", IndexType.UNIQUE, "value",
        "dateTimeList");

    dateIndexTest.createIndex(
        "DateIndexTestDateHashIndex", IndexType.UNIQUE, "dateField");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateHashIndex",
        IndexType.UNIQUE,
        "value", "dateField");

    dateIndexTest.createIndex(
        "DateIndexTestDateTimeHashIndex", IndexType.UNIQUE,
        "dateTimeField");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateTimeHashIndex",
        IndexType.UNIQUE,
        "value", "dateTimeField");

    dateIndexTest.createIndex(
        "DateIndexTestValueDateListHashIndex",
        IndexType.UNIQUE,
        "value", "dateList");
    dateIndexTest.createIndex(
        "DateIndexTestValueDateTimeListHashIndex",
        IndexType.UNIQUE,
        "value", "dateTimeList");
  }

  public void testDateIndexes() {

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

    final var dateIndexTestDateIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestDateIndex");
    try (var stream = dateIndexTestDateIndex.getRids(session, dateOne)) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream = dateIndexTestDateIndex.getRids(session, dateTwo)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestDateTimeIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestDateTimeIndex");
    try (var stream = dateIndexTestDateTimeIndex
        .getRids(session, dateTwo)) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream = dateIndexTestDateTimeIndex
        .getRids(session, dateOne)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateIndex");
    try (var stream =
        dateIndexTestValueDateIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      Assert.assertEquals((stream.findAny().orElse(null)), dateDoc.getIdentity());
    }
    try (var stream =
        dateIndexTestValueDateIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateTimeIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateTimeIndex");
    try (var stream =
        dateIndexTestValueDateTimeIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream =
        dateIndexTestValueDateTimeIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateListIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateListIndex");

    try (var stream =
        dateIndexTestValueDateListIndex
            .getRids(session, new CompositeKey("v1", dateThree))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream =
        dateIndexTestValueDateListIndex
            .getRids(session, new CompositeKey("v1", dateFour))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }

    final var dateIndexTestValueDateTimeListIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateListIndex");
    try (var stream =
        dateIndexTestValueDateTimeListIndex

            .getRids(session, new CompositeKey("v1", dateThree))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream =
        dateIndexTestValueDateTimeListIndex

            .getRids(session, new CompositeKey("v1", dateFour))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }

    final var dateIndexTestDateHashIndexIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestDateHashIndex");
    try (var stream = dateIndexTestDateHashIndexIndex
        .getRids(session, dateOne)) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream = dateIndexTestDateHashIndexIndex
        .getRids(session, dateTwo)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestDateTimeHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestDateTimeHashIndex");
    try (var stream = dateIndexTestDateTimeHashIndex
        .getRids(session, dateTwo)) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream = dateIndexTestDateTimeHashIndex
        .getRids(session, dateOne)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateHashIndex");
    try (var stream =
        dateIndexTestValueDateHashIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream =
        dateIndexTestValueDateHashIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateTimeHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateTimeHashIndex");
    try (var stream =
        dateIndexTestValueDateTimeHashIndex

            .getRids(session, new CompositeKey("v1", dateTwo))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream =
        dateIndexTestValueDateTimeHashIndex

            .getRids(session, new CompositeKey("v1", dateOne))) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    final var dateIndexTestValueDateListHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateListHashIndex");

    try (var stream =
        dateIndexTestValueDateListHashIndex

            .getRids(session, new CompositeKey("v1", dateThree))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream =
        dateIndexTestValueDateListHashIndex

            .getRids(session, new CompositeKey("v1", dateFour))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }

    final var dateIndexTestValueDateTimeListHashIndex =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("DateIndexTestValueDateListHashIndex");
    try (var stream =
        dateIndexTestValueDateTimeListHashIndex

            .getRids(session, new CompositeKey("v1", dateThree))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
    try (var stream =
        dateIndexTestValueDateTimeListHashIndex

            .getRids(session, new CompositeKey("v1", dateFour))) {
      Assert.assertEquals(stream.findAny().orElse(null), dateDoc.getIdentity());
    }
  }
}
