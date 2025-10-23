package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Test;

public class ChainIndexFetchTest extends DbTestBase {
  @Test
  public void testFetchChaninedIndex() {
    graph.executeInTx(g -> {
      var schemaProperty = (YTDBSchemaProperty) g.createSchemaClass("BaseClass").
          createSchemaProperty("ref", PropertyType.LINK).next();
      var linkedClass = (YTDBSchemaClass) g.createSchemaClass("LinkedClass").next();
      var id = linkedClass.createSchemaProperty("id", PropertyType.STRING);
      id.createIndex("idIndex", IndexType.UNIQUE);

      schemaProperty.linkedClass(linkedClass);
      schemaProperty.createIndex("propertyIndex", IndexType.NOT_UNIQUE);
    });

    session.begin();
    var doc = (EntityImpl) session.newEntity("LinkedClass");
    doc.setProperty("id", "referred");
    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    var doc1 = (EntityImpl) session.newEntity("BaseClass");
    doc1.setProperty("ref", doc);

    session.commit();

    var res = session.query(" select from BaseClass where ref.id ='wrong_referred' ");

    assertEquals(0, res.stream().count());
  }
}
