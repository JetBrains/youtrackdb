package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import org.junit.Assert;
import org.junit.Test;

public class DefaultValueSerializationTest extends DbTestBase {

  @Test
  public void testKeepValueSerialization() {
    // create example schema
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("ClassC");

    var prop = classA.createProperty("name", PropertyType.STRING);
    prop.setDefaultValue("uuid()");

    session.begin();
    var doc = (EntityImpl) session.newEntity("ClassC");

    var val = doc.toStream();
    var doc1 = (EntityImpl) session.newEntity();
    RecordInternal.unsetDirty(doc1);
    doc1.fromStream(val);
    doc1.deserializeFields();
    Assert.assertEquals(doc.field("name").toString(), doc1.field("name").toString());
    session.rollback();
  }
}
