package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
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

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("DateIndexTest",
                __.createSchemaProperty("dateField", PropertyType.DATE)
                    .createPropertyIndex("DateIndexTestDateIndex", IndexType.UNIQUE),
                __.createSchemaProperty("dateTimeField", PropertyType.DATETIME)
                    .createPropertyIndex("DateIndexTestDateTimeIndex", IndexType.UNIQUE),
                __.createSchemaProperty("dateList", PropertyType.EMBEDDEDLIST,
                    PropertyType.DATE),
                __.createSchemaProperty("dateTimeList", PropertyType.EMBEDDEDLIST,
                    PropertyType.DATETIME),
                __.createSchemaProperty("value", PropertyType.STRING)
            ).createClassIndex("DateIndexTestValueDateIndex", IndexType.UNIQUE, "value", "dateField")
            .createClassIndex("DateIndexTestValueDateTimeIndex", IndexType.UNIQUE, "value",
                "dateTimeField")
            .createClassIndex("DateIndexTestValueDateListIndex", IndexType.UNIQUE, "value",
                "dateList")
            .createClassIndex("DateIndexTestValueDateTimeListIndex", IndexType.UNIQUE, "value",
                "dateTimeList")
    );
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
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("DateIndexTestDateIndex");
    try (var iterator = dateIndexTestDateIndex.getRids(session, dateOne)) {
      Assert.assertEquals(YTDBIteratorUtils.findFirst(iterator).orElse(null),
          dateDoc.getIdentity());
    }
    try (var iterator = dateIndexTestDateIndex.getRids(session, dateTwo)) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    final var dateIndexTestDateTimeIndex =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("DateIndexTestDateTimeIndex");
    try (var iterator = dateIndexTestDateTimeIndex.getRids(session, dateTwo)) {
      Assert.assertEquals(YTDBIteratorUtils.findFirst(iterator).orElse(null),
          dateDoc.getIdentity());
    }
    try (var iterator = dateIndexTestDateTimeIndex
        .getRids(session, dateOne)) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    final var dateIndexTestValueDateIndex =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("DateIndexTestValueDateIndex");
    try (var iterator =
        dateIndexTestValueDateIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      Assert.assertEquals((YTDBIteratorUtils.findFirst(iterator).orElse(null)),
          dateDoc.getIdentity());
    }
    try (var iterator =
        dateIndexTestValueDateIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    final var dateIndexTestValueDateTimeIndex =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("DateIndexTestValueDateTimeIndex");
    try (var iterator =
        dateIndexTestValueDateTimeIndex
            .getRids(session, new CompositeKey("v1", dateTwo))) {
      Assert.assertEquals(YTDBIteratorUtils.findFirst(iterator).orElse(null),
          dateDoc.getIdentity());
    }
    try (var iterator =
        dateIndexTestValueDateTimeIndex
            .getRids(session, new CompositeKey("v1", dateOne))) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    final var dateIndexTestValueDateListIndex =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("DateIndexTestValueDateListIndex");

    try (var iterator =
        dateIndexTestValueDateListIndex
            .getRids(session, new CompositeKey("v1", dateThree))) {
      Assert.assertEquals(YTDBIteratorUtils.findFirst(iterator).orElse(null),
          dateDoc.getIdentity());
    }
    try (var iterator =
        dateIndexTestValueDateListIndex
            .getRids(session, new CompositeKey("v1", dateFour))) {
      Assert.assertEquals(YTDBIteratorUtils.findFirst(iterator).orElse(null),
          dateDoc.getIdentity());
    }

    final var dateIndexTestValueDateTimeListIndex =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("DateIndexTestValueDateListIndex");
    try (var iterator = dateIndexTestValueDateTimeListIndex
        .getRids(session, new CompositeKey("v1", dateThree))) {
      Assert.assertEquals(YTDBIteratorUtils.findFirst(iterator).orElse(null),
          dateDoc.getIdentity());
    }
    try (var iterator =
        dateIndexTestValueDateTimeListIndex.getRids(session, new CompositeKey("v1", dateFour))) {
      Assert.assertEquals(YTDBIteratorUtils.findFirst(iterator).orElse(null),
          dateDoc.getIdentity());
    }
  }
}
