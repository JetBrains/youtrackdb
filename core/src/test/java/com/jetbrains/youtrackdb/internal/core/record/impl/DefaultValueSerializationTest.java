package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
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
    final var rec = (RecordAbstract) doc1;
    rec.unsetDirty();
    doc1.fromStream(val);
    doc1.deserializeProperties();
    Assert.assertEquals(doc.getProperty("name").toString(), doc1.getProperty("name").toString());
    session.rollback();
  }
}
