package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class TestOrderByIndexPropDesc extends DbTestBase {

  private static final String DOCUMENT_CLASS_NAME = "MyDocument";
  private static final String PROP_INDEXED_STRING = "dateProperty";

  public void beforeTest() throws Exception {
    super.beforeTest();
    graph.autoExecuteInTx(g ->
        g.addSchemaClass(DOCUMENT_CLASS_NAME)
            .addSchemaProperty(PROP_INDEXED_STRING, PropertyType.STRING)
            .addPropertyIndex("index", IndexType.NOT_UNIQUE));
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
      doc.setProperty(PROP_INDEXED_STRING, i);
      session.commit();
    }

    var result =
        session.query(
            "select from " + DOCUMENT_CLASS_NAME + " order by " + PROP_INDEXED_STRING + " desc");

    Assert.assertEquals(count, result.stream().count());
  }
}
