package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.text.ParseException;
import org.junit.Assert;
import org.junit.Test;

public class UniqueHashIndexForDateTest extends DbTestBase {

  @Test
  public void testSimpleUniqueDateIndex() throws ParseException {
    var clazz = session.getMetadata().getSchema().createClass("test_edge");
    var prop = clazz.createProperty("date", PropertyType.DATETIME);
    prop.createIndex(INDEX_TYPE.UNIQUE);
    session.begin();
    var doc = (EntityImpl) session.newEntity("test_edge");
    doc.setProperty("date", "2015-03-24 08:54:49");

    var doc1 = (EntityImpl) session.newEntity("test_edge");
    doc1.setProperty("date", "2015-03-24 08:54:49");

    try {
      session.commit();
      Assert.fail("expected exception for duplicate ");
    } catch (BaseException e) {

    }
  }
}
