package com.jetbrains.youtrack.db.auto;

import static org.testng.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test(groups = {"index"})
public class IndexClusterTest extends BaseDBTest {

  @Parameters(value = "remote")
  public IndexClusterTest(boolean remote) {
    super(remote);
  }

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
    session.newInstance(className).field("key", "a").field("value", 1);

    session.commit();

    var clId = session.addCluster(className + "secondCluster");
    oclass.addClusterId(session, clId);

    session.begin();
    session
        .newInstance(className)
        .field("key", "a")
        .field("value", 2);
    session.commit();

    // when
    session.command("rebuild index " + className + "index1").close();
    assertEquals(
        session.query("select from " + className + " where key = 'a'").stream().count(), 2);
  }
}
