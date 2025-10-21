package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.exception.TooBigIndexKeyException;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

public class BigKeyIndexTest extends DbTestBase {
  @Test
  public void testBigKey() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("One").
        createSchemaProperty("two", PropertyType.STRING)
        .createPropertyIndex("twoIndex",
            IndexType.NOT_UNIQUE));

    for (var i = 0; i < 100; i++) {
      session.begin();
      var doc = session.newInstance("One");
      var bigValue = new StringBuilder(i % 1000 + "one10000");
      for (var z = 0; z < 218; z++) {
        bigValue.append("one").append(z);
      }
      doc.setProperty("two", bigValue.toString());

      session.commit();
    }
  }

  @Test(expected = TooBigIndexKeyException.class)
  public void testTooBigKey() {
    graph.autoExecuteInTx(
        g ->
            g.createSchemaClass("One").
                createSchemaProperty("two", PropertyType.STRING)
                .createPropertyIndex("twoIndex", IndexType.NOT_UNIQUE));

    session.begin();
    var doc = session.newInstance("One");
    var bigValue = new StringBuilder();
    for (var z = 0; z < 5000; z++) {
      bigValue.append("one").append(z);
    }
    doc.setProperty("two", bigValue.toString());
    session.commit();
  }
}
