package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BinaryComparator;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BytesContainer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import org.junit.Assert;

public abstract class AbstractComparatorTest extends DbTestBase {

  protected EntitySerializer serializer =
      RecordSerializerBinary.INSTANCE.getCurrentSerializer();
  protected BinaryComparator comparator = serializer.getComparator();

  protected void testEquals(DatabaseSessionInternal db, PropertyTypeInternal sourceType,
      PropertyTypeInternal destType) {
    try {
      Assert.assertTrue(comparator.isEqual(db, field(db, sourceType, 10), field(db, destType, 10)));
      Assert.assertFalse(comparator.isEqual(db, field(db, sourceType, 10), field(db, destType, 9)));
      Assert.assertFalse(
          comparator.isEqual(db, field(db, sourceType, 10), field(db, destType, 11)));
    } catch (AssertionError e) {
      System.out.println("ERROR: testEquals(" + sourceType + "," + destType + ")");
      throw e;
    }
  }

  protected BinaryField field(DatabaseSessionInternal db, final PropertyTypeInternal type,
      final Object value) {
    return field(db, type, value, null);
  }

  protected BinaryField field(DatabaseSessionInternal db, final PropertyTypeInternal type,
      final Object value,
      Collate collate) {
    var bytes = new BytesContainer();
    bytes.offset = serializer.serializeValue(db, bytes, value,
        type, null, null, null);
    return new BinaryField(null, type, bytes, collate);
  }
}
