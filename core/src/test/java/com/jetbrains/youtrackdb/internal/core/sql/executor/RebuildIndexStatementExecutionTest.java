package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

public class RebuildIndexStatementExecutionTest extends DbTestBase {

  @Test
  public void indexAfterRebuildShouldIncludeAllCollections() {
    // given
    Schema schema = session.getMetadata().getSchema();
    var className = "IndexCollectionTest";

    var oclass = schema.createClass(className);
    oclass.createProperty("key", PropertyType.STRING);
    oclass.createProperty("value", PropertyType.INTEGER);
    oclass.createIndex(className + "index1", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

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

    // when
    var result = session.execute("rebuild index " + className + "index1");
    Assert.assertTrue(result.hasNext());
    var resultRecord = result.next();
    Assert.assertEquals(2L, resultRecord.<Object>getProperty("totalIndexed"));
    Assert.assertFalse(result.hasNext());

    session.begin();
    assertEquals(
        2, session.query("select from " + className + " where key = 'a'").stream().toList().size());
    session.commit();
  }
}
