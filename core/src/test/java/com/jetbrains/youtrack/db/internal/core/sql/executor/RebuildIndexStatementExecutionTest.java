package com.jetbrains.youtrack.db.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class RebuildIndexStatementExecutionTest extends DbTestBase {

  @Test
  public void indexAfterRebuildShouldIncludeAllClusters() {
    // given
    Schema schema = session.getMetadata().getSchema();
    var className = "IndexClusterTest";

    var oclass = schema.createClass(className);
    oclass.createProperty(session, "key", PropertyType.STRING);
    oclass.createProperty(session, "value", PropertyType.INTEGER);
    oclass.createIndex(session, className + "index1", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

    session.begin();
    var ele = session.newEntity(className);
    ele.setProperty("key", "a");
    ele.setProperty("value", 1);
    session.commit();

    session.begin();
    var ele1 = session.newEntity(className);
    ele1.setProperty("key", "a");
    ele1.setProperty("value", 2);
    session.commit();

    Assert.assertNotEquals(ele1.getIdentity().getClusterId(), ele.getIdentity().getClusterId());

    session.begin();
    // when
    var result = session.command("rebuild index " + className + "index1");
    Assert.assertTrue(result.hasNext());
    var resultRecord = result.next();
    Assert.assertEquals(2L, resultRecord.<Object>getProperty("totalIndexed"));
    Assert.assertFalse(result.hasNext());
    assertEquals(
        2, session.query("select from " + className + " where key = 'a'").stream().toList().size());
    session.commit();
  }
}
