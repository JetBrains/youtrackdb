package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

public class BinaryComparatorCompareTest extends AbstractComparatorTest {

  @Test
  public void testInteger() {
    testCompareNumber(PropertyTypeInternal.INTEGER, 10);
  }

  @Test
  public void testLong() {
    testCompareNumber(PropertyTypeInternal.LONG, 10L);
  }

  @Test
  public void testShort() {
    testCompareNumber(PropertyTypeInternal.SHORT, (short) 10);
  }

  @Test
  public void testByte() {
    testCompareNumber(PropertyTypeInternal.BYTE, (byte) 10);
  }

  @Test
  public void testFloat() {
    testCompareNumber(PropertyTypeInternal.FLOAT, 10f);
  }

  @Test
  public void testDouble() {
    testCompareNumber(PropertyTypeInternal.DOUBLE, 10d);
  }

  @Test
  public void testDatetime() throws ParseException {
    testCompareNumber(PropertyTypeInternal.DATETIME, 10L);

    final var format =
        new SimpleDateFormat(StorageConfiguration.DEFAULT_DATETIME_FORMAT);
    format.setTimeZone(DateHelper.getDatabaseTimeZone(session));

    var now1 = format.format(new Date());
    var now = format.parse(now1);

    Assert.assertEquals(
        0, comparator.compare(session,
            field(session, PropertyTypeInternal.DATETIME, now),
            field(session, PropertyTypeInternal.STRING, format.format(now))));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.DATETIME, new Date(now.getTime() + 1)),
            field(session, PropertyTypeInternal.STRING, format.format(now)))
            > 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.DATETIME, new Date(now.getTime() - 1)),
            field(session, PropertyTypeInternal.STRING, format.format(now)))
            < 0);
  }

  @Test
  public void testBinary() {
    final var b1 = new byte[]{0, 1, 2, 3};
    final var b2 = new byte[]{0, 1, 2, 4};
    final var b3 = new byte[]{1, 1, 2, 4};

    Assert.assertEquals(
        "For values " + field(session, PropertyTypeInternal.BINARY, b1) + " and " + field(session,
            PropertyTypeInternal.BINARY, b1),
        0,
        comparator.compare(session, field(session, PropertyTypeInternal.BINARY, b1),
            field(session, PropertyTypeInternal.BINARY, b1)));
    Assert.assertFalse(
        comparator.compare(session, field(session, PropertyTypeInternal.BINARY, b1),
            field(session, PropertyTypeInternal.BINARY, b2))
            > 1);
    Assert.assertFalse(
        comparator.compare(session, field(session, PropertyTypeInternal.BINARY, b1),
            field(session, PropertyTypeInternal.BINARY, b3))
            > 1);
  }

  @Test
  public void testLinks() {
    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2))));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.LINK, new RecordId(2, 1)))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.LINK, new RecordId(0, 2)))
            > 0);

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.STRING, new RecordId(1, 2).toString())));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.STRING, new RecordId(0, 2).toString()))
            > 0);
  }

  @Test
  public void testString() {
    Assert.assertEquals(
        0, comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test")));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test2"),
            field(session, PropertyTypeInternal.STRING, "test"))
            > 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test2"))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "t"),
            field(session, PropertyTypeInternal.STRING, "te"))
            < 0);

    // DEF COLLATE
    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test")));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test2", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test"))
            > 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test2"))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "t", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "te"))
            < 0);

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate())));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test2", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()))
            > 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test2", new DefaultCollate()))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "t", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "te", new DefaultCollate()))
            < 0);

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate())));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test2"),
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()))
            > 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test2", new DefaultCollate()))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "t"),
            field(session, PropertyTypeInternal.STRING, "te", new DefaultCollate()))
            < 0);

    // CASE INSENSITIVE COLLATE
    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test", new CaseInsensitiveCollate())));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test2"),
            field(session, PropertyTypeInternal.STRING, "test", new CaseInsensitiveCollate()))
            > 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test2", new CaseInsensitiveCollate()))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "t"),
            field(session, PropertyTypeInternal.STRING, "te", new CaseInsensitiveCollate()))
            < 0);

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "TEST", new CaseInsensitiveCollate())));
    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "TEST"),
            field(session, PropertyTypeInternal.STRING, "TEST", new CaseInsensitiveCollate())));
    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "TE"),
            field(session, PropertyTypeInternal.STRING, "te", new CaseInsensitiveCollate())));

    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test2"),
            field(session, PropertyTypeInternal.STRING, "TEST", new CaseInsensitiveCollate()))
            > 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "TEST2", new CaseInsensitiveCollate()))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.STRING, "t"),
            field(session, PropertyTypeInternal.STRING, "tE", new CaseInsensitiveCollate()))
            < 0);
  }

  @Test
  public void testDecimal() {
    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10))));
    Assert.assertEquals(
        -1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(11))));
    Assert.assertEquals(
        1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(9))));

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.SHORT, (short) 10)));
    Assert.assertEquals(
        -1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.SHORT, (short) 11)));
    Assert.assertEquals(
        1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.SHORT,
                (short) 9)));

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.INTEGER, 10)));
    Assert.assertEquals(
        -1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.INTEGER, 11)));
    Assert.assertEquals(
        1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.INTEGER, 9)));

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.LONG, 10L)));
    Assert.assertEquals(
        -1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.LONG, 11L)));
    Assert.assertEquals(
        1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.LONG, 9L)));

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.FLOAT, 10F)));
    Assert.assertEquals(
        -1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.FLOAT, 11F)));
    Assert.assertEquals(
        1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.FLOAT, 9F)));

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DOUBLE, 10.0)));
    Assert.assertEquals(
        -1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DOUBLE, 11.0)));
    Assert.assertEquals(
        1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DOUBLE, 9.0)));

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.BYTE, (byte) 10)));
    Assert.assertEquals(
        -1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.BYTE, (byte) 11)));
    Assert.assertEquals(
        1,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.BYTE, (byte) 9)));

    Assert.assertEquals(
        0,
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.STRING, "10")));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.STRING, "11"))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.STRING, "9"))
            < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(20)),
            field(session, PropertyTypeInternal.STRING, "11"))
            > 0);
  }

  @Test
  public void testBoolean() {
    Assert.assertEquals(
        0, comparator.compare(session,
            field(session, PropertyTypeInternal.BOOLEAN, true),
            field(session, PropertyTypeInternal.BOOLEAN, true)));
    Assert.assertEquals(
        1, comparator.compare(session,
            field(session, PropertyTypeInternal.BOOLEAN, true),
            field(session, PropertyTypeInternal.BOOLEAN, false)));
    Assert.assertEquals(
        -1, comparator.compare(session,
            field(session, PropertyTypeInternal.BOOLEAN, false),
            field(session, PropertyTypeInternal.BOOLEAN, true)));

    Assert.assertEquals(
        0, comparator.compare(session,
            field(session, PropertyTypeInternal.BOOLEAN, true),
            field(session, PropertyTypeInternal.STRING, "true")));
    Assert.assertEquals(
        0, comparator.compare(session,
            field(session, PropertyTypeInternal.BOOLEAN, false),
            field(session, PropertyTypeInternal.STRING, "false")));
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.BOOLEAN, false),
            field(session, PropertyTypeInternal.STRING, "true")) < 0);
    Assert.assertTrue(
        comparator.compare(session,
            field(session, PropertyTypeInternal.BOOLEAN, true),
            field(session, PropertyTypeInternal.STRING, "false")) > 0);
  }

  protected void testCompareNumber(PropertyTypeInternal sourceType, Number value10AsSourceType) {
    var numberTypes =
        new PropertyTypeInternal[]{
            PropertyTypeInternal.BYTE,
            PropertyTypeInternal.DOUBLE,
            PropertyTypeInternal.FLOAT,
            PropertyTypeInternal.SHORT,
            PropertyTypeInternal.INTEGER,
            PropertyTypeInternal.LONG,
            PropertyTypeInternal.DATETIME
        };

    for (var t : numberTypes) {
      if (sourceType == PropertyTypeInternal.DATETIME && t == PropertyTypeInternal.BYTE)
      // SKIP TEST
      {
        continue;
      }

      testCompare(sourceType, t);
    }

    for (var t : numberTypes) {
      testCompare(t, sourceType);
    }

    // STRING
    if (sourceType != PropertyTypeInternal.DATETIME) {
      Assert.assertEquals(
          0,
          comparator.compare(session,
              field(session, sourceType, value10AsSourceType),
              field(session, PropertyTypeInternal.STRING, value10AsSourceType.toString())));
      Assert.assertTrue(
          comparator.compare(session,
              field(session, sourceType, value10AsSourceType),
              field(session, PropertyTypeInternal.STRING, "9"))
              < 0);
      Assert.assertTrue(
          comparator.compare(session,
              field(session, sourceType, value10AsSourceType),
              field(session, PropertyTypeInternal.STRING, "11"))
              < 0);
      Assert.assertTrue(
          comparator.compare(session,
              field(session, sourceType, value10AsSourceType.intValue() * 2),
              field(session, PropertyTypeInternal.STRING,
                  "11"))
              > 0);

      Assert.assertEquals(
          0,
          comparator.compare(session,
              field(session, PropertyTypeInternal.STRING, value10AsSourceType.toString()),
              field(session, sourceType, value10AsSourceType)));
      Assert.assertTrue(
          comparator.compare(session,
              field(session, PropertyTypeInternal.STRING, value10AsSourceType.toString()),
              field(session, sourceType, value10AsSourceType.intValue() - 1))
              < 0);
      Assert.assertTrue(
          comparator.compare(session,
              field(session, PropertyTypeInternal.STRING, value10AsSourceType.toString()),
              field(session, sourceType, value10AsSourceType.intValue() + 1))
              < 0);
      Assert.assertTrue(
          comparator.compare(session,
              field(session, PropertyTypeInternal.STRING, "" + value10AsSourceType.intValue() * 2),
              field(session, sourceType, value10AsSourceType.intValue()))
              > 0);
    }
  }

  protected void testCompare(PropertyTypeInternal sourceType, PropertyTypeInternal destType) {
    testCompare(sourceType, destType, 10);
  }

  protected void testCompare(PropertyTypeInternal sourceType, PropertyTypeInternal destType,
      final Number value) {
    try {
      Assert.assertEquals(
          0,
          comparator.compare(
              session, field(session, sourceType, value), field(session, destType, value)));
      Assert.assertEquals(
          1,
          comparator.compare(session,
              field(session, sourceType, value), field(session, destType, value.intValue() - 1)));
      Assert.assertEquals(
          -1,
          comparator.compare(session,
              field(session, sourceType, value), field(session, destType, value.intValue() + 1)));
    } catch (AssertionError e) {
      System.out.println("ERROR: testCompare(" + sourceType + "," + destType + "," + value + ")");
      System.out.flush();
      throw e;
    }
  }
}
