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

package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang.ArrayUtils;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class TransactionConsistencyTest extends BaseDBTest {

  protected DatabaseSessionInternal database1;
  protected DatabaseSessionInternal database2;

  public static final String NAME = "name";

  @Parameters(value = "remote")
  public TransactionConsistencyTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void test1RollbackOnConcurrentException() {
    database1 = acquireSession();

    database1.begin();

    // Create docA.
    EntityImpl vDocA_db1 = database1.newInstance();
    vDocA_db1.field(NAME, "docA");

    // Create docB.
    EntityImpl vDocB_db1 = database1.newInstance();
    vDocB_db1.field(NAME, "docB");

    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();
    RID vDocB_Rid = vDocB_db1.getIdentity().copy();

    var vDocA_version = -1;
    var vDocB_version = -1;

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.field(NAME, "docA_v2");

      // Concurrent update docA via database1 -> will throw ConcurrentModificationException at
      // database2.commit().
      database1.activateOnCurrentThread();
      database1.begin();
      try {
        vDocA_db1 = database1.bindToSession(vDocA_db1);

        vDocA_db1.field(NAME, "docA_v3");

        database1.commit();
      } catch (ConcurrentModificationException e) {
        Assert.fail("Should not failed here...");
      }
      vDocA_db1 = database1.bindToSession(vDocA_db1);
      vDocB_db1 = database1.bindToSession(vDocB_db1);

      Assert.assertEquals(vDocA_db1.field(NAME), "docA_v3");
      // Keep the last versions.
      // Following updates should failed and reverted.
      vDocA_version = vDocA_db1.getVersion();
      vDocB_version = vDocB_db1.getVersion();

      // Update docB in db2 transaction context -> should be rollbacked.
      database2.activateOnCurrentThread();
      EntityImpl vDocB_db2 = database2.load(vDocB_Rid);
      vDocB_db2.field(NAME, "docB_UpdatedInTranscationThatWillBeRollbacked");

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

    EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
    Assert.assertEquals(vDocA_db2.field(NAME), "docA_v3");
    Assert.assertEquals(vDocA_db2.getVersion(), vDocA_version);

    // docB should be in the first state : "docB"
    EntityImpl vDocB_db2 = database2.load(vDocB_Rid);
    Assert.assertEquals(vDocB_db2.field(NAME), "docB");
    Assert.assertEquals(vDocB_db2.getVersion(), vDocB_version);

    database2.close();
  }

  @Test
  public void test4RollbackWithPin() {
    database1 = acquireSession();

    // Create docA.
    EntityImpl vDocA_db1 = database1.newInstance();
    vDocA_db1.field(NAME, "docA");
    database1.begin();
    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.field(NAME, "docA_v2");

      database1.activateOnCurrentThread();
      database1.begin();
      try {
        vDocA_db1 = database1.bindToSession(vDocA_db1);
        vDocA_db1.field(NAME, "docA_v3");
        database1.commit();
      } catch (ConcurrentModificationException e) {
        Assert.fail("Should not failed here...");
      }
      vDocA_db1 = database1.bindToSession(vDocA_db1);
      Assert.assertEquals(vDocA_db1.field(NAME), "docA_v3");

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

    // docB should be in the last state : "docA_v3"
    EntityImpl vDocB_db2 = database2.load(vDocA_Rid);
    Assert.assertEquals(vDocB_db2.field(NAME), "docA_v3");

    database1.activateOnCurrentThread();
    database1.close();

    database2.activateOnCurrentThread();
    database2.close();
  }

  @Test
  public void test3RollbackWithCopyCacheStrategy() {
    database1 = acquireSession();

    // Create docA.
    EntityImpl vDocA_db1 = database1.newInstance();
    vDocA_db1.field(NAME, "docA");
    database1.begin();
    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.field(NAME, "docA_v2");

      database1.activateOnCurrentThread();
      database1.begin();
      try {
        vDocA_db1 = database1.bindToSession(vDocA_db1);
        vDocA_db1.field(NAME, "docA_v3");
        database1.commit();
      } catch (ConcurrentModificationException e) {
        Assert.fail("Should not failed here...");
      }

      vDocA_db1 = database1.bindToSession(vDocA_db1);
      Assert.assertEquals(vDocA_db1.field(NAME), "docA_v3");

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

    // docB should be in the last state : "docA_v3"
    EntityImpl vDocB_db2 = database2.load(vDocA_Rid);
    Assert.assertEquals(vDocB_db2.field(NAME), "docA_v3");

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
    vDocA_db1.field(NAME, "docA");
    database1.commit();

    // Keep the ID.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    // Update docA in db2
    database2 = acquireSession();
    database2.begin();
    EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
    vDocA_db2.field(NAME, "docA_v2");
    database2.commit();

    // Later... read docA with db1.
    database1.activateOnCurrentThread();
    database1.begin();
    EntityImpl vDocA_db1_later = database1.load(vDocA_Rid);
    Assert.assertEquals(vDocA_db1_later.field(NAME), "docA_v2");
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

    var kim = ((EntityImpl) session.newEntity("Profile")).field("name", "Kim")
        .field("surname", "Bauer");
    var teri = ((EntityImpl) session.newEntity("Profile")).field("name", "Teri")
        .field("surname", "Bauer");
    var jack = ((EntityImpl) session.newEntity("Profile")).field("name", "Jack")
        .field("surname", "Bauer");

    ((HashSet<EntityImpl>) jack.field("following", new HashSet<EntityImpl>())
        .field("following"))
        .add(kim);
    ((HashSet<EntityImpl>) kim.field("following", new HashSet<EntityImpl>()).field("following"))
        .add(teri);
    ((HashSet<EntityImpl>) teri.field("following", new HashSet<EntityImpl>())
        .field("following"))
        .add(jack);

    session.commit();

    session.close();
    session = acquireSession();

    EntityImpl loadedJack = session.load(jack.getIdentity());

    var jackLastVersion = loadedJack.getVersion();
    session.begin();
    loadedJack = session.bindToSession(loadedJack);
    loadedJack.field("occupation", "agent");

    session.commit();
    Assert.assertTrue(jackLastVersion != session.bindToSession(loadedJack).getVersion());

    loadedJack = session.load(jack.getIdentity());
    Assert.assertTrue(jackLastVersion != session.bindToSession(loadedJack).getVersion());

    session.close();

    session = acquireSession();
    loadedJack = session.load(jack.getIdentity());
    Assert.assertTrue(jackLastVersion != session.bindToSession(loadedJack).getVersion());
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

    var kim = ((EntityImpl) session.newEntity("MyProfile")).field("name", "Kim")
        .field("surname", "Bauer");
    var teri = ((EntityImpl) session.newEntity("MyProfile")).field("name", "Teri")
        .field("surname", "Bauer");
    var jack = ((EntityImpl) session.newEntity("MyProfile")).field("name", "Jack")
        .field("surname", "Bauer");

    var myedge = ((EntityImpl) session.newEntity("MyEdge")).field("in", kim).field("out", jack);

    ((HashSet<EntityImpl>) kim.field("out", new HashSet<RID>()).field("out")).add(myedge);
    ((HashSet<EntityImpl>) jack.field("in", new HashSet<RID>()).field("in")).add(myedge);

    session.commit();

    var result = session.command("select from MyProfile ");

    Assert.assertTrue(result.stream().findAny().isPresent());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void loadRecordTest() {
    session.begin();

    var kim = ((EntityImpl) session.newEntity("Profile")).field("name", "Kim")
        .field("surname", "Bauer");
    var teri = ((EntityImpl) session.newEntity("Profile")).field("name", "Teri")
        .field("surname", "Bauer");
    var jack = ((EntityImpl) session.newEntity("Profile")).field("name", "Jack")
        .field("surname", "Bauer");
    var chloe = ((EntityImpl) session.newEntity("Profile")).field("name", "Chloe")
        .field("surname", "O'Brien");

    ((HashSet<EntityImpl>) jack.field("following", new HashSet<EntityImpl>())
        .field("following"))
        .add(kim);
    ((HashSet<EntityImpl>) kim.field("following", new HashSet<EntityImpl>()).field("following"))
        .add(teri);
    ((HashSet<EntityImpl>) teri.field("following", new HashSet<EntityImpl>())
        .field("following"))
        .add(jack);
    ((HashSet<EntityImpl>) teri.field("following")).add(kim);
    ((HashSet<EntityImpl>) chloe.field("following", new HashSet<EntityImpl>())
        .field("following"))
        .add(jack);
    ((HashSet<EntityImpl>) chloe.field("following")).add(teri);
    ((HashSet<EntityImpl>) chloe.field("following")).add(kim);

    var schema = session.getSchema();
    var profileClusterIds =
        Arrays.asList(ArrayUtils.toObject(schema.getClass("Profile").getClusterIds()));

    session.commit();

    Assert.assertListContainsObject(
        profileClusterIds, jack.getIdentity().getClusterId(), "Cluster id not found");
    Assert.assertListContainsObject(
        profileClusterIds, kim.getIdentity().getClusterId(), "Cluster id not found");
    Assert.assertListContainsObject(
        profileClusterIds, teri.getIdentity().getClusterId(), "Cluster id not found");
    Assert.assertListContainsObject(
        profileClusterIds, chloe.getIdentity().getClusterId(), "Cluster id not found");

    session.load(chloe.getIdentity());
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
      Assert.assertEquals(session.countClusterElements("MyFruit"), 0);

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
                .field("name", "" + i)
                .field("color", "FOO")
                .field("flavor", "BAR" + i);

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
        session.delete(session.bindToSession(entries));
      }
      session.commit();

      System.out.println("[testTransactionPopulateDelete] Deleted executed successfully");

      Assert.assertEquals(session.countClusterElements("MyFruit"), 0);
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

    // remove those foos in a transaction
    // Step 3a
    var result =
        session.query("select * from Foo where address = 'test1'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    // Step 4a
    session.begin();
    session.delete(session.bindToSession(result.get(0)));
    session.commit();

    // Step 3b
    result = session.query("select * from Foo where address = 'test2'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    // Step 4b
    session.begin();
    session.delete(session.bindToSession(result.get(0)));
    session.commit();

    // Step 3c
    result = session.query("select * from Foo where address = 'test3'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    // Step 4c
    session.begin();
    session.delete(session.bindToSession(result.get(0)));
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

    // Commenting out the transaction will result in the test succeeding.
    var foo = session.newVertex("Foo");
    foo.setProperty("prop", "test1");
    session.begin();
    session.commit();

    // Comment out these two lines and the test will succeed. The issue appears to be related to
    // an edge
    // connecting a deleted vertex during a transaction
    var bar = session.newVertex("Bar");
    bar.setProperty("prop", "test1");
    session.begin();
    session.commit();

    session.begin();
    foo = session.bindToSession(foo);
    bar = session.bindToSession(bar);
    var sees = session.newStatefulEdge(foo, bar, "Sees");
    session.commit();

    var foos = session.query("select * from Foo").stream().toList();
    Assert.assertEquals(foos.size(), 1);

    session.begin();
    session.delete(session.bindToSession(foos.getFirst().asEntity()));
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
      person.field("name", Character.toString((char) ('A' + i)));
      person.field("surname", Character.toString((char) ('A' + (i % 3))));
      person.field("myversion", 0);
      person.field("in", new HashSet<EntityImpl>());
      person.field("out", new HashSet<EntityImpl>());

      if (i >= 1) {
        var edge = ((EntityImpl) session.newEntity("TREdge"));
        edge.field("in", person.getIdentity());
        edge.field("out", inserted.get(i - 1));
        (person.<Set<EntityImpl>>getProperty("out")).add(edge);
        (session.bindToSession(inserted.get(i - 1)).<Set<EntityImpl>>getProperty("in")).add(
            edge);

      }
      inserted.add(person);

    }
    session.commit();

    final var result1 = session.command("select from TRPerson");
    Assert.assertEquals(result1.stream().count(), cnt);

    try {
      session.executeInTx(
          () -> {
            List<Entity> inserted2 = new ArrayList<>();

            for (var i = 0; i < cnt; i++) {
              var person = ((EntityImpl) session.newEntity("TRPerson"));
              person.field("name", Character.toString((char) ('a' + i)));
              person.field("surname", Character.toString((char) ('a' + (i % 3))));
              person.field("myversion", 0);
              person.field("in", new HashSet<EntityImpl>());
              person.field("out", new HashSet<EntityImpl>());

              if (i >= 1) {
                var edge = ((EntityImpl) session.newEntity("TREdge"));
                edge.field("in", person.getIdentity());
                edge.field("out", inserted2.get(i - 1));
                (person.<Set<EntityImpl>>getProperty("out")).add(edge);
                ((inserted2.get(i - 1)).<Set<EntityImpl>>getProperty("in")).add(edge);

              }

              inserted2.add(person);

            }

            for (var i = 0; i < cnt; i++) {
              if (i != cnt - 1) {
                var doc = session.bindToSession((EntityImpl) inserted.get(i));
                doc.setProperty("myversion", 2);

              }
            }

            var doc = ((EntityImpl) inserted.get(cnt - 1));
            session.bindToSession(doc).delete();

            throw new IllegalStateException();
          });
      Assert.fail();
    } catch (IllegalStateException e) {
      Assert.assertTrue(true);
    }

    final var result2 = session.command("select from TRPerson");
    Assert.assertNotNull(result2);
    Assert.assertEquals(result2.stream().count(), cnt);
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
    var account = session.newEntity("Account");
    account.setProperty("name", "John Grisham");
    session.begin();
    account = account;
    session.commit();

    session.begin();
    account = session.bindToSession(account);
    var address1 = session.newEntity("Address");
    address1.setProperty("street", "Mulholland drive");

    var address2 = session.newEntity("Address");
    address2.setProperty("street", "Via Veneto");

    List<Entity> addresses = new ArrayList<>();
    addresses.add(address1);
    addresses.add(address2);

    account.setProperty("addresses", addresses);

    account = account;
    session.commit();

    session.begin();
    account = session.bindToSession(account);
    String originalName = account.getProperty("name");
    Assert.assertEquals(account.<List<Identifiable>>getProperty("addresses").size(), 2);
    account
        .<List<Identifiable>>getProperty("addresses")
        .remove(1); // delete one of the objects in the Books collection to see how rollback behaves
    Assert.assertEquals(account.<List<Identifiable>>getProperty("addresses").size(), 1);
    account.setProperty(
        "name", "New Name"); // change an attribute to see if the change is rolled back
    account = account;

    Assert.assertEquals(
        account.<List<Identifiable>>getProperty("addresses").size(),
        1); // before rollback this is fine because one of the books was removed

    session.rollback(); // rollback the transaction

    account = session.bindToSession(account);
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
  }

  public void testTransactionsCache() {
    Assert.assertFalse(session.getTransaction().isActive());
    Schema schema = session.getMetadata().getSchema();
    var classA = schema.createClass("TransA");
    classA.createProperty("name", PropertyType.STRING);
    var doc = ((EntityImpl) session.newEntity(classA));
    doc.field("name", "test1");

    session.begin();

    session.commit();
    RID orid = doc.getIdentity();
    session.begin();
    Assert.assertTrue(session.getTransaction().isActive());
    doc = orid.getRecord(session);
    Assert.assertEquals(doc.field("name"), "test1");
    doc.field("name", "test2");
    doc = orid.getRecord(session);
    Assert.assertEquals(doc.field("name"), "test2");
    // There is NO SAVE!
    session.commit();

    doc = orid.getRecord(session);
    Assert.assertEquals(doc.field("name"), "test2");
  }
}
