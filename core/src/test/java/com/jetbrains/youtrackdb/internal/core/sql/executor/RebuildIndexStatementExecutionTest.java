package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import org.junit.Assert;
import org.junit.Test;

public class RebuildIndexStatementExecutionTest extends DbTestBase {

  @Test
  public void indexAfterRebuildShouldIncludeAllCollections() {
    // given

    var className = "IndexCollectionTest";
    graph.autoExecuteInTx(g -> g.addSchemaClass(className,
        __.addSchemaProperty("key", PropertyType.STRING)
            .addPropertyIndex(className + "index1", IndexType.NOT_UNIQUE),
        __.addSchemaProperty("value", PropertyType.INTEGER)
    ));

    session.begin();
    while (true) {
      var ele = session.newEntity(className);
      ele.setProperty("key", "a");
      ele.setProperty("value", 1);

      var ele1 = session.newEntity(className);
      ele1.setProperty("key", "a");
      ele1.setProperty("value", 2);

      if (ele1.getIdentity().getCollectionId() != ele.getIdentity().getCollectionId()) {
        ele.delete();
        ele1.delete();
        continue;
      }
      break;
    }
    session.commit();

    session.begin();
    // when
    var result = session.execute("rebuild index " + className + "index1");
    Assert.assertTrue(result.hasNext());
    var resultRecord = result.next();
    Assert.assertEquals(2L, resultRecord.<Object>getProperty("totalIndexed"));
    Assert.assertFalse(result.hasNext());
    assertEquals(
        2, session.query("select from " + className + " where key = 'a'").stream().toList().size());
    session.commit();
  }
}
