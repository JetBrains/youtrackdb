/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.ArrayUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class TransactionConsistencyTest extends BaseDBTest {

  protected DatabaseSessionEmbedded database1;
  protected DatabaseSessionEmbedded database2;

  public static final String NAME = "name";

  @Test
  public void test1RollbackOnConcurrentException() {
    database1 = acquireSession();

    database1.begin();

    // Create docA.
    EntityImpl vDocA_db1 = database1.newInstance();
    vDocA_db1.setProperty(NAME, "docA");

    // Create docB.
    EntityImpl vDocB_db1 = database1.newInstance();
    vDocB_db1.setProperty(NAME, "docB");

    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();
    RID vDocB_Rid = vDocB_db1.getIdentity().copy();

    var vDocA_version = -1L;
    var vDocB_version = -1L;

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.setProperty(NAME, "docA_v2");

      // Concurrent update docA via database1 -> will throw ConcurrentModificationException at
      // database2.commit().
      database1.activateOnCurrentThread();
      database1.begin();
      try {
        var activeTx = database1.getActiveTransaction();
        vDocA_db1 = activeTx.load(vDocA_db1);

        vDocA_db1.setProperty(NAME, "docA_v3");

        database1.commit();
      } catch (ConcurrentModificationException e) {
        Assert.fail("Should not failed here...");
      }
      database1.begin();
      var activeTx = database1.getActiveTransaction();
      vDocA_db1 = activeTx.load(vDocA_db1);
      vDocB_db1 = activeTx.load(vDocB_db1);

      Assert.assertEquals(vDocA_db1.getProperty(NAME), "docA_v3");
      // Keep the last versions.
      // Following updates should failed and reverted.
      vDocA_version = vDocA_db1.getVersion();
      vDocB_version = vDocB_db1.getVersion();
      database1.commit();

      // Update docB in db2 transaction context -> should be rollbacked.
      database2.activateOnCurrentThread();
      EntityImpl vDocB_db2 = database2.load(vDocB_Rid);
      vDocB_db2.setProperty(NAME, "docB_UpdatedInTranscationThatWillBeRollbacked");

      // Will throw ConcurrentModificationException
      database2.commit();
      Assert.fail("Should throw ConcurrentModificationException");
    } catch (ConcurrentModificationException e) {
      database2.rollback();
    }

    // Force reload all (to be sure it is not a cache problem)
    database1.activateOnCurrentThread();
    database1.close();

    database2.activateOnCurrentThread();
    database2 = acquireSession();

    database2.begin();
    EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
    Assert.assertEquals(vDocA_db2.getProperty(NAME), "docA_v3");
    Assert.assertEquals(vDocA_db2.getVersion(), vDocA_version);

    // docB should be in the first state : "docB"
    EntityImpl vDocB_db2 = database2.load(vDocB_Rid);
    Assert.assertEquals(vDocB_db2.getProperty(NAME), "docB");
    Assert.assertEquals(vDocB_db2.getVersion(), vDocB_version);
    database2.commit();

    database2.close();
  }

  @Test
  public void test4RollbackWithPin() {
    database1 = acquireSession();

    database1.begin();
    // Create docA.
    EntityImpl vDocA_db1 = database1.newInstance();
    vDocA_db1.setProperty(NAME, "docA");
    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.setProperty(NAME, "docA_v2");

      database1.activateOnCurrentThread();
      database1.begin();
      try {
        var activeTx = database1.getActiveTransaction();
        vDocA_db1 = activeTx.load(vDocA_db1);
        vDocA_db1.setProperty(NAME, "docA_v3");
        database1.commit();
      } catch (ConcurrentModificationException e) {
        Assert.fail("Should not failed here...");
      }
      database1.begin();
      var activeTx = database1.getActiveTransaction();
      vDocA_db1 = activeTx.load(vDocA_db1);
      Assert.assertEquals(vDocA_db1.getProperty(NAME), "docA_v3");
      database1.commit();

      // Will throw ConcurrentModificationException
      database2.activateOnCurrentThread();
      database2.commit();
      Assert.fail("Should throw ConcurrentModificationException");
    } catch (ConcurrentModificationException e) {
      database2.rollback();
    }

    // Force reload all (to be sure it is not a cache problem)
    database1.activateOnCurrentThread();
    database1.close();

    database2.activateOnCurrentThread();
    database2.close();
    database2 = acquireSession();

    database2.begin();
    // docB should be in the last state : "docA_v3"
    EntityImpl vDocB_db2 = database2.load(vDocA_Rid);
    Assert.assertEquals(vDocB_db2.getProperty(NAME), "docA_v3");
    database2.commit();

    database1.activateOnCurrentThread();
    database1.close();

    database2.activateOnCurrentThread();
    database2.close();
  }

  @Test
  public void test3RollbackWithCopyCacheStrategy() {
    database1 = acquireSession();

    database1.begin();
    // Create docA.
    EntityImpl vDocA_db1 = database1.newInstance();
    vDocA_db1.setProperty(NAME, "docA");
    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.setProperty(NAME, "docA_v2");

      database1.activateOnCurrentThread();
      database1.begin();
      try {
        var activeTx = database1.getActiveTransaction();
        vDocA_db1 = activeTx.load(vDocA_db1);
        vDocA_db1.setProperty(NAME, "docA_v3");
        database1.commit();
      } catch (ConcurrentModificationException e) {
        Assert.fail("Should not failed here...");
      }

      database1.begin();
      var activeTx = database1.getActiveTransaction();
      vDocA_db1 = activeTx.load(vDocA_db1);
      Assert.assertEquals(vDocA_db1.getProperty(NAME), "docA_v3");
      database1.commit();

      // Will throw ConcurrentModificationException
      database2.activateOnCurrentThread();
      database2.commit();
      Assert.fail("Should throw ConcurrentModificationException");
    } catch (ConcurrentModificationException e) {
      database2.rollback();
    }

    // Force reload all (to be sure it is not a cache problem)
    database1.activateOnCurrentThread();
    database1.close();

    database2.activateOnCurrentThread();
    database2.close();
    database2 = acquireSession();

    database2.begin();
    // docB should be in the last state : "docA_v3"
    EntityImpl vDocB_db2 = database2.load(vDocA_Rid);
    Assert.assertEquals(vDocB_db2.getProperty(NAME), "docA_v3");
    database2.commit();

    database1.activateOnCurrentThread();
    database1.close();
    database2.activateOnCurrentThread();
    database2.close();
  }

  @Test
  public void test5CacheUpdatedMultipleDbs() {
    database1 = acquireSession();

    // Create docA in db1
    database1.begin();
    EntityImpl vDocA_db1 = database1.newInstance();
    vDocA_db1.setProperty(NAME, "docA");
    database1.commit();

    // Keep the ID.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    // Update docA in db2
    database2 = acquireSession();
    database2.begin();
    EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
    vDocA_db2.setProperty(NAME, "docA_v2");
    database2.commit();

    // Later... read docA with db1.
    database1.activateOnCurrentThread();
    database1.begin();
    EntityImpl vDocA_db1_later = database1.load(vDocA_Rid);
    Assert.assertEquals(vDocA_db1_later.getProperty(NAME), "docA_v2");
    database1.commit();

    database1.close();

    database2.activateOnCurrentThread();
    database2.close();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void checkVersionsInConnectedDocuments() {
    session = acquireSession();
    session.begin();

    var kim = ((EntityImpl) session.newEntity("Profile")).setPropertyInChain("name", "Kim")
        .setPropertyInChain("surname", "Bauer");
    var teri = ((EntityImpl) session.newEntity("Profile")).setPropertyInChain("name", "Teri")
        .setPropertyInChain("surname", "Bauer");
    var jack = ((EntityImpl) session.newEntity("Profile")).setPropertyInChain("name", "Jack")
        .setPropertyInChain("surname", "Bauer");

    jack.getOrCreateLinkSet("following").add(kim);
    kim.getOrCreateLinkSet("following").add(teri);
    teri.getOrCreateLinkSet("following").add(jack);

    session.commit();

    session.close();
    session = acquireSession();

    session.begin();
    EntityImpl loadedJack = session.load(jack.getIdentity());
    var jackLastVersion = loadedJack.getVersion();
    session.commit();

    session.begin();
    var activeTx3 = session.getActiveTransaction();
    loadedJack = activeTx3.load(loadedJack);
    loadedJack.setProperty("occupation", "agent");
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    Assert.assertTrue(jackLastVersion != activeTx2.<EntityImpl>load(loadedJack).getVersion());

    loadedJack = session.load(jack.getIdentity());
    var activeTx1 = session.getActiveTransaction();
    Assert.assertTrue(jackLastVersion != activeTx1.<EntityImpl>load(loadedJack).getVersion());
    session.commit();

    session.close();

    session = acquireSession();
    session.begin();
    loadedJack = session.load(jack.getIdentity());
    var activeTx = session.getActiveTransaction();
    Assert.assertTrue(jackLastVersion != activeTx.<EntityImpl>load(loadedJack).getVersion());
    session.commit();
    session.close();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void createLinkInTx() {
    session = createSessionInstance();

    var profile = session.getMetadata().getSchema().createClass("MyProfile", 1);
    var edge = session.getMetadata().getSchema().createClass("MyEdge", 1);
    profile
        .createProperty("name", PropertyType.STRING)
        .setMin("3")
        .setMax("30")
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    profile.createProperty("surname", PropertyType.STRING).setMin("3")
        .setMax("30");
    profile.createProperty("in", PropertyType.LINKSET, edge);
    profile.createProperty("out", PropertyType.LINKSET, edge);
    edge.createProperty("in", PropertyType.LINK, profile);
    edge.createProperty("out", PropertyType.LINK, profile);

    session.begin();

    var kim = ((EntityImpl) session.newEntity("MyProfile"))
        .setPropertyInChain("name", "Kim")
        .setPropertyInChain("surname", "Bauer");
    ((EntityImpl) session.newEntity("MyProfile"))
        .setPropertyInChain("name", "Teri")
        .setPropertyInChain("surname", "Bauer");
    var jack = ((EntityImpl) session.newEntity("MyProfile"))
        .setPropertyInChain("name", "Jack")
        .setPropertyInChain("surname", "Bauer");

    var myedge = ((EntityImpl) session.newEntity("MyEdge"))
        .setPropertyInChain("in", kim)
        .setPropertyInChain("out", jack);

    kim.getOrCreateLinkSet("out").add(myedge);
    jack.getOrCreateLinkSet("in").add(myedge);

    session.commit();

    session.begin();
    var result = session.execute("select from MyProfile ");
    Assert.assertTrue(result.stream().findAny().isPresent());
    session.commit();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void loadRecordTest() {
    session.begin();

    var kim = ((EntityImpl) session.newEntity("Profile"))
        .setPropertyInChain("name", "Kim")
        .setPropertyInChain("surname", "Bauer");
    var teri = ((EntityImpl) session.newEntity("Profile"))
        .setPropertyInChain("name", "Teri")
        .setPropertyInChain("surname", "Bauer");
    var jack = ((EntityImpl) session.newEntity("Profile"))
        .setPropertyInChain("name", "Jack")
        .setPropertyInChain("surname", "Bauer");
    var chloe = ((EntityImpl) session.newEntity("Profile"))
        .setPropertyInChain("name", "Chloe")
        .setPropertyInChain("surname", "O'Brien");

    jack.getOrCreateLinkSet("following").add(kim);
    kim.getOrCreateLinkSet("following").add(teri);
    teri.getOrCreateLinkSet("following").addAll(List.of(jack, kim));
    chloe.getOrCreateLinkSet("following").addAll(List.of(jack, teri, kim));

    var schema = session.getSchema();
    var profileCollectionIds =
        Arrays.asList(ArrayUtils.toObject(schema.getClass("Profile").getCollectionIds()));

    session.commit();

    Assert.assertListContainsObject(
        profileCollectionIds, jack.getIdentity().getCollectionId(), "Collection id not found");
    Assert.assertListContainsObject(
        profileCollectionIds, kim.getIdentity().getCollectionId(), "Collection id not found");
    Assert.assertListContainsObject(
        profileCollectionIds, teri.getIdentity().getCollectionId(), "Collection id not found");
    Assert.assertListContainsObject(
        profileCollectionIds, chloe.getIdentity().getCollectionId(), "Collection id not found");

    session.begin();
    session.load(chloe.getIdentity());
    session.commit();
  }

  @Test
  public void testTransactionPopulateDelete() {
    if (!session.getMetadata().getSchema().existsClass("MyFruit")) {
      var fruitClass = session.getMetadata().getSchema().createClass("MyFruit");
      fruitClass.createProperty("name", PropertyType.STRING);
      fruitClass.createProperty("color", PropertyType.STRING);
      fruitClass.createProperty("flavor", PropertyType.STRING);

      session
          .getMetadata()
          .getSchema()
          .getClass("MyFruit")
          .getProperty("name")
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      session
          .getMetadata()
          .getSchema()
          .getClass("MyFruit")
          .getProperty("color")
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      session
          .getMetadata()
          .getSchema()
          .getClass("MyFruit")
          .getProperty("flavor")
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    }

    var chunkSize = 10;
    for (var initialValue = 0; initialValue < 10; initialValue++) {
      session.begin();
      Assert.assertEquals(session.countCollectionElements("MyFruit"), 0);
      session.rollback();

      System.out.println(
          "[testTransactionPopulateDelete] Populating chunk "
              + initialValue
              + "... (chunk="
              + chunkSize
              + ")");

      // do insert
      List<EntityImpl> v = new ArrayList<>();
      session.begin();
      for (var i = initialValue * chunkSize; i < (initialValue * chunkSize) + chunkSize; i++) {
        var d =
            ((EntityImpl) session.newEntity("MyFruit"))
                .setPropertyInChain("name", "" + i)
                .setPropertyInChain("color", "FOO")
                .setPropertyInChain("flavor", "BAR" + i);

        v.add(d);
      }

      System.out.println(
          "[testTransactionPopulateDelete] Committing chunk " + initialValue + "...");

      session.commit();

      System.out.println(
          "[testTransactionPopulateDelete] Committed chunk "
              + initialValue
              + ", starting to delete all the new entries ("
              + v.size()
              + ")...");

      // do delete
      session.begin();
      for (var entries : v) {
        var activeTx = session.getActiveTransaction();
        session.delete(activeTx.<EntityImpl>load(entries));
      }
      session.commit();

      System.out.println("[testTransactionPopulateDelete] Deleted executed successfully");

      session.begin();
      Assert.assertEquals(session.countCollectionElements("MyFruit"), 0);
      session.rollback();
    }

    System.out.println("[testTransactionPopulateDelete] End of the test");
  }

  @Test
  public void testConsistencyOnDelete() {
    if (session.getMetadata().getSchema().getClass("Foo") == null) {
      session.createVertexClass("Foo");
    }

    session.begin();
    // Step 1
    // Create several foo's
    var v = session.newVertex("Foo");
    v.setProperty("address", "test1");

    v = session.newVertex("Foo");
    v.setProperty("address", "test2");

    v = session.newVertex("Foo");
    v.setProperty("address", "test3");
    session.commit();

    session.begin();
    // remove those foos in a transaction
    // Step 3a
    var result =
        session.query("select * from Foo where address = 'test1'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    session.commit();
    // Step 4a
    session.begin();
    var activeTx2 = session.getActiveTransaction();
    session.delete(activeTx2.<Entity>load(result.get(0)));
    session.commit();

    session.begin();
    // Step 3b
    result = session.query("select * from Foo where address = 'test2'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    session.commit();
    // Step 4b
    session.begin();
    var activeTx1 = session.getActiveTransaction();
    session.delete(activeTx1.<Entity>load(result.get(0)));
    session.commit();

    session.begin();
    // Step 3c
    result = session.query("select * from Foo where address = 'test3'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    session.commit();
    // Step 4c
    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(result.get(0)));
    session.commit();
  }

  @Test
  public void deletesWithinTransactionArentWorking() {
    if (session.getClass("Foo") == null) {
      session.createVertexClass("Foo");
    }
    if (session.getClass("Bar") == null) {
      session.createVertexClass("Bar");
    }
    if (session.getClass("Sees") == null) {
      session.createEdgeClass("Sees");
    }

    session.begin();
    // Commenting out the transaction will result in the test succeeding.
    var foo = session.newVertex("Foo");
    foo.setProperty("prop", "test1");
    session.commit();

    session.begin();
    // Comment out these two lines and the test will succeed. The issue appears to be related to
    // an edge
    // connecting a deleted vertex during a transaction
    var bar = session.newVertex("Bar");
    bar.setProperty("prop", "test1");
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    foo = activeTx2.load(foo);
    var activeTx1 = session.getActiveTransaction();
    bar = activeTx1.load(bar);
    session.newStatefulEdge(foo, bar, "Sees");
    session.commit();

    session.begin();
    var foos = session.query("select * from Foo").stream().toList();
    Assert.assertEquals(foos.size(), 1);
    session.commit();

    session.begin();
    Entity identifiable = foos.getFirst().asEntityOrNull();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(identifiable));
    session.commit();
  }

  public void transactionRollbackConstistencyTest() {
    var vertexClass = session.getMetadata().getSchema().createClass("TRVertex");
    var edgeClass = session.getMetadata().getSchema().createClass("TREdge");
    vertexClass.createProperty("in", PropertyType.LINKSET, edgeClass);
    vertexClass.createProperty("out", PropertyType.LINKSET, edgeClass);
    edgeClass.createProperty("in", PropertyType.LINK, vertexClass);
    edgeClass.createProperty("out", PropertyType.LINK, vertexClass);

    var personClass = session.getMetadata().getSchema()
        .createClass("TRPerson", vertexClass);
    personClass.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
    personClass.createProperty("surname", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    personClass.createProperty("version", PropertyType.INTEGER);

    session.close();

    final var cnt = 4;

    session = createSessionInstance();
    session.begin();
    List<Entity> inserted = new ArrayList<>();

    for (var i = 0; i < cnt; i++) {
      var person = ((EntityImpl) session.newEntity("TRPerson"));
      person.setPropertyInChain("name", Character.toString((char) ('A' + i)));
      person.setPropertyInChain("surname", Character.toString((char) ('A' + (i % 3))));
      person.setProperty("myversion", 0);
      person.getOrCreateLinkSet("in");
      person.getOrCreateLinkSet("out");

      if (i >= 1) {
        var edge = (EntityImpl) session.newEntity("TREdge");
        edge.setProperty("in", person.getIdentity());
        edge.setProperty("out", inserted.get(i - 1));
        person.<Set<EntityImpl>>getProperty("out").add(edge);
        var activeTx = session.getActiveTransaction();
        activeTx.<Entity>load(inserted.get(i - 1)).<Set<EntityImpl>>getProperty("in").add(
            edge);

      }
      inserted.add(person);

    }
    session.commit();

    session.begin();
    final var result1 = session.execute("select from TRPerson");
    Assert.assertEquals(result1.stream().count(), cnt);
    session.commit();

    try {
      session.executeInTx(
          transaction -> {
            List<Entity> inserted2 = new ArrayList<>();

            for (var i = 0; i < cnt; i++) {
              var person = ((EntityImpl) session.newEntity("TRPerson"));
              person.setProperty("name", Character.toString((char) ('a' + i)));
              person.setProperty("surname", Character.toString((char) ('a' + (i % 3))));
              person.setProperty("myversion", 0);
              person.getOrCreateLinkSet("in");
              person.getOrCreateLinkSet("out");

              if (i >= 1) {
                var edge = (EntityImpl) session.newEntity("TREdge");
                edge.setProperty("in", person.getIdentity());
                edge.setProperty("out", inserted2.get(i - 1));
                person.<Set<EntityImpl>>getProperty("out").add(edge);
                inserted2.get(i - 1).<Set<EntityImpl>>getProperty("in").add(edge);

              }

              inserted2.add(person);

            }

            for (var i = 0; i < cnt; i++) {
              if (i != cnt - 1) {
                var activeTx = session.getActiveTransaction();
                var doc = activeTx.<EntityImpl>load(inserted.get(i));
                doc.setProperty("myversion", 2);

              }
            }

            var doc = ((EntityImpl) inserted.get(cnt - 1));
            var activeTx = session.getActiveTransaction();
            activeTx.<EntityImpl>load(doc).delete();

            throw new IllegalStateException();
          });
      Assert.fail();
    } catch (IllegalStateException e) {
      Assert.assertTrue(true);
    }

    session.begin();
    final var result2 = session.execute("select from TRPerson");
    Assert.assertNotNull(result2);
    Assert.assertEquals(result2.stream().count(), cnt);
    session.commit();
  }

  @Test
  public void testQueryIsolation() {
    session.begin();
    var v = session.newVertex();

    v.setProperty("purpose", "testQueryIsolation");

    var result =
        session
            .query("select from V where purpose = 'testQueryIsolation'")
            .entityStream()
            .toList();
    Assert.assertEquals(result.size(), 1);

    session.commit();

    result =
        session
            .query("select from V where purpose = 'testQueryIsolation'")
            .entityStream()
            .toList();
    Assert.assertEquals(result.size(), 1);
  }

  /**
   * When calling .remove(o) on a collection, the row corresponding to o is deleted and not restored
   * when the transaction is rolled back.
   *
   * <p>Commented code after data model change to work around this problem.
   */
  @SuppressWarnings("unused")
  @Test
  public void testRollbackWithRemove() {
    session.begin();
    var account = session.newEntity("Account");
    account.setProperty("name", "John Grisham");
    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    account = activeTx2.load(account);
    var address1 = session.newEntity("Address");
    address1.setProperty("street", "Mulholland drive");

    var address2 = session.newEntity("Address");
    address2.setProperty("street", "Via Veneto");

    final var addresses = session.newLinkList();
    addresses.add(address1);
    addresses.add(address2);

    account.setProperty("addresses", addresses);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    account = activeTx1.load(account);
    String originalName = account.getProperty("name");
    Assert.assertEquals(account.<List<Identifiable>>getProperty("addresses").size(), 2);
    account
        .<List<Identifiable>>getProperty("addresses")
        .remove(1); // delete one of the objects in the Books collection to see how rollback behaves
    Assert.assertEquals(account.<List<Identifiable>>getProperty("addresses").size(), 1);
    account.setProperty(
        "name", "New Name"); // change an attribute to see if the change is rolled back

    Assert.assertEquals(
        account.<List<Identifiable>>getProperty("addresses").size(),
        1); // before rollback this is fine because one of the books was removed

    session.rollback(); // rollback the transaction

    session.begin();
    var activeTx = session.getActiveTransaction();
    account = activeTx.load(account);
    Assert.assertEquals(
        account.<List<Identifiable>>getProperty("addresses").size(),
        2); // this is fine, author still linked to 2 books
    Assert.assertEquals(account.getProperty("name"), originalName); // name is restored

    var bookCount = 0;
    var entityIterator = session.browseClass("Address");
    while (entityIterator.hasNext()) {
      var b = entityIterator.next();
      var street = b.getProperty("street");
      if ("Mulholland drive".equals(street) || "Via Veneto".equals(street)) {
        bookCount++;
      }
    }

    Assert.assertEquals(bookCount, 2); // this fails, only 1 entry in the datastore :(
    session.commit();
  }

  public void testTransactionsCache() {
    Assert.assertFalse(session.getTransactionInternal().isActive());
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TransA");
    classA.createProperty("name", PropertyType.STRING);

    session.begin();
    var doc = ((EntityImpl) session.newEntity(classA));
    doc.setProperty("name", "test1");
    session.commit();

    RID orid = doc.getIdentity();
    session.begin();
    Assert.assertTrue(session.getTransactionInternal().isActive());
    var transaction2 = session.getActiveTransaction();
    doc = transaction2.load(orid);
    Assert.assertEquals(doc.getProperty("name"), "test1");
    doc.setProperty("name", "test2");
    var transaction1 = session.getActiveTransaction();
    doc = transaction1.load(orid);
    Assert.assertEquals(doc.getProperty("name"), "test2");
    // There is NO SAVE!
    session.commit();

    session.begin();
    var transaction = session.getActiveTransaction();
    doc = transaction.load(orid);
    Assert.assertEquals(doc.getProperty("name"), "test2");
    session.commit();
  }
}
