package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

public class BinaryComparatorEqualsTest extends AbstractComparatorTest {

  @Test
  public void testInteger() {
    testEquals(PropertyTypeInternal.INTEGER, 10);
  }

  @Test
  public void testLong() {
    testEquals(PropertyTypeInternal.LONG, 10L);
  }

  @Test
  public void testShort() {
    testEquals(PropertyTypeInternal.SHORT, (short) 10);
  }

  @Test
  public void testByte() {
    testEquals(PropertyTypeInternal.BYTE, (byte) 10);
  }

  @Test
  public void testFloat() {
    testEquals(PropertyTypeInternal.FLOAT, 10f);
  }

  @Test
  public void testDouble() {
    testEquals(PropertyTypeInternal.DOUBLE, 10d);
  }

  @Test
  public void testDatetime() throws ParseException {
    testEquals(PropertyTypeInternal.DATETIME, 10L);

    final var format =
        new SimpleDateFormat(StorageConfiguration.DEFAULT_DATETIME_FORMAT);

    var now1 = format.format(new Date());
    var now = format.parse(now1);

    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.DATETIME, now),
            field(session, PropertyTypeInternal.STRING, format.format(now))));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.DATETIME, new Date(now.getTime() + 1)),
            field(session, PropertyTypeInternal.STRING, format.format(now))));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.DATETIME, new Date(now.getTime() - 1)),
            field(session, PropertyTypeInternal.STRING, format.format(now))));
  }

  @Test
  public void testBinary() throws ParseException {
    final var b1 = new byte[]{0, 1, 2, 3};
    final var b2 = new byte[]{0, 1, 2, 4};
    final var b3 = new byte[]{1, 1, 2, 4};

    Assert.assertTrue(
        comparator.isEqual(
            session, field(session, PropertyTypeInternal.BINARY, b1),
            field(session, PropertyTypeInternal.BINARY, b1)));
    Assert.assertFalse(
        comparator.isEqual(
            session, field(session, PropertyTypeInternal.BINARY, b1),
            field(session, PropertyTypeInternal.BINARY, b2)));
    Assert.assertFalse(
        comparator.isEqual(
            session, field(session, PropertyTypeInternal.BINARY, b1),
            field(session, PropertyTypeInternal.BINARY, b3)));
  }

  @Test
  public void testLinks() throws ParseException {
    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2))));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.LINK, new RecordId(2, 1))));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.LINK, new RecordId(0, 2))));

    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.STRING, new RecordId(1, 2).toString())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.LINK, new RecordId(1, 2)),
            field(session, PropertyTypeInternal.STRING, new RecordId(0, 2).toString())));
  }

  @Test
  public void testString() {
    Assert.assertTrue(
        comparator.isEqual(
            session, field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING,
                "test")));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test2"),
            field(session, PropertyTypeInternal.STRING, "test")));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test2")));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "t"),
            field(session, PropertyTypeInternal.STRING, "te")));

    // DEF COLLATE
    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test")));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test2", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test")));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test2")));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "t", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "te")));

    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test2", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "test2", new DefaultCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "t", new DefaultCollate()),
            field(session, PropertyTypeInternal.STRING, "te", new DefaultCollate())));

    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test2"),
            field(session, PropertyTypeInternal.STRING, "test", new DefaultCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test2", new DefaultCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "t"),
            field(session, PropertyTypeInternal.STRING, "te", new DefaultCollate())));

    // CASE INSENSITIVE COLLATE
    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test", new CaseInsensitiveCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test2"),
            field(session, PropertyTypeInternal.STRING, "test", new CaseInsensitiveCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "test2", new CaseInsensitiveCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "t"),
            field(session, PropertyTypeInternal.STRING, "te", new CaseInsensitiveCollate())));

    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "TEST", new CaseInsensitiveCollate())));
    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "TEST"),
            field(session, PropertyTypeInternal.STRING, "TEST", new CaseInsensitiveCollate())));
    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "TE"),
            field(session, PropertyTypeInternal.STRING, "te", new CaseInsensitiveCollate())));

    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test2"),
            field(session, PropertyTypeInternal.STRING, "TEST", new CaseInsensitiveCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "test"),
            field(session, PropertyTypeInternal.STRING, "TEST2", new CaseInsensitiveCollate())));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.STRING, "t"),
            field(session, PropertyTypeInternal.STRING, "tE", new CaseInsensitiveCollate())));
  }

  @Test
  public void testDecimal() {
    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10))));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(11))));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(10)),
            field(session, PropertyTypeInternal.DECIMAL, new BigDecimal(9))));
  }

  @Test
  public void testBoolean() {
    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.BOOLEAN, true),
            field(session, PropertyTypeInternal.BOOLEAN, true)));
    Assert.assertFalse(
        comparator.isEqual(
            session, field(session, PropertyTypeInternal.BOOLEAN, true),
            field(session, PropertyTypeInternal.BOOLEAN,
                false)));
    Assert.assertFalse(
        comparator.isEqual(
            session, field(session, PropertyTypeInternal.BOOLEAN, false),
            field(session, PropertyTypeInternal.BOOLEAN,
                true)));

    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.BOOLEAN, true),
            field(session, PropertyTypeInternal.STRING, "true")));
    Assert.assertTrue(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.BOOLEAN, false),
            field(session, PropertyTypeInternal.STRING, "false")));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.BOOLEAN, false),
            field(session, PropertyTypeInternal.STRING, "true")));
    Assert.assertFalse(
        comparator.isEqual(session,
            field(session, PropertyTypeInternal.BOOLEAN, true),
            field(session, PropertyTypeInternal.STRING, "false")));
  }

  @Test
  public void testBinaryFieldCopy() {
    final var f = field(session, PropertyTypeInternal.BYTE, 10,
        new CaseInsensitiveCollate()).copy();
    Assert.assertEquals(f.type, PropertyTypeInternal.BYTE);
    Assert.assertNotNull(f.bytes);
    Assert.assertEquals(f.collate.getName(), CaseInsensitiveCollate.NAME);
  }

  @Test
  public void testBinaryComparable() {
    for (var t : PropertyTypeInternal.values()) {
      switch (t) {
        case INTEGER:
        case LONG:
        case DATETIME:
        case SHORT:
        case STRING:
        case DOUBLE:
        case FLOAT:
        case BYTE:
        case BOOLEAN:
        case DATE:
        case BINARY:
        case LINK:
        case DECIMAL:
          Assert.assertTrue(
              comparator.isBinaryComparable(t));
          break;

        default:
          Assert.assertFalse(
              comparator.isBinaryComparable(t));
      }
    }
  }

  protected void testEquals(PropertyTypeInternal sourceType, Number value10AsSourceType) {
    var numberTypes =
        new PropertyTypeInternal[]{PropertyTypeInternal.BYTE, PropertyTypeInternal.DOUBLE,
            PropertyTypeInternal.FLOAT,
            PropertyTypeInternal.SHORT, PropertyTypeInternal.INTEGER,
            PropertyTypeInternal.LONG};

    for (var t : numberTypes) {
      if (sourceType == PropertyTypeInternal.DATETIME && t == PropertyTypeInternal.BYTE)
      // SKIP TEST
      {
        continue;
      }

      testEquals(session, sourceType, t);
    }

    for (var t : numberTypes) {
      testEquals(session, t, sourceType);
    }

    if (sourceType != PropertyTypeInternal.DATETIME) {
      // STRING
      Assert.assertTrue(
          comparator.isEqual(session,
              field(session, sourceType, value10AsSourceType),
              field(session, PropertyTypeInternal.STRING, value10AsSourceType.toString())));
      Assert.assertFalse(
          comparator.isEqual(session,
              field(session, sourceType, value10AsSourceType),
              field(session, PropertyTypeInternal.STRING, "9")));
      Assert.assertFalse(
          comparator.isEqual(session,
              field(session, sourceType, value10AsSourceType),
              field(session, PropertyTypeInternal.STRING, "11")));
      Assert.assertFalse(
          comparator.isEqual(session,
              field(session, sourceType, value10AsSourceType.intValue() * 2),
              field(session, PropertyTypeInternal.STRING, "11")));

      Assert.assertTrue(
          comparator.isEqual(session,
              field(session, PropertyTypeInternal.STRING, value10AsSourceType.toString()),
              field(session, sourceType, value10AsSourceType)));
      Assert.assertFalse(
          comparator.isEqual(session,
              field(session, PropertyTypeInternal.STRING, value10AsSourceType.toString()),
              field(session, sourceType, value10AsSourceType.intValue() - 1)));
      Assert.assertFalse(
          comparator.isEqual(session,
              field(session, PropertyTypeInternal.STRING, value10AsSourceType.toString()),
              field(session, sourceType, value10AsSourceType.intValue() + 1)));
      Assert.assertFalse(
          comparator.isEqual(session,
              field(session, PropertyTypeInternal.STRING, "" + value10AsSourceType.intValue() * 2),
              field(session, sourceType, value10AsSourceType.intValue())));
    }
  }

  @Override
  protected void testEquals(DatabaseSessionEmbedded db, PropertyTypeInternal sourceType,
      PropertyTypeInternal destType) {
    try {
      Assert.assertTrue(comparator.isEqual(db, field(db, sourceType, 10), field(db, destType, 10)));
      Assert.assertFalse(comparator.isEqual(db, field(db, sourceType, 10), field(db, destType, 9)));
      Assert.assertFalse(
          comparator.isEqual(db, field(db, sourceType, 10), field(db, destType, 11)));
    } catch (AssertionError e) {
      System.out.println("ERROR: testEquals(" + sourceType + "," + destType + ")");
      System.out.flush();
      throw e;
    }
  }
}
