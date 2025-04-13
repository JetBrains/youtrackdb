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
import com.jetbrains.youtrack.db.api.query.ResultSet;
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
    database1.save(vDocA_db1);

    // Create docB.
    EntityImpl vDocB_db1 = database1.newInstance();
    vDocB_db1.field(NAME, "docB");

    database1.save(vDocB_db1);
    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();
    RID vDocB_Rid = vDocB_db1.getIdentity().copy();

    int vDocA_version = -1;
    int vDocB_version = -1;

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.field(NAME, "docA_v2");
      database2.save(vDocA_db2);

      // Concurrent update docA via database1 -> will throw ConcurrentModificationException at
      // database2.commit().
      database1.activateOnCurrentThread();
      database1.begin();
      try {
        vDocA_db1 = database1.bindToSession(vDocA_db1);

        vDocA_db1.field(NAME, "docA_v3");
        database1.save(vDocA_db1);

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
      database2.save(vDocB_db2);

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
    database1.save(vDocA_db1);
    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.field(NAME, "docA_v2");
      database2.save(vDocA_db2);

      database1.activateOnCurrentThread();
      database1.begin();
      try {
        vDocA_db1 = database1.bindToSession(vDocA_db1);
        vDocA_db1.field(NAME, "docA_v3");
        database1.save(vDocA_db1);
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
    database1.save(vDocA_db1);
    database1.commit();

    // Keep the IDs.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    database2 = acquireSession();
    database2.begin();
    try {
      // Get docA and update in db2 transaction context
      EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
      vDocA_db2.field(NAME, "docA_v2");
      database2.save(vDocA_db2);

      database1.activateOnCurrentThread();
      database1.begin();
      try {
        vDocA_db1 = database1.bindToSession(vDocA_db1);
        vDocA_db1.field(NAME, "docA_v3");
        database1.save(vDocA_db1);
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
    database1.save(vDocA_db1);
    database1.commit();

    // Keep the ID.
    RID vDocA_Rid = vDocA_db1.getIdentity().copy();

    // Update docA in db2
    database2 = acquireSession();
    database2.begin();
    EntityImpl vDocA_db2 = database2.load(vDocA_Rid);
    vDocA_db2.field(NAME, "docA_v2");
    database2.save(vDocA_db2);
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
    database = acquireSession();
    database.begin();

    EntityImpl kim = new EntityImpl("Profile").field("name", "Kim").field("surname", "Bauer");
    EntityImpl teri = new EntityImpl("Profile").field("name", "Teri").field("surname", "Bauer");
    EntityImpl jack = new EntityImpl("Profile").field("name", "Jack").field("surname", "Bauer");

    ((HashSet<EntityImpl>) jack.field("following", new HashSet<EntityImpl>())
        .field("following"))
        .add(kim);
    ((HashSet<EntityImpl>) kim.field("following", new HashSet<EntityImpl>()).field("following"))
        .add(teri);
    ((HashSet<EntityImpl>) teri.field("following", new HashSet<EntityImpl>())
        .field("following"))
        .add(jack);

    jack.save();

    database.commit();

    database.close();
    database = acquireSession();

    EntityImpl loadedJack = database.load(jack.getIdentity());

    int jackLastVersion = loadedJack.getVersion();
    database.begin();
    loadedJack = database.bindToSession(loadedJack);
    loadedJack.field("occupation", "agent");
    loadedJack.save();
    database.commit();
    Assert.assertTrue(jackLastVersion != database.bindToSession(loadedJack).getVersion());

    loadedJack = database.load(jack.getIdentity());
    Assert.assertTrue(jackLastVersion != database.bindToSession(loadedJack).getVersion());

    database.close();

    database = acquireSession();
    loadedJack = database.load(jack.getIdentity());
    Assert.assertTrue(jackLastVersion != database.bindToSession(loadedJack).getVersion());
    database.close();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void createLinkInTx() {
    database = createSessionInstance();

    SchemaClass profile = database.getMetadata().getSchema().createClass("MyProfile", 1);
    SchemaClass edge = database.getMetadata().getSchema().createClass("MyEdge", 1);
    profile
        .createProperty(database, "name", PropertyType.STRING)
        .setMin(database, "3")
        .setMax(database, "30")
        .createIndex(database, SchemaClass.INDEX_TYPE.NOTUNIQUE);
    profile.createProperty(database, "surname", PropertyType.STRING).setMin(database, "3")
        .setMax(database, "30");
    profile.createProperty(database, "in", PropertyType.LINKSET, edge);
    profile.createProperty(database, "out", PropertyType.LINKSET, edge);
    edge.createProperty(database, "in", PropertyType.LINK, profile);
    edge.createProperty(database, "out", PropertyType.LINK, profile);

    database.begin();

    EntityImpl kim = new EntityImpl("MyProfile").field("name", "Kim").field("surname", "Bauer");
    EntityImpl teri = new EntityImpl("MyProfile").field("name", "Teri")
        .field("surname", "Bauer");
    EntityImpl jack = new EntityImpl("MyProfile").field("name", "Jack")
        .field("surname", "Bauer");

    EntityImpl myedge = new EntityImpl("MyEdge").field("in", kim).field("out", jack);
    myedge.save();
    ((HashSet<EntityImpl>) kim.field("out", new HashSet<RID>()).field("out")).add(myedge);
    ((HashSet<EntityImpl>) jack.field("in", new HashSet<RID>()).field("in")).add(myedge);

    jack.save();
    kim.save();
    teri.save();
    database.commit();

    ResultSet result = database.command("select from MyProfile ");

    Assert.assertTrue(result.stream().findAny().isPresent());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void loadRecordTest() {
    database.begin();

    EntityImpl kim = new EntityImpl("Profile").field("name", "Kim").field("surname", "Bauer");
    EntityImpl teri = new EntityImpl("Profile").field("name", "Teri").field("surname", "Bauer");
    EntityImpl jack = new EntityImpl("Profile").field("name", "Jack").field("surname", "Bauer");
    EntityImpl chloe = new EntityImpl("Profile").field("name", "Chloe")
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

    var schema = database.getSchema();
    var profileClusterIds =
        Arrays.asList(ArrayUtils.toObject(schema.getClass("Profile").getClusterIds()));

    jack.save();
    kim.save();
    teri.save();
    chloe.save();

    database.commit();

    Assert.assertListContainsObject(
        profileClusterIds, jack.getIdentity().getClusterId(), "Cluster id not found");
    Assert.assertListContainsObject(
        profileClusterIds, kim.getIdentity().getClusterId(), "Cluster id not found");
    Assert.assertListContainsObject(
        profileClusterIds, teri.getIdentity().getClusterId(), "Cluster id not found");
    Assert.assertListContainsObject(
        profileClusterIds, chloe.getIdentity().getClusterId(), "Cluster id not found");

    database.load(chloe.getIdentity());
  }

  @Test
  public void testTransactionPopulateDelete() {
    if (!database.getMetadata().getSchema().existsClass("MyFruit")) {
      SchemaClass fruitClass = database.getMetadata().getSchema().createClass("MyFruit");
      fruitClass.createProperty(database, "name", PropertyType.STRING);
      fruitClass.createProperty(database, "color", PropertyType.STRING);
      fruitClass.createProperty(database, "flavor", PropertyType.STRING);

      database
          .getMetadata()
          .getSchema()
          .getClass("MyFruit")
          .getProperty(database, "name")
          .createIndex(database, SchemaClass.INDEX_TYPE.NOTUNIQUE);
      database
          .getMetadata()
          .getSchema()
          .getClass("MyFruit")
          .getProperty(database, "color")
          .createIndex(database, SchemaClass.INDEX_TYPE.NOTUNIQUE);
      database
          .getMetadata()
          .getSchema()
          .getClass("MyFruit")
          .getProperty(database, "flavor")
          .createIndex(database, SchemaClass.INDEX_TYPE.NOTUNIQUE);
    }

    int chunkSize = 10;
    for (int initialValue = 0; initialValue < 10; initialValue++) {
      Assert.assertEquals(database.countClusterElements("MyFruit"), 0);

      System.out.println(
          "[testTransactionPopulateDelete] Populating chunk "
              + initialValue
              + "... (chunk="
              + chunkSize
              + ")");

      // do insert
      List<EntityImpl> v = new ArrayList<>();
      database.begin();
      for (int i = initialValue * chunkSize; i < (initialValue * chunkSize) + chunkSize; i++) {
        EntityImpl d =
            new EntityImpl("MyFruit")
                .field("name", "" + i)
                .field("color", "FOO")
                .field("flavor", "BAR" + i);
        d.save();
        v.add(d);
      }

      System.out.println(
          "[testTransactionPopulateDelete] Committing chunk " + initialValue + "...");

      database.commit();

      System.out.println(
          "[testTransactionPopulateDelete] Committed chunk "
              + initialValue
              + ", starting to delete all the new entries ("
              + v.size()
              + ")...");

      // do delete
      database.begin();
      for (EntityImpl entries : v) {
        database.delete(database.bindToSession(entries));
      }
      database.commit();

      System.out.println("[testTransactionPopulateDelete] Deleted executed successfully");

      Assert.assertEquals(database.countClusterElements("MyFruit"), 0);
    }

    System.out.println("[testTransactionPopulateDelete] End of the test");
  }

  @Test
  public void testConsistencyOnDelete() {
    if (database.getMetadata().getSchema().getClass("Foo") == null) {
      database.createVertexClass("Foo");
    }

    database.begin();
    // Step 1
    // Create several foo's
    var v = database.newVertex("Foo");
    v.setProperty("address", "test1");
    v.save();

    v = database.newVertex("Foo");
    v.setProperty("address", "test2");
    v.save();

    v = database.newVertex("Foo");
    v.setProperty("address", "test3");
    v.save();
    database.commit();

    // remove those foos in a transaction
    // Step 3a
    var result =
        database.query("select * from Foo where address = 'test1'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    // Step 4a
    database.begin();
    database.delete(database.bindToSession(result.get(0)));
    database.commit();

    // Step 3b
    result = database.query("select * from Foo where address = 'test2'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    // Step 4b
    database.begin();
    database.delete(database.bindToSession(result.get(0)));
    database.commit();

    // Step 3c
    result = database.query("select * from Foo where address = 'test3'").entityStream().toList();
    Assert.assertEquals(result.size(), 1);
    // Step 4c
    database.begin();
    database.delete(database.bindToSession(result.get(0)));
    database.commit();
  }

  @Test
  public void deletesWithinTransactionArentWorking() {
    if (database.getClass("Foo") == null) {
      database.createVertexClass("Foo");
    }
    if (database.getClass("Bar") == null) {
      database.createVertexClass("Bar");
    }
    if (database.getClass("Sees") == null) {
      database.createEdgeClass("Sees");
    }

    // Commenting out the transaction will result in the test succeeding.
    var foo = database.newVertex("Foo");
    foo.setProperty("prop", "test1");
    database.begin();
    foo.save();
    database.commit();

    // Comment out these two lines and the test will succeed. The issue appears to be related to
    // an edge
    // connecting a deleted vertex during a transaction
    var bar = database.newVertex("Bar");
    bar.setProperty("prop", "test1");
    database.begin();
    bar.save();
    database.commit();

    database.begin();
    foo = database.bindToSession(foo);
    bar = database.bindToSession(bar);
    var sees = database.newRegularEdge(foo, bar, "Sees");
    sees.save();
    database.commit();

    var foos = database.query("select * from Foo").stream().toList();
    Assert.assertEquals(foos.size(), 1);

    database.begin();
    database.delete(database.bindToSession(foos.get(0).toEntity()));
    database.commit();
  }

  public void transactionRollbackConstistencyTest() {
    SchemaClass vertexClass = database.getMetadata().getSchema().createClass("TRVertex");
    SchemaClass edgeClass = database.getMetadata().getSchema().createClass("TREdge");
    vertexClass.createProperty(database, "in", PropertyType.LINKSET, edgeClass);
    vertexClass.createProperty(database, "out", PropertyType.LINKSET, edgeClass);
    edgeClass.createProperty(database, "in", PropertyType.LINK, vertexClass);
    edgeClass.createProperty(database, "out", PropertyType.LINK, vertexClass);

    SchemaClass personClass = database.getMetadata().getSchema()
        .createClass("TRPerson", vertexClass);
    personClass.createProperty(database, "name", PropertyType.STRING)
        .createIndex(database, SchemaClass.INDEX_TYPE.UNIQUE);
    personClass.createProperty(database, "surname", PropertyType.STRING)
        .createIndex(database, SchemaClass.INDEX_TYPE.NOTUNIQUE);
    personClass.createProperty(database, "version", PropertyType.INTEGER);

    database.close();

    final int cnt = 4;

    database = createSessionInstance();
    database.begin();
    List<Entity> inserted = new ArrayList<>();

    for (int i = 0; i < cnt; i++) {
      EntityImpl person = new EntityImpl("TRPerson");
      person.field("name", Character.toString((char) ('A' + i)));
      person.field("surname", Character.toString((char) ('A' + (i % 3))));
      person.field("myversion", 0);
      person.field("in", new HashSet<EntityImpl>());
      person.field("out", new HashSet<EntityImpl>());

      if (i >= 1) {
        EntityImpl edge = new EntityImpl("TREdge");
        edge.field("in", person.getIdentity());
        edge.field("out", inserted.get(i - 1));
        (person.<Set<EntityImpl>>getProperty("out")).add(edge);
        (database.bindToSession(inserted.get(i - 1)).<Set<EntityImpl>>getProperty("in")).add(
            edge);
        edge.save();
      }
      inserted.add(person);
      person.save();
    }
    database.commit();

    final ResultSet result1 = database.command("select from TRPerson");
    Assert.assertEquals(result1.stream().count(), cnt);

    try {
      database.executeInTx(
          () -> {
            List<Entity> inserted2 = new ArrayList<>();

            for (int i = 0; i < cnt; i++) {
              EntityImpl person = new EntityImpl("TRPerson");
              person.field("name", Character.toString((char) ('a' + i)));
              person.field("surname", Character.toString((char) ('a' + (i % 3))));
              person.field("myversion", 0);
              person.field("in", new HashSet<EntityImpl>());
              person.field("out", new HashSet<EntityImpl>());

              if (i >= 1) {
                EntityImpl edge = new EntityImpl("TREdge");
                edge.field("in", person.getIdentity());
                edge.field("out", inserted2.get(i - 1));
                (person.<Set<EntityImpl>>getProperty("out")).add(edge);
                ((inserted2.get(i - 1)).<Set<EntityImpl>>getProperty("in")).add(edge);
                edge.save();
              }

              inserted2.add(person);
              person.save();
            }

            for (int i = 0; i < cnt; i++) {
              if (i != cnt - 1) {
                var doc = database.bindToSession((EntityImpl) inserted.get(i));
                doc.setProperty("myversion", 2);
                doc.save();
              }
            }

            var doc = ((EntityImpl) inserted.get(cnt - 1));
            database.bindToSession(doc).delete();

            throw new IllegalStateException();
          });
      Assert.fail();
    } catch (IllegalStateException e) {
      Assert.assertTrue(true);
    }

    final ResultSet result2 = database.command("select from TRPerson");
    Assert.assertNotNull(result2);
    Assert.assertEquals(result2.stream().count(), cnt);
  }

  @Test
  public void testQueryIsolation() {
    database.begin();
    var v = database.newVertex();

    v.setProperty("purpose", "testQueryIsolation");
    v.save();

    var result =
        database
            .query("select from V where purpose = 'testQueryIsolation'")
            .entityStream()
            .toList();
    Assert.assertEquals(result.size(), 1);

    database.commit();

    result =
        database
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
    var account = database.newEntity("Account");
    account.setProperty("name", "John Grisham");
    database.begin();
    account = database.save(account);
    database.commit();

    database.begin();
    account = database.bindToSession(account);
    var address1 = database.newEntity("Address");
    address1.setProperty("street", "Mulholland drive");
    address1.save();

    var address2 = database.newEntity("Address");
    address2.setProperty("street", "Via Veneto");

    List<Entity> addresses = new ArrayList<>();
    addresses.add(address1);
    addresses.add(address2);

    account.setProperty("addresses", addresses);

    account = database.save(account);
    database.commit();

    database.begin();
    account = database.bindToSession(account);
    String originalName = account.getProperty("name");
    Assert.assertEquals(account.<List<Identifiable>>getProperty("addresses").size(), 2);
    account
        .<List<Identifiable>>getProperty("addresses")
        .remove(1); // delete one of the objects in the Books collection to see how rollback behaves
    Assert.assertEquals(account.<List<Identifiable>>getProperty("addresses").size(), 1);
    account.setProperty(
        "name", "New Name"); // change an attribute to see if the change is rolled back
    account = database.save(account);

    Assert.assertEquals(
        account.<List<Identifiable>>getProperty("addresses").size(),
        1); // before rollback this is fine because one of the books was removed

    database.rollback(); // rollback the transaction

    account = database.bindToSession(account);
    Assert.assertEquals(
        account.<List<Identifiable>>getProperty("addresses").size(),
        2); // this is fine, author still linked to 2 books
    Assert.assertEquals(account.getProperty("name"), originalName); // name is restored

    int bookCount = 0;
    for (var b : database.browseClass("Address")) {
      var street = b.getProperty("street");
      if ("Mulholland drive".equals(street) || "Via Veneto".equals(street)) {
        bookCount++;
      }
    }

    Assert.assertEquals(bookCount, 2); // this fails, only 1 entry in the datastore :(
  }

  public void testTransactionsCache() {
    Assert.assertFalse(database.getTransaction().isActive());
    Schema schema = database.getMetadata().getSchema();
    SchemaClass classA = schema.createClass("TransA");
    classA.createProperty(database, "name", PropertyType.STRING);
    EntityImpl doc = new EntityImpl(classA);
    doc.field("name", "test1");

    database.begin();
    doc.save();
    database.commit();
    RID orid = doc.getIdentity();
    database.begin();
    Assert.assertTrue(database.getTransaction().isActive());
    doc = orid.getRecord();
    Assert.assertEquals(doc.field("name"), "test1");
    doc.field("name", "test2");
    doc = orid.getRecord();
    Assert.assertEquals(doc.field("name"), "test2");
    // There is NO SAVE!
    database.commit();

    doc = orid.getRecord();
    Assert.assertEquals(doc.field("name"), "test1");
  }
}
