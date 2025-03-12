package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class TestOrderByIndexPropDesc extends DbTestBase {

  private static final String DOCUMENT_CLASS_NAME = "MyDocument";
  private static final String PROP_INDEXED_STRING = "dateProperty";

  public void beforeTest() throws Exception {
    super.beforeTest();
    var oclass = session.getMetadata().getSchema().createClass(DOCUMENT_CLASS_NAME);
    oclass.createProperty(PROP_INDEXED_STRING, PropertyType.INTEGER);
    oclass.createIndex("index", INDEX_TYPE.NOTUNIQUE, PROP_INDEXED_STRING);
  }

  @Test
  public void worksFor1000() {
    test(1000);
  }

  @Test
  public void worksFor10000() {
    test(50000);
  }

  private void test(int count) {
    EntityImpl doc;
    for (var i = 0; i < count; i++) {
      session.begin();
      doc = session.newInstance(DOCUMENT_CLASS_NAME);
      doc.field(PROP_INDEXED_STRING, i);
      session.commit();
    }

    var result =
        session.query(
            "select from " + DOCUMENT_CLASS_NAME + " order by " + PROP_INDEXED_STRING + " desc");

    Assert.assertEquals(count, result.stream().count());
  }
}
