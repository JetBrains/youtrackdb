package com.jetbrains.youtrack.db.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Test;

public class ChainIndexFetchTest extends DbTestBase {

  @Test
  public void testFetchChaninedIndex() {
    var baseClass = session.getMetadata().getSchema().createClass("BaseClass");
    var propr = baseClass.createProperty("ref", PropertyType.LINK);

    var linkedClass = session.getMetadata().getSchema().createClass("LinkedClass");
    var id = linkedClass.createProperty("id", PropertyType.STRING);
    id.createIndex(INDEX_TYPE.UNIQUE);

    propr.setLinkedClass(linkedClass);
    propr.createIndex(INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var doc = (EntityImpl) session.newEntity(linkedClass);
    doc.setProperty("id", "referred");
    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    var doc1 = (EntityImpl) session.newEntity(baseClass);
    doc1.setProperty("ref", doc);

    session.commit();

    var res = session.query(" select from BaseClass where ref.id ='wrong_referred' ");

    assertEquals(0, res.stream().count());
  }
}
