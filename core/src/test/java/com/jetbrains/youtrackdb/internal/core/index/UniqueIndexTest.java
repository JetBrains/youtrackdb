package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.Triple;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class UniqueIndexTest extends DbTestBase {

  @Test
  public void compositeIndexWithEdgesTestOne() {
    var linkClass = session.createLightweightEdgeClass("Link");

    var entityClass = session.createVertexClass("Entity");
    var edgeOutPropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, "Link");
    entityClass.createProperty(edgeOutPropertyName, PropertyType.LINKBAG);

    entityClass.createProperty("type", PropertyType.STRING);
    entityClass.createIndex("typeLink", SchemaClass.INDEX_TYPE.UNIQUE, "type",
        edgeOutPropertyName);

    session.begin();
    var firstEntity = session.newVertex(entityClass);
    firstEntity.setProperty("type", "type1");

    var secondEntity = session.newVertex(entityClass);
    secondEntity.setProperty("type", "type2");

    var thirdEntity = session.newVertex(entityClass);
    thirdEntity.setProperty("type", "type3");

    firstEntity.addLightWeightEdge(thirdEntity, linkClass);
    secondEntity.addLightWeightEdge(thirdEntity, linkClass);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    secondEntity = activeTx.load(secondEntity);
    secondEntity.setProperty("type", "type1");
    try {
      session.commit();
      Assert.fail();
    } catch (RecordDuplicatedException e) {
      session.rollback();
    }
  }

  @Test
  public void compositeIndexWithEdgesTestTwo() {
    var linkClass = session.createLightweightEdgeClass("Link");

    var entityClass = session.createVertexClass("Entity");
    var edgeOutPropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, "Link");
    entityClass.createProperty(edgeOutPropertyName, PropertyType.LINKBAG);

    entityClass.createProperty("type", PropertyType.STRING);
    entityClass.createIndex("typeLink", SchemaClass.INDEX_TYPE.UNIQUE, "type",
        edgeOutPropertyName);

    session.begin();
    var firstEntity = session.newVertex(entityClass);
    firstEntity.setProperty("type", "type1");

    var secondEntity = session.newVertex(entityClass);
    secondEntity.setProperty("type", "type2");

    var thirdEntity = session.newVertex(entityClass);
    thirdEntity.setProperty("type", "type3");

    firstEntity.addLightWeightEdge(thirdEntity, linkClass);
    secondEntity.addLightWeightEdge(thirdEntity, linkClass);

    session.commit();
  }

  @Test
  public void compositeIndexWithEdgesTestThree() {
    var linkClass = session.createLightweightEdgeClass("Link");

    var entityClass = session.createVertexClass("Entity");
    var edgeOutPropertyName = Vertex.getEdgeLinkFieldName(Direction.OUT, "Link");
    entityClass.createProperty(edgeOutPropertyName, PropertyType.LINKBAG);

    entityClass.createProperty("type", PropertyType.STRING);
    entityClass.createIndex("typeLink", SchemaClass.INDEX_TYPE.UNIQUE, "type",
        edgeOutPropertyName);

    session.begin();
    var firstEntity = session.newVertex(entityClass);
    firstEntity.setProperty("type", "type1");

    var secondEntity = session.newVertex(entityClass);
    secondEntity.setProperty("type", "type1");

    var thirdEntity = session.newVertex(entityClass);
    thirdEntity.setProperty("type", "type3");

    firstEntity.addLightWeightEdge(thirdEntity, linkClass);

    session.commit();
  }

  @Test()
  public void testUniqueOnUpdate() {
    final Schema schema = session.getMetadata().getSchema();
    var userClass = schema.createClass("User");
    userClass.createProperty("MailAddress", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);

    session.begin();
    var john = (EntityImpl) session.newEntity("User");
    john.setProperty("MailAddress", "john@doe.com");
    session.commit();

    session.begin();
    var jane = (EntityImpl) session.newEntity("User");
    jane.setProperty("MailAddress", "jane@doe.com");
    var id = jane;

    session.commit();

    try {
      session.begin();
      EntityImpl toUp = session.load(id.getIdentity());
      toUp.setProperty("MailAddress", "john@doe.com");
      session.commit();
      Assert.fail("Expected record duplicate exception");
    } catch (RecordDuplicatedException ex) {
      // ignore
    }
    session.begin();
    EntityImpl fromDb = session.load(id.getIdentity());
    Assert.assertEquals(fromDb.getProperty("MailAddress"), "jane@doe.com");
    session.commit();
  }

  @Test
  public void testUniqueOnUpdateNegativeVersion() {
    final Schema schema = session.getMetadata().getSchema();
    var userClass = schema.createClass("User");
    userClass.createProperty("MailAddress", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);

    session.begin();
    var jane = (EntityImpl) session.newEntity("User");
    jane.setProperty("MailAddress", "jane@doe.com");

    session.commit();

    final RID rid = jane.getIdentity();

    reOpen("admin", "adminpwd");

    session.begin();
    var joneJane = session.loadEntity(rid);

    joneJane.setProperty("MailAddress", "john@doe.com");
    session.commit();

    reOpen("admin", "adminpwd");

    try {
      session.begin();
      var toUp = (EntityImpl) session.newEntity("User");
      toUp.setProperty("MailAddress", "john@doe.com");

      session.commit();

      Assert.fail("Expected record duplicate exception");
    } catch (RecordDuplicatedException ex) {
      // ignore
    }

    session.begin();
    final var result = session.query("select from User where MailAddress = 'john@doe.com'");
    Assert.assertEquals(result.stream().count(), 1);
    session.commit();
  }

  @Test
  public void uniqueIndexOnLinkBag() {
    final var schema = session.getMetadata().getSchema();
    final var bClass = schema.createClass("bClass_" + UniqueIndexTest.class.getSimpleName());
    final var aClass = schema.createClass("aClass_" + UniqueIndexTest.class.getSimpleName());
    aClass.createProperty("bLinks", PropertyType.LINKBAG, bClass);

    // a unique index on the LINKBAG property
    aClass.createIndex(
        "unique_index_123", SchemaClass.INDEX_TYPE.UNIQUE, "bLinks"
    );

    // 2 entities with different "bLinks" fields.
    final var ids = session.computeInTx(tx -> {
      final var b = tx.newEntity(bClass);

      final var a1 = tx.newEntity(aClass);
      final var a2 = tx.newEntity(aClass);

      final var bag1 = new LinkBag((DatabaseSessionInternal) tx.getDatabaseSession());
      bag1.add(b.getIdentity());
      a1.setProperty("bLinks", bag1);

      return new Triple<>(b.getIdentity(), a1.getIdentity(), a2.getIdentity());
    });

    // removing link from a1, adding it to a2
    session.executeInTx(tx -> {
      final var b = tx.loadEntity(ids.key);
      final var a1 = tx.loadEntity(ids.value.key);
      final var a2 = tx.loadEntity(ids.value.value);

      final var bag2 = new LinkBag((DatabaseSessionInternal) tx.getDatabaseSession());
      bag2.add(b.getIdentity());
      a2.setProperty("bLinks", bag2);

      final var bag1 = a1.<LinkBag>getProperty("bLinks");
      bag1.remove(b.getIdentity());

      // this line is redundant in theory, but it used to cause an issue with index update.
      // don't remove it from the test please.
      a1.setProperty("bLinks", bag1);
    });
  }
}
