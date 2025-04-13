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

import com.jetbrains.youtrack.db.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import com.jetbrains.youtrack.db.internal.core.sql.executor.FetchFromIndexStep;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import com.jetbrains.youtrack.db.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@SuppressWarnings({"deprecation", "unchecked"})
@Test
public class IndexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public IndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateCompanyData();
  }

  public void testDuplicatedIndexOnUnique() {
    var jayMiner = database.newEntity("Profile");
    jayMiner.setProperty("nick", "Jay");
    jayMiner.setProperty("name", "Jay");
    jayMiner.setProperty("surname", "Miner");

    database.begin();
    database.save(jayMiner);
    database.commit();

    var jacobMiner = database.newEntity("Profile");
    jacobMiner.setProperty("nick", "Jay");
    jacobMiner.setProperty("name", "Jacob");
    jacobMiner.setProperty("surname", "Miner");

    try {
      database.begin();
      database.save(jacobMiner);
      database.commit();

      // IT SHOULD GIVE ERROR ON DUPLICATED KEY
      Assert.fail();

    } catch (RecordDuplicatedException e) {
      Assert.assertTrue(true);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInUniqueIndex() {
    checkEmbeddedDB();
    Assert.assertEquals(
        database.getMetadata().getSchema().getClassInternal("Profile")
            .getInvolvedIndexesInternal(database, "nick").iterator().next().getType(),
        SchemaClass.INDEX_TYPE.UNIQUE.toString());
    try (var resultSet =
        database.query(
            "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0'"
                + " ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")) {
      assertIndexUsage(resultSet);

      final List<String> expectedSurnames =
          new ArrayList<>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (final Entity profile : result) {
        expectedSurnames.remove(profile.<String>getProperty("surname"));
      }

      Assert.assertEquals(expectedSurnames.size(), 0);
    }
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testUseOfIndex() {
    final List<EntityImpl> result = executeQuery("select * from Profile where nick = 'Jay'");

    Assert.assertFalse(result.isEmpty());

    Entity record;
    for (EntityImpl entries : result) {
      record = entries;
      Assert.assertTrue(record.<String>getProperty("name").equalsIgnoreCase("Jay"));
    }
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testIndexEntries() {
    checkEmbeddedDB();

    List<EntityImpl> result = executeQuery("select * from Profile where nick is not null");

    Index idx =
        database.getMetadata().getIndexManagerInternal().getIndex(database, "Profile.nick");

    Assert.assertEquals(idx.getInternal().size(database), result.size());
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testIndexSize() {
    checkEmbeddedDB();

    List<EntityImpl> result = executeQuery("select * from Profile where nick is not null");

    int profileSize = result.size();

    Assert.assertEquals(
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "Profile.nick")
            .getInternal()
            .size(database),
        profileSize);
    for (int i = 0; i < 10; i++) {
      database.begin();
      Entity profile = database.newEntity("Profile");
      profile.setProperty("nick", "Yay-" + i);
      profile.setProperty("name", "Jay");
      profile.setProperty("surname", "Miner");
      database.save(profile);
      database.commit();

      profileSize++;
      try (Stream<RID> stream =
          database
              .getMetadata()
              .getIndexManagerInternal()
              .getIndex(database, "Profile.nick")
              .getInternal()
              .getRids(database, "Yay-" + i)) {
        Assert.assertTrue(stream.findAny().isPresent());
      }
    }
  }

  @Test(dependsOnMethods = "testUseOfIndex")
  public void testChangeOfIndexToNotUnique() {
    dropIndexes("Profile", "nick");

    database
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty(database, "nick")
        .createIndex(database, INDEX_TYPE.NOTUNIQUE);
  }

  private void dropIndexes(String className, String propertyName) {
    for (var indexName : database.getMetadata().getSchema().getClassInternal(className)
        .getPropertyInternal(database, propertyName).getAllIndexes(database)) {
      database.getMetadata().getIndexManagerInternal().dropIndex(database, indexName);
    }
  }

  @Test(dependsOnMethods = "testChangeOfIndexToNotUnique")
  public void testDuplicatedIndexOnNotUnique() {
    database.begin();
    Entity nickNolte = database.newEntity("Profile");
    nickNolte.setProperty("nick", "Jay");
    nickNolte.setProperty("name", "Nick");
    nickNolte.setProperty("surname", "Nolte");

    database.save(nickNolte);
    database.commit();
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnNotUnique")
  public void testChangeOfIndexToUnique() {
    try {
      dropIndexes("Profile", "nick");
      database
          .getMetadata()
          .getSchema()
          .getClass("Profile")
          .getProperty(database, "nick")
          .createIndex(database, INDEX_TYPE.UNIQUE);
      Assert.fail();
    } catch (RecordDuplicatedException e) {
      Assert.assertTrue(true);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMajorSelect() {
    if (database.isRemote()) {
      return;
    }

    try (var resultSet =
        database.query("select * from Profile where nick > 'ZZZJayLongNickIndex3'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks =
          new ArrayList<>(Arrays.asList("ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 2);
      for (Entity profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMajorEqualsSelect() {
    if (database.isRemote()) {
      return;
    }

    try (var resultSet =
        database.query("select * from Profile where nick >= 'ZZZJayLongNickIndex3'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks =
          new ArrayList<>(
              Arrays.asList(
                  "ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (Entity profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMinorSelect() {
    if (database.isRemote()) {
      return;
    }

    try (var resultSet = database.query("select * from Profile where nick < '002'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("000", "001"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 2);
      for (Entity profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMinorEqualsSelect() {
    if (database.isRemote()) {
      return;
    }

    try (var resultSet = database.query("select * from Profile where nick <= '002'")) {
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("000", "001", "002"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (Entity profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments", enabled = false)
  public void testIndexBetweenSelect() {
    if (database.isRemote()) {
      return;
    }

    var query = "select * from Profile where nick between '001' and '004'";
    try (var resultSet = database.query(query)) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("001", "002", "003", "004"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 4);
      for (Entity profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments", enabled = false)
  public void testIndexInComplexSelectOne() {
    if (database.isRemote()) {
      return;
    }

    try (var resultSet =
        database.query(
            "select * from Profile where (name = 'Giuseppe' OR name <> 'Napoleone') AND"
                + " (nick is not null AND (name = 'Giuseppe' OR name <> 'Napoleone') AND"
                + " (nick >= 'ZZZJayLongNickIndex3'))")) {
      assertIndexUsage(resultSet);

      final List<String> expectedNicks =
          new ArrayList<>(
              Arrays.asList(
                  "ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (Entity profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments", enabled = false)
  public void testIndexInComplexSelectTwo() {
    if (database.isRemote()) {
      return;
    }

    try (var resultSet =
        database.query(
            "select * from Profile where ((name = 'Giuseppe' OR name <> 'Napoleone') AND"
                + " (nick is not null AND (name = 'Giuseppe' OR name <> 'Napoleone') AND"
                + " (nick >= 'ZZZJayLongNickIndex3' OR nick >= 'ZZZJayLongNickIndex4')))")) {
      assertIndexUsage(resultSet);

      final List<String> expectedNicks =
          new ArrayList<>(
              Arrays.asList(
                  "ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));
      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (Entity profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  public void populateIndexDocuments() {
    for (int i = 0; i <= 5; i++) {
      database.begin();
      final Entity profile = database.newEntity("Profile");
      profile.setProperty("nick", "ZZZJayLongNickIndex" + i);
      profile.setProperty("name", "NickIndex" + i);
      profile.setProperty("surname", "NolteIndex" + i);
      database.save(profile);
      database.commit();
    }

    for (int i = 0; i <= 5; i++) {
      database.begin();
      final Entity profile = database.newEntity("Profile");
      profile.setProperty("nick", "00" + i);
      profile.setProperty("name", "NickIndex" + i);
      profile.setProperty("surname", "NolteIndex" + i);
      database.save(profile);
      database.commit();
    }
  }

  @Test(dependsOnMethods = "testChangeOfIndexToUnique")
  public void removeNotUniqueIndexOnNick() {
    dropIndexes("Profile", "nick");
  }

  @Test(dependsOnMethods = "removeNotUniqueIndexOnNick")
  public void testQueryingWithoutNickIndex() {
    Assert.assertFalse(
        database.getMetadata().getSchema().getClass("Profile")
            .getInvolvedIndexes(database, "name").isEmpty());
    Assert.assertTrue(
        database.getMetadata().getSchema().getClass("Profile").getInvolvedIndexes(database, "nick")
            .isEmpty());

    List<EntityImpl> result =
        database
            .command(new SQLSynchQuery<EntityImpl>("SELECT FROM Profile WHERE nick = 'Jay'"))
            .execute(database);
    Assert.assertEquals(result.size(), 2);

    result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Jay'"))
            .execute(database);
    Assert.assertEquals(result.size(), 1);

    result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Nick'"))
            .execute(database);
    Assert.assertEquals(result.size(), 1);
  }

  @Test(dependsOnMethods = "testQueryingWithoutNickIndex")
  public void createNotUniqueIndexOnNick() {
    database
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty(database, "nick")
        .createIndex(database, INDEX_TYPE.NOTUNIQUE);
  }

  @Test(dependsOnMethods = {"createNotUniqueIndexOnNick", "populateIndexDocuments"})
  public void testIndexInNotUniqueIndex() {
    Assert.assertEquals(
        database.getClassInternal("Profile").
            getInvolvedIndexesInternal(database, "nick").iterator()
            .next().getType(),
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString());

    try (var resultSet =
        database.query(
            "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0'"
                + " ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")) {
      final List<String> expectedSurnames =
          new ArrayList<>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (final Entity profile : result) {
        expectedSurnames.remove(profile.<String>getProperty("surname"));
      }

      Assert.assertEquals(expectedSurnames.size(), 0);
    }
  }

  @Test
  public void indexLinks() {
    checkEmbeddedDB();

    database
        .getMetadata()
        .getSchema()
        .getClass("Whiz")
        .getProperty(database, "account")
        .createIndex(database, INDEX_TYPE.NOTUNIQUE);

    final List<EntityImpl> result = executeQuery("select * from Account limit 1");
    final Index idx =
        database.getMetadata().getIndexManagerInternal().getIndex(database, "Whiz.account");

    for (int i = 0; i < 5; i++) {
      database.begin();
      final EntityImpl whiz = new EntityImpl("Whiz");

      whiz.field("id", i);
      whiz.field("text", "This is a test");
      whiz.field("account", result.get(0).getIdentity());

      whiz.save();
      database.commit();
    }

    Assert.assertEquals(idx.getInternal().size(database), 5);

    final List<EntityImpl> indexedResult =
        executeQuery("select * from Whiz where account = ?", result.get(0).getIdentity());
    Assert.assertEquals(indexedResult.size(), 5);

    database.begin();
    for (final EntityImpl resDoc : indexedResult) {
      database.bindToSession(resDoc).delete();
    }

    Entity whiz = new EntityImpl("Whiz");
    whiz.setProperty("id", 100);
    whiz.setProperty("text", "This is a test!");
    whiz.setProperty("account", new EntityImpl("Company").field("id", 9999));
    whiz.save();
    database.commit();

    database.begin();
    whiz = database.bindToSession(whiz);
    Assert.assertTrue(((EntityImpl) whiz.getProperty("account")).getIdentity().isValid());
    ((EntityImpl) whiz.getProperty("account")).delete();
    whiz.delete();
    database.commit();
  }

  public void linkedIndexedProperty() {
    try (DatabaseSessionInternal db = acquireSession()) {
      if (!db.getMetadata().getSchema().existsClass("TestClass")) {
        SchemaClass testClass =
            db.getMetadata().getSchema().createClass("TestClass", 1, (SchemaClass[]) null);
        SchemaClass testLinkClass =
            db.getMetadata().getSchema().createClass("TestLinkClass", 1, (SchemaClass[]) null);
        testClass
            .createProperty(db, "testLink", PropertyType.LINK, testLinkClass)
            .createIndex(db, INDEX_TYPE.NOTUNIQUE);
        testClass.createProperty(db, "name", PropertyType.STRING)
            .createIndex(db, INDEX_TYPE.UNIQUE);
        testLinkClass.createProperty(db, "testBoolean", PropertyType.BOOLEAN);
        testLinkClass.createProperty(db, "testString", PropertyType.STRING);
      }
      EntityImpl testClassDocument = db.newInstance("TestClass");
      db.begin();
      testClassDocument.field("name", "Test Class 1");
      EntityImpl testLinkClassDocument = new EntityImpl("TestLinkClass");
      testLinkClassDocument.field("testString", "Test Link Class 1");
      testLinkClassDocument.field("testBoolean", true);
      testClassDocument.field("testLink", testLinkClassDocument);
      testClassDocument.save();
      db.commit();
      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.Boolean
      List<EntityImpl> result =
          db.query(
              new SQLSynchQuery<EntityImpl>(
                  "select from TestClass where testLink.testBoolean = true"));
      Assert.assertEquals(result.size(), 1);
      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.String
      result =
          db.query(
              new SQLSynchQuery<EntityImpl>(
                  "select from TestClass where testLink.testString = 'Test Link Class 1'"));
      Assert.assertEquals(result.size(), 1);
    }
  }

  @Test(dependsOnMethods = "linkedIndexedProperty")
  public void testLinkedIndexedPropertyInTx() {
    try (DatabaseSessionInternal db = acquireSession()) {
      db.begin();
      EntityImpl testClassDocument = db.newInstance("TestClass");
      testClassDocument.field("name", "Test Class 2");
      EntityImpl testLinkClassDocument = new EntityImpl("TestLinkClass");
      testLinkClassDocument.field("testString", "Test Link Class 2");
      testLinkClassDocument.field("testBoolean", true);
      testClassDocument.field("testLink", testLinkClassDocument);
      testClassDocument.save();
      db.commit();

      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.Boolean
      List<EntityImpl> result =
          db.query(
              new SQLSynchQuery<EntityImpl>(
                  "select from TestClass where testLink.testBoolean = true"));
      Assert.assertEquals(result.size(), 2);
      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.String
      result =
          db.query(
              new SQLSynchQuery<EntityImpl>(
                  "select from TestClass where testLink.testString = 'Test Link Class 2'"));
      Assert.assertEquals(result.size(), 1);
    }
  }

  public void testConcurrentRemoveDelete() {
    checkEmbeddedDB();

    try (DatabaseSessionInternal db = acquireSession()) {
      if (!db.getMetadata().getSchema().existsClass("MyFruit")) {
        SchemaClass fruitClass = db.getMetadata().getSchema()
            .createClass("MyFruit", 1, (SchemaClass[]) null);
        fruitClass.createProperty(db, "name", PropertyType.STRING);
        fruitClass.createProperty(db, "color", PropertyType.STRING);

        db.getMetadata()
            .getSchema()
            .getClass("MyFruit")
            .getProperty(database, "name")
            .createIndex(db, INDEX_TYPE.UNIQUE);

        db.getMetadata()
            .getSchema()
            .getClass("MyFruit")
            .getProperty(database, "color")
            .createIndex(db, INDEX_TYPE.NOTUNIQUE);
      }

      long expectedIndexSize = 0;

      final int passCount = 10;
      final int chunkSize = 10;

      for (int pass = 0; pass < passCount; pass++) {
        List<EntityImpl> recordsToDelete = new ArrayList<>();
        db.begin();
        for (int i = 0; i < chunkSize; i++) {
          EntityImpl d =
              new EntityImpl("MyFruit")
                  .field("name", "ABC" + pass + 'K' + i)
                  .field("color", "FOO" + pass);
          d.save();
          if (i < chunkSize / 2) {
            recordsToDelete.add(d);
          }
        }
        db.commit();

        expectedIndexSize += chunkSize;
        Assert.assertEquals(
            db.getMetadata()
                .getIndexManagerInternal()
                .getClassIndex(db, "MyFruit", "MyFruit.color")
                .getInternal()
                .size(db),
            expectedIndexSize,
            "After add");

        // do delete
        db.begin();
        for (final EntityImpl recordToDelete : recordsToDelete) {
          db.delete(db.bindToSession(recordToDelete));
        }
        db.commit();

        expectedIndexSize -= recordsToDelete.size();
        Assert.assertEquals(
            db.getMetadata()
                .getIndexManagerInternal()
                .getClassIndex(db, "MyFruit", "MyFruit.color")
                .getInternal()
                .size(db),
            expectedIndexSize,
            "After delete");
      }
    }
  }

  public void testIndexParamsAutoConversion() {
    checkEmbeddedDB();

    final EntityImpl doc;
    final RecordId result;
    try (DatabaseSessionInternal db = acquireSession()) {
      if (!db.getMetadata().getSchema().existsClass("IndexTestTerm")) {
        final SchemaClass termClass =
            db.getMetadata().getSchema().createClass("IndexTestTerm", 1, (SchemaClass[]) null);
        termClass.createProperty(db, "label", PropertyType.STRING);
        termClass.createIndex(db,
            "idxTerm",
            INDEX_TYPE.UNIQUE.toString(),
            null,
            Map.of("ignoreNullValues", true), new String[]{"label"});
      }

      db.begin();
      doc = new EntityImpl("IndexTestTerm");
      doc.field("label", "42");
      doc.save();
      db.commit();

      try (Stream<RID> stream =
          db.getMetadata()
              .getIndexManagerInternal()
              .getIndex(db, "idxTerm")
              .getInternal()
              .getRids(db, "42")) {
        result = (RecordId) stream.findAny().orElse(null);
      }
    }
    Assert.assertNotNull(result);
    Assert.assertEquals(result.getIdentity(), doc.getIdentity());
  }

  public void testTransactionUniqueIndexTestOne() {
    checkEmbeddedDB();

    DatabaseSessionInternal db = acquireSession();
    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexTest")) {
      final SchemaClass termClass =
          db.getMetadata()
              .getSchema()
              .createClass("TransactionUniqueIndexTest", 1, (SchemaClass[]) null);
      termClass.createProperty(db, "label", PropertyType.STRING);
      termClass.createIndex(db,
          "idxTransactionUniqueIndexTest",
          INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true), new String[]{"label"});
    }

    db.begin();
    EntityImpl docOne = new EntityImpl("TransactionUniqueIndexTest");
    docOne.field("label", "A");
    docOne.save();
    db.commit();

    final Index index =
        db.getMetadata().getIndexManagerInternal().getIndex(db, "idxTransactionUniqueIndexTest");
    Assert.assertEquals(index.getInternal().size(database), 1);

    db.begin();
    try {
      EntityImpl docTwo = new EntityImpl("TransactionUniqueIndexTest");
      docTwo.field("label", "A");
      docTwo.save();

      db.commit();
      Assert.fail();
    } catch (RecordDuplicatedException ignored) {
    }

    Assert.assertEquals(index.getInternal().size(database), 1);
  }

  @Test(dependsOnMethods = "testTransactionUniqueIndexTestOne")
  public void testTransactionUniqueIndexTestTwo() {
    checkEmbeddedDB();

    DatabaseSessionInternal db = acquireSession();
    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexTest")) {
      final SchemaClass termClass =
          db.getMetadata()
              .getSchema()
              .createClass("TransactionUniqueIndexTest", 1, (SchemaClass[]) null);

      termClass.createProperty(db, "label", PropertyType.STRING);
      termClass.createIndex(db,
          "idxTransactionUniqueIndexTest",
          INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true), new String[]{"label"});
    }
    final Index index =
        db.getMetadata().getIndexManagerInternal().getIndex(db, "idxTransactionUniqueIndexTest");
    Assert.assertEquals(index.getInternal().size(database), 1);

    db.begin();
    try {
      EntityImpl docOne = new EntityImpl("TransactionUniqueIndexTest");
      docOne.field("label", "B");
      docOne.save();

      EntityImpl docTwo = new EntityImpl("TransactionUniqueIndexTest");
      docTwo.field("label", "B");
      docTwo.save();

      db.commit();
      Assert.fail();
    } catch (RecordDuplicatedException oie) {
      db.rollback();
    }

    Assert.assertEquals(index.getInternal().size(database), 1);
  }

  public void testTransactionUniqueIndexTestWithDotNameOne() {
    checkEmbeddedDB();

    DatabaseSessionInternal db = acquireSession();
    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexWithDotTest")) {
      final SchemaClass termClass =
          db.getMetadata()
              .getSchema()
              .createClass("TransactionUniqueIndexWithDotTest", 1, (SchemaClass[]) null);
      termClass.createProperty(db, "label", PropertyType.STRING).createIndex(db, INDEX_TYPE.UNIQUE);
    }

    db.begin();
    EntityImpl docOne = new EntityImpl("TransactionUniqueIndexWithDotTest");
    docOne.field("label", "A");
    docOne.save();
    db.commit();

    final Index index =
        db.getMetadata()
            .getIndexManagerInternal()
            .getIndex(db, "TransactionUniqueIndexWithDotTest.label");
    Assert.assertEquals(index.getInternal().size(database), 1);

    long countClassBefore = db.countClass("TransactionUniqueIndexWithDotTest");
    db.begin();
    try {
      EntityImpl docTwo = new EntityImpl("TransactionUniqueIndexWithDotTest");
      docTwo.field("label", "A");
      docTwo.save();

      db.commit();
      Assert.fail();
    } catch (RecordDuplicatedException ignored) {
    }

    Assert.assertEquals(
        ((List<EntityImpl>)
            db.command(new CommandSQL("select from TransactionUniqueIndexWithDotTest"))
                .execute(db))
            .size(),
        countClassBefore);

    Assert.assertEquals(index.getInternal().size(db), 1);
  }

  @Test(dependsOnMethods = "testTransactionUniqueIndexTestWithDotNameOne")
  public void testTransactionUniqueIndexTestWithDotNameTwo() {
    checkEmbeddedDB();

    DatabaseSessionInternal db = acquireSession();
    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexWithDotTest")) {
      final SchemaClass termClass =
          db.getMetadata()
              .getSchema()
              .createClass("TransactionUniqueIndexWithDotTest", 1, (SchemaClass[]) null);
      termClass.createProperty(db, "label", PropertyType.STRING)
          .createIndex(database, INDEX_TYPE.UNIQUE);
    }

    final Index index =
        db.getMetadata()
            .getIndexManagerInternal()
            .getIndex(db, "TransactionUniqueIndexWithDotTest.label");
    Assert.assertEquals(index.getInternal().size(database), 1);

    db.begin();
    try {
      EntityImpl docOne = new EntityImpl("TransactionUniqueIndexWithDotTest");
      docOne.field("label", "B");
      docOne.save();

      EntityImpl docTwo = new EntityImpl("TransactionUniqueIndexWithDotTest");
      docTwo.field("label", "B");
      docTwo.save();

      db.commit();
      Assert.fail();
    } catch (RecordDuplicatedException oie) {
      db.rollback();
    }

    Assert.assertEquals(index.getInternal().size(database), 1);
  }

  @Test(dependsOnMethods = "linkedIndexedProperty")
  public void testIndexRemoval() {
    checkEmbeddedDB();

    final Index index = getIndex("Profile.nick");

    Iterator<RawPair<Object, RID>> streamIterator;
    Object key;
    try (Stream<RawPair<Object, RID>> stream = index.getInternal().stream(database)) {
      streamIterator = stream.iterator();
      Assert.assertTrue(streamIterator.hasNext());

      RawPair<Object, RID> pair = streamIterator.next();
      key = pair.first;

      database.begin();
      pair.second.getRecord().delete();
      database.commit();
    }

    try (Stream<RID> stream = index.getInternal().getRids(database, key)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  public void createInheritanceIndex() {
    try (DatabaseSessionInternal db = acquireSession()) {
      if (!db.getMetadata().getSchema().existsClass("BaseTestClass")) {
        SchemaClass baseClass =
            db.getMetadata().getSchema().createClass("BaseTestClass", 1, (SchemaClass[]) null);
        SchemaClass childClass =
            db.getMetadata().getSchema().createClass("ChildTestClass", 1, (SchemaClass[]) null);
        SchemaClass anotherChildClass =
            db.getMetadata().getSchema()
                .createClass("AnotherChildTestClass", 1, (SchemaClass[]) null);

        if (!baseClass.isSuperClassOf(db, childClass)) {
          childClass.setSuperClass(db, baseClass);
        }
        if (!baseClass.isSuperClassOf(db, anotherChildClass)) {
          anotherChildClass.setSuperClass(db, baseClass);
        }

        baseClass
            .createProperty(db, "testParentProperty", PropertyType.LONG)
            .createIndex(db, INDEX_TYPE.NOTUNIQUE);
      }

      db.begin();
      EntityImpl childClassDocument = db.newInstance("ChildTestClass");
      childClassDocument.field("testParentProperty", 10L);
      childClassDocument.save();

      EntityImpl anotherChildClassDocument = db.newInstance("AnotherChildTestClass");
      anotherChildClassDocument.field("testParentProperty", 11L);
      anotherChildClassDocument.save();
      db.commit();

      Assert.assertNotEquals(
          childClassDocument.getIdentity(), new RecordId(-1, RID.CLUSTER_POS_INVALID));
      Assert.assertNotEquals(
          anotherChildClassDocument.getIdentity(), new RecordId(-1, RID.CLUSTER_POS_INVALID));
    }
  }

  @Test(dependsOnMethods = "createInheritanceIndex")
  public void testIndexReturnOnlySpecifiedClass() {

    try (ResultSet result =
        database.command("select * from ChildTestClass where testParentProperty = 10")) {

      Assert.assertEquals(10L, result.next().<Object>getProperty("testParentProperty"));
      Assert.assertFalse(result.hasNext());
    }

    try (ResultSet result =
        database.command("select * from AnotherChildTestClass where testParentProperty = 11")) {
      Assert.assertEquals(11L, result.next().<Object>getProperty("testParentProperty"));
      Assert.assertFalse(result.hasNext());
    }
  }

  public void testNotUniqueIndexKeySize() {
    checkEmbeddedDB();

    final Schema schema = database.getMetadata().getSchema();
    SchemaClass cls = schema.createClass("IndexNotUniqueIndexKeySize");
    cls.createProperty(database, "value", PropertyType.INTEGER);
    cls.createIndex(database, "IndexNotUniqueIndexKeySizeIndex", INDEX_TYPE.NOTUNIQUE, "value");

    IndexManagerAbstract idxManager = database.getMetadata().getIndexManagerInternal();

    final Index idx = idxManager.getIndex(database, "IndexNotUniqueIndexKeySizeIndex");

    final Set<Integer> keys = new HashSet<>();
    for (int i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      database.begin();
      final EntityImpl doc = new EntityImpl("IndexNotUniqueIndexKeySize");
      doc.field("value", key);
      doc.save();
      database.commit();

      keys.add(key);
    }

    try (Stream<RawPair<Object, RID>> stream = idx.getInternal().stream(database)) {
      Assert.assertEquals(stream.map((pair) -> pair.first).distinct().count(), keys.size());
    }
  }

  public void testNotUniqueIndexSize() {
    checkEmbeddedDB();

    final Schema schema = database.getMetadata().getSchema();
    SchemaClass cls = schema.createClass("IndexNotUniqueIndexSize");
    cls.createProperty(database, "value", PropertyType.INTEGER);
    cls.createIndex(database, "IndexNotUniqueIndexSizeIndex", INDEX_TYPE.NOTUNIQUE, "value");

    IndexManagerAbstract idxManager = database.getMetadata().getIndexManagerInternal();
    final Index idx = idxManager.getIndex(database, "IndexNotUniqueIndexSizeIndex");

    for (int i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      database.begin();
      final EntityImpl doc = new EntityImpl("IndexNotUniqueIndexSize");
      doc.field("value", key);
      doc.save();
      database.commit();
    }

    Assert.assertEquals(idx.getInternal().size(database), 99);
  }

  @Test
  public void testIndexRebuildDuringNonProxiedObjectDelete() {
    checkEmbeddedDB();

    database.begin();
    Entity profile = database.newEntity("Profile");
    profile.setProperty("nick", "NonProxiedObjectToDelete");
    profile.setProperty("name", "NonProxiedObjectToDelete");
    profile.setProperty("surname", "NonProxiedObjectToDelete");
    profile = database.save(profile);
    database.commit();

    IndexManagerAbstract idxManager = database.getMetadata().getIndexManagerInternal();
    Index nickIndex = idxManager.getIndex(database, "Profile.nick");

    try (Stream<RID> stream = nickIndex.getInternal()
        .getRids(database, "NonProxiedObjectToDelete")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    database.begin();
    final Entity loadedProfile = database.load(profile.getIdentity());
    database.delete(loadedProfile);
    database.commit();

    try (Stream<RID> stream = nickIndex.getInternal()
        .getRids(database, "NonProxiedObjectToDelete")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  @Test(dependsOnMethods = "testIndexRebuildDuringNonProxiedObjectDelete")
  public void testIndexRebuildDuringDetachAllNonProxiedObjectDelete() {
    checkEmbeddedDB();

    database.begin();
    Entity profile = database.newEntity("Profile");
    profile.setProperty("nick", "NonProxiedObjectToDelete");
    profile.setProperty("name", "NonProxiedObjectToDelete");
    profile.setProperty("surname", "NonProxiedObjectToDelete");
    profile = database.save(profile);
    database.commit();

    IndexManagerAbstract idxManager = database.getMetadata().getIndexManagerInternal();
    Index nickIndex = idxManager.getIndex(database, "Profile.nick");

    try (Stream<RID> stream = nickIndex.getInternal()
        .getRids(database, "NonProxiedObjectToDelete")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    final Entity loadedProfile = database.load(profile.getIdentity());
    database.begin();
    database.delete(database.bindToSession(loadedProfile));
    database.commit();

    try (Stream<RID> stream = nickIndex.getInternal()
        .getRids(database, "NonProxiedObjectToDelete")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  @Test(dependsOnMethods = "testIndexRebuildDuringDetachAllNonProxiedObjectDelete")
  public void testRestoreUniqueIndex() {
    dropIndexes("Profile", "nick");
    database
        .command(
            "CREATE INDEX Profile.nick on Profile (nick) UNIQUE METADATA {ignoreNullValues: true}")
        .close();
    database.getMetadata().reload();
  }

  @Test
  public void testIndexInCompositeQuery() {
    SchemaClass classOne =
        database.getMetadata().getSchema()
            .createClass("CompoundSQLIndexTest1", 1, (SchemaClass[]) null);
    SchemaClass classTwo =
        database.getMetadata().getSchema()
            .createClass("CompoundSQLIndexTest2", 1, (SchemaClass[]) null);

    classTwo.createProperty(database, "address", PropertyType.LINK, classOne);

    classTwo.createIndex(database, "CompoundSQLIndexTestIndex", INDEX_TYPE.UNIQUE, "address");

    database.begin();
    EntityImpl docOne = new EntityImpl("CompoundSQLIndexTest1");
    docOne.field("city", "Montreal");

    docOne.save();

    EntityImpl docTwo = new EntityImpl("CompoundSQLIndexTest2");
    docTwo.field("address", docOne);
    docTwo.save();
    database.commit();

    List<EntityImpl> result =
        executeQuery(
            "select from CompoundSQLIndexTest2 where address in (select from"
                + " CompoundSQLIndexTest1 where city='Montreal')");
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).getIdentity(), docTwo.getIdentity());
  }

  public void testIndexWithLimitAndOffset() {
    final Schema schema = database.getSchema();
    final SchemaClass indexWithLimitAndOffset =
        schema.createClass("IndexWithLimitAndOffsetClass", 1, (SchemaClass[]) null);
    indexWithLimitAndOffset.createProperty(database, "val", PropertyType.INTEGER);
    indexWithLimitAndOffset.createProperty(database, "index", PropertyType.INTEGER);

    database
        .command(
            "create index IndexWithLimitAndOffset on IndexWithLimitAndOffsetClass (val) notunique")
        .close();

    for (int i = 0; i < 30; i++) {
      database.begin();
      final EntityImpl document = new EntityImpl("IndexWithLimitAndOffsetClass");
      document.field("val", i / 10);
      document.field("index", i);
      document.save();
      database.commit();
    }

    final List<EntityImpl> result =
        executeQuery("select from IndexWithLimitAndOffsetClass where val = 1 offset 5 limit 2");
    Assert.assertEquals(result.size(), 2);

    for (int i = 0; i < 2; i++) {
      final EntityImpl document = result.get(i);
      Assert.assertEquals(document.<Object>field("val"), 1);
      Assert.assertEquals(document.<Object>field("index"), 15 + i);
    }
  }

  public void testNullIndexKeysSupport() {
    final Schema schema = database.getSchema();
    final SchemaClass clazz = schema.createClass("NullIndexKeysSupport", 1, (SchemaClass[]) null);
    clazz.createProperty(database, "nullField", PropertyType.STRING);

    var metadata = Map.of("ignoreNullValues", false);

    clazz.createIndex(database,
        "NullIndexKeysSupportIndex",
        INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"nullField"});
    for (int i = 0; i < 20; i++) {
      database.begin();
      if (i % 5 == 0) {
        EntityImpl document = new EntityImpl("NullIndexKeysSupport");
        document.field("nullField", (Object) null);
        document.save();
      } else {
        EntityImpl document = new EntityImpl("NullIndexKeysSupport");
        document.field("nullField", "val" + i);
        document.save();
      }
      database.commit();
    }

    List<EntityImpl> result =
        executeQuery("select from NullIndexKeysSupport where nullField = 'val3'");
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).field("nullField"), "val3");

    final String query = "select from NullIndexKeysSupport where nullField is null";
    result = executeQuery("select from NullIndexKeysSupport where nullField is null");

    Assert.assertEquals(result.size(), 4);
    for (EntityImpl document : result) {
      Assert.assertNull(document.field("nullField"));
    }

    final EntityImpl explain = database.command(new CommandSQL("explain " + query))
        .execute(database);
    Assert.assertTrue(
        explain.<Set<String>>field("involvedIndexes").contains("NullIndexKeysSupportIndex"));
  }

  public void testNullHashIndexKeysSupport() {
    final Schema schema = database.getSchema();
    final SchemaClass clazz = schema.createClass("NullHashIndexKeysSupport", 1,
        (SchemaClass[]) null);
    clazz.createProperty(database, "nullField", PropertyType.STRING);

    var metadata = Map.of("ignoreNullValues", false);

    clazz.createIndex(database,
        "NullHashIndexKeysSupportIndex",
        INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"nullField"});
    for (int i = 0; i < 20; i++) {
      database.begin();
      if (i % 5 == 0) {
        EntityImpl document = new EntityImpl("NullHashIndexKeysSupport");
        document.field("nullField", (Object) null);
        document.save();
      } else {
        EntityImpl document = new EntityImpl("NullHashIndexKeysSupport");
        document.field("nullField", "val" + i);
        document.save();
      }
      database.commit();
    }

    List<EntityImpl> result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from NullHashIndexKeysSupport where nullField = 'val3'"));
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).field("nullField"), "val3");

    final String query = "select from NullHashIndexKeysSupport where nullField is null";
    result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from NullHashIndexKeysSupport where nullField is null"));

    Assert.assertEquals(result.size(), 4);
    for (EntityImpl document : result) {
      Assert.assertNull(document.field("nullField"));
    }

    final EntityImpl explain = database.command(new CommandSQL("explain " + query))
        .execute(database);
    Assert.assertTrue(
        explain.<Set<String>>field("involvedIndexes").contains("NullHashIndexKeysSupportIndex"));
  }

  public void testNullIndexKeysSupportInTx() {
    final Schema schema = database.getMetadata().getSchema();
    final SchemaClass clazz = schema.createClass("NullIndexKeysSupportInTx", 1,
        (SchemaClass[]) null);
    clazz.createProperty(database, "nullField", PropertyType.STRING);

    var metadata = Map.of("ignoreNullValues", false);

    clazz.createIndex(database,
        "NullIndexKeysSupportInTxIndex",
        INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"nullField"});

    database.begin();

    for (int i = 0; i < 20; i++) {
      if (i % 5 == 0) {
        EntityImpl document = new EntityImpl("NullIndexKeysSupportInTx");
        document.field("nullField", (Object) null);
        document.save();
      } else {
        EntityImpl document = new EntityImpl("NullIndexKeysSupportInTx");
        document.field("nullField", "val" + i);
        document.save();
      }
    }

    database.commit();

    List<EntityImpl> result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from NullIndexKeysSupportInTx where nullField = 'val3'"));
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).field("nullField"), "val3");

    final String query = "select from NullIndexKeysSupportInTx where nullField is null";
    result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from NullIndexKeysSupportInTx where nullField is null"));

    Assert.assertEquals(result.size(), 4);
    for (EntityImpl document : result) {
      Assert.assertNull(document.field("nullField"));
    }

    final EntityImpl explain = database.command(new CommandSQL("explain " + query))
        .execute(database);
    Assert.assertTrue(
        explain.<Set<String>>field("involvedIndexes").contains("NullIndexKeysSupportInTxIndex"));
  }

  public void testNullIndexKeysSupportInMiddleTx() {
    if (remoteDB) {
      return;
    }

    final Schema schema = database.getSchema();
    final SchemaClass clazz = schema.createClass("NullIndexKeysSupportInMiddleTx", 1,
        (SchemaClass[]) null);
    clazz.createProperty(database, "nullField", PropertyType.STRING);

    var metadata = Map.of("ignoreNullValues", false);

    clazz.createIndex(database,
        "NullIndexKeysSupportInMiddleTxIndex",
        INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[]{"nullField"});

    database.begin();

    for (int i = 0; i < 20; i++) {
      if (i % 5 == 0) {
        EntityImpl document = new EntityImpl("NullIndexKeysSupportInMiddleTx");
        document.field("nullField", (Object) null);
        document.save();
      } else {
        EntityImpl document = new EntityImpl("NullIndexKeysSupportInMiddleTx");
        document.field("nullField", "val" + i);
        document.save();
      }
    }

    List<EntityImpl> result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from NullIndexKeysSupportInMiddleTx where nullField = 'val3'"));
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).field("nullField"), "val3");

    final String query = "select from NullIndexKeysSupportInMiddleTx where nullField is null";
    result =
        database.query(
            new SQLSynchQuery<EntityImpl>(
                "select from NullIndexKeysSupportInMiddleTx where nullField is null"));

    Assert.assertEquals(result.size(), 4);
    for (EntityImpl document : result) {
      Assert.assertNull(document.field("nullField"));
    }

    final EntityImpl explain = database.command(new CommandSQL("explain " + query))
        .execute(database);
    Assert.assertTrue(
        explain
            .<Set<String>>field("involvedIndexes")
            .contains("NullIndexKeysSupportInMiddleTxIndex"));

    database.commit();
  }

  public void testCreateIndexAbstractClass() {
    final Schema schema = database.getSchema();

    SchemaClass abstractClass = schema.createAbstractClass("TestCreateIndexAbstractClass");
    abstractClass
        .createProperty(database, "value", PropertyType.STRING)
        .setMandatory(database, true)
        .createIndex(database, INDEX_TYPE.UNIQUE);

    schema.createClass("TestCreateIndexAbstractClassChildOne", abstractClass);
    schema.createClass("TestCreateIndexAbstractClassChildTwo", abstractClass);

    database.begin();
    EntityImpl docOne = new EntityImpl("TestCreateIndexAbstractClassChildOne");
    docOne.field("value", "val1");
    docOne.save();

    EntityImpl docTwo = new EntityImpl("TestCreateIndexAbstractClassChildTwo");
    docTwo.field("value", "val2");
    docTwo.save();
    database.commit();

    final String queryOne = "select from TestCreateIndexAbstractClass where value = 'val1'";

    List<EntityImpl> resultOne = executeQuery(queryOne);
    Assert.assertEquals(resultOne.size(), 1);
    Assert.assertEquals(resultOne.get(0).getIdentity(), docOne.getIdentity());

    try (var result = database.command("explain " + queryOne)) {
      var explain = result.next().toEntity();
      Assert.assertTrue(
          explain
              .<String>getProperty("executionPlanAsString")
              .contains("FETCH FROM INDEX TestCreateIndexAbstractClass.value"));

      final String queryTwo = "select from TestCreateIndexAbstractClass where value = 'val2'";

      List<EntityImpl> resultTwo = executeQuery(queryTwo);
      Assert.assertEquals(resultTwo.size(), 1);
      Assert.assertEquals(resultTwo.get(0).getIdentity(), docTwo.getIdentity());

      explain = database.command(new CommandSQL("explain " + queryTwo)).execute(database);
      Assert.assertTrue(
          explain
              .<Collection<String>>getProperty("involvedIndexes")
              .contains("TestCreateIndexAbstractClass.value"));
    }
  }

  @Test(enabled = false)
  public void testValuesContainerIsRemovedIfIndexIsRemoved() {
    if (remoteDB) {
      return;
    }

    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz =
        schema.createClass("ValuesContainerIsRemovedIfIndexIsRemovedClass", 1,
            (SchemaClass[]) null);
    clazz.createProperty(database, "val", PropertyType.STRING);

    database
        .command(
            "create index ValuesContainerIsRemovedIfIndexIsRemovedIndex on"
                + " ValuesContainerIsRemovedIfIndexIsRemovedClass (val) notunique")
        .close();

    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 100; j++) {
        database.begin();
        EntityImpl document = new EntityImpl("ValuesContainerIsRemovedIfIndexIsRemovedClass");
        document.field("val", "value" + i);
        document.save();
        database.commit();
      }
    }

    final AbstractPaginatedStorage storageLocalAbstract =
        (AbstractPaginatedStorage)
            ((DatabaseSessionInternal) database.getUnderlying()).getStorage();

    final WriteCache writeCache = storageLocalAbstract.getWriteCache();
    Assert.assertTrue(writeCache.exists("ValuesContainerIsRemovedIfIndexIsRemovedIndex.irs"));
    database.command("drop index ValuesContainerIsRemovedIfIndexIsRemovedIndex").close();
    Assert.assertFalse(writeCache.exists("ValuesContainerIsRemovedIfIndexIsRemovedIndex.irs"));
  }

  public void testPreservingIdentityInIndexTx() {
    checkEmbeddedDB();
    if (!database.getMetadata().getSchema().existsClass("PreservingIdentityInIndexTxParent")) {
      database.createVertexClass("PreservingIdentityInIndexTxParent");
    }
    if (!database.getMetadata().getSchema().existsClass("PreservingIdentityInIndexTxEdge")) {
      database.createEdgeClass("PreservingIdentityInIndexTxEdge");
    }
    var fieldClass = (SchemaClassInternal) database.getClass("PreservingIdentityInIndexTxChild");
    if (fieldClass == null) {
      fieldClass = (SchemaClassInternal) database.createVertexClass(
          "PreservingIdentityInIndexTxChild");
      fieldClass.createProperty(database, "name", PropertyType.STRING);
      fieldClass.createProperty(database, "in_field", PropertyType.LINK);
      fieldClass.createIndex(database, "nameParentIndex", INDEX_TYPE.NOTUNIQUE, "in_field", "name");
    }

    database.begin();
    Vertex parent = database.newVertex("PreservingIdentityInIndexTxParent");
    database.save(parent);
    Vertex child = database.newVertex("PreservingIdentityInIndexTxChild");
    database.save(child);
    database.save(database.newRegularEdge(parent, child, "PreservingIdentityInIndexTxEdge"));
    child.setProperty("name", "pokus");
    database.save(child);

    Vertex parent2 = database.newVertex("PreservingIdentityInIndexTxParent");
    database.save(parent2);
    Vertex child2 = database.newVertex("PreservingIdentityInIndexTxChild");
    database.save(child2);
    database.save(database.newRegularEdge(parent2, child2, "preservingIdentityInIndexTxEdge"));
    child2.setProperty("name", "pokus2");
    database.save(child2);
    database.commit();

    {
      fieldClass = database.getClassInternal("PreservingIdentityInIndexTxChild");
      Index index = fieldClass.getClassIndex(database, "nameParentIndex");
      CompositeKey key = new CompositeKey(parent.getIdentity(), "pokus");

      Collection<RID> h;
      try (Stream<RID> stream = index.getInternal().getRids(database, key)) {
        h = stream.toList();
      }
      for (RID o : h) {
        Assert.assertNotNull(database.load(o));
      }
    }

    {
      fieldClass = (SchemaClassInternal) database.getClass("PreservingIdentityInIndexTxChild");
      Index index = fieldClass.getClassIndex(database, "nameParentIndex");
      CompositeKey key = new CompositeKey(parent2.getIdentity(), "pokus2");

      Collection<RID> h;
      try (Stream<RID> stream = index.getInternal().getRids(database, key)) {
        h = stream.toList();
      }
      for (RID o : h) {
        Assert.assertNotNull(database.load(o));
      }
    }

    database.begin();
    database.delete(database.bindToSession(parent));
    database.delete(database.bindToSession(child));

    database.delete(database.bindToSession(parent2));
    database.delete(database.bindToSession(child2));
    database.commit();
  }

  public void testEmptyNotUniqueIndex() {
    checkEmbeddedDB();

    SchemaClass emptyNotUniqueIndexClazz =
        database
            .getMetadata()
            .getSchema()
            .createClass("EmptyNotUniqueIndexTest", 1, (SchemaClass[]) null);
    emptyNotUniqueIndexClazz.createProperty(database, "prop", PropertyType.STRING);

    emptyNotUniqueIndexClazz.createIndex(database,
        "EmptyNotUniqueIndexTestIndex", INDEX_TYPE.NOTUNIQUE_HASH_INDEX, "prop");
    final Index notUniqueIndex = database.getIndex("EmptyNotUniqueIndexTestIndex");

    database.begin();
    EntityImpl document = new EntityImpl("EmptyNotUniqueIndexTest");
    document.field("prop", "keyOne");
    document.save();

    document = new EntityImpl("EmptyNotUniqueIndexTest");
    document.field("prop", "keyTwo");
    document.save();
    database.commit();

    try (Stream<RID> stream = notUniqueIndex.getInternal().getRids(database, "RandomKeyOne")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (Stream<RID> stream = notUniqueIndex.getInternal().getRids(database, "keyOne")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    try (Stream<RID> stream = notUniqueIndex.getInternal().getRids(database, "RandomKeyTwo")) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (Stream<RID> stream = notUniqueIndex.getInternal().getRids(database, "keyTwo")) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  public void testNullIteration() {
    SchemaClass v = database.getSchema().getClass("V");
    SchemaClass testNullIteration =
        database.getMetadata().getSchema().createClass("NullIterationTest", v);
    testNullIteration.createProperty(database, "name", PropertyType.STRING);
    testNullIteration.createProperty(database, "birth", PropertyType.DATETIME);

    database.begin();
    database
        .command("CREATE VERTEX NullIterationTest SET name = 'Andrew', birth = sysdate()")
        .close();
    database
        .command("CREATE VERTEX NullIterationTest SET name = 'Marcel', birth = sysdate()")
        .close();
    database.command("CREATE VERTEX NullIterationTest SET name = 'Olivier'").close();
    database.commit();

    var metadata = Map.of("ignoreNullValues", false);

    testNullIteration.createIndex(database,
        "NullIterationTestIndex",
        INDEX_TYPE.NOTUNIQUE.name(),
        null,
        metadata, new String[]{"birth"});

    ResultSet result = database.query("SELECT FROM NullIterationTest ORDER BY birth ASC");
    Assert.assertEquals(result.stream().count(), 3);

    result = database.query("SELECT FROM NullIterationTest ORDER BY birth DESC");
    Assert.assertEquals(result.stream().count(), 3);

    result = database.query("SELECT FROM NullIterationTest");
    Assert.assertEquals(result.stream().count(), 3);
  }

  public void testMultikeyWithoutFieldAndNullSupport() {
    checkEmbeddedDB();

    // generates stubs for index
    database.begin();
    EntityImpl doc1 = new EntityImpl();
    doc1.save(database.getClusterNameById(database.getDefaultClusterId()));
    EntityImpl doc2 = new EntityImpl();
    doc2.save(database.getClusterNameById(database.getDefaultClusterId()));
    EntityImpl doc3 = new EntityImpl();
    doc3.save(database.getClusterNameById(database.getDefaultClusterId()));
    EntityImpl doc4 = new EntityImpl();
    doc4.save(database.getClusterNameById(database.getDefaultClusterId()));
    database.commit();

    final RID rid1 = doc1.getIdentity();
    final RID rid2 = doc2.getIdentity();
    final RID rid3 = doc3.getIdentity();
    final RID rid4 = doc4.getIdentity();
    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("TestMultikeyWithoutField");

    clazz.createProperty(database, "state", PropertyType.BYTE);
    clazz.createProperty(database, "users", PropertyType.LINKSET);
    clazz.createProperty(database, "time", PropertyType.LONG);
    clazz.createProperty(database, "reg", PropertyType.LONG);
    clazz.createProperty(database, "no", PropertyType.INTEGER);

    var mt = Map.of("ignoreNullValues", false);
    clazz.createIndex(database,
        "MultikeyWithoutFieldIndex",
        INDEX_TYPE.UNIQUE.toString(),
        null,
        mt, new String[]{"state", "users", "time", "reg", "no"});

    database.begin();
    EntityImpl document = new EntityImpl("TestMultikeyWithoutField");
    document.field("state", (byte) 1);

    Set<RID> users = new HashSet<>();
    users.add(rid1);
    users.add(rid2);

    document.field("users", users);
    document.field("time", 12L);
    document.field("reg", 14L);
    document.field("no", 12);

    document.save();
    database.commit();

    Index index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.getInternal().size(database), 2);

    // we support first and last keys check only for embedded storage
    // we support first and last keys check only for embedded storage
    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        if (rid1.compareTo(rid2) < 0) {
          Assert.assertEquals(
              keyStream.iterator().next(), new CompositeKey((byte) 1, rid1, 12L, 14L, 12));
        } else {
          Assert.assertEquals(
              keyStream.iterator().next(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
        }
      }
      try (Stream<RawPair<Object, RID>> descStream = index.getInternal().descStream(database)) {
        if (rid1.compareTo(rid2) < 0) {
          Assert.assertEquals(
              descStream.iterator().next().first, new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
        } else {
          Assert.assertEquals(
              descStream.iterator().next().first, new CompositeKey((byte) 1, rid1, 12L, 14L, 12));
        }
      }
    }

    final RID rid = document.getIdentity();

    database.close();
    database = acquireSession();

    database.begin();
    document = database.load(rid);

    users = document.field("users");
    users.remove(rid1);
    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.getInternal().size(database), 1);
    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        Assert.assertEquals(
            keyStream.iterator().next(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
      }
    }

    database.close();
    database = acquireSession();

    document = database.load(rid);

    database.begin();
    document = database.bindToSession(document);
    users = document.field("users");
    users.remove(rid2);
    Assert.assertTrue(users.isEmpty());
    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndex");

    Assert.assertEquals(index.getInternal().size(database), 1);
    if (!(database.isRemote())) {
      try (Stream<Object> keyStreamAsc = index.getInternal().keyStream()) {
        Assert.assertEquals(
            keyStreamAsc.iterator().next(), new CompositeKey((byte) 1, null, 12L, 14L, 12));
      }
    }

    database.close();
    database = acquireSession();

    database.begin();
    document = database.load(rid);
    users = document.field("users");
    users.add(rid3);
    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndex");

    Assert.assertEquals(index.getInternal().size(database), 1);
    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        Assert.assertEquals(
            keyStream.iterator().next(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
      }
    }

    database.close();
    database = acquireSession();

    database.begin();
    document = database.bindToSession(document);
    users = document.field("users");
    users.add(rid4);
    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.getInternal().size(database), 2);

    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        if (rid3.compareTo(rid4) < 0) {
          Assert.assertEquals(
              keyStream.iterator().next(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
        } else {
          Assert.assertEquals(
              keyStream.iterator().next(), new CompositeKey((byte) 1, rid4, 12L, 14L, 12));
        }
      }
      try (Stream<RawPair<Object, RID>> descStream = index.getInternal().descStream(database)) {
        if (rid3.compareTo(rid4) < 0) {
          Assert.assertEquals(
              descStream.iterator().next().first, new CompositeKey((byte) 1, rid4, 12L, 14L, 12));
        } else {
          Assert.assertEquals(
              descStream.iterator().next().first, new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
        }
      }
    }

    database.close();
    database = acquireSession();

    database.begin();
    document = database.bindToSession(document);
    document.removeField("users");
    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.getInternal().size(database), 1);

    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        Assert.assertEquals(
            keyStream.iterator().next(), new CompositeKey((byte) 1, null, 12L, 14L, 12));
      }
    }
  }

  public void testMultikeyWithoutFieldAndNoNullSupport() {
    checkEmbeddedDB();

    // generates stubs for index
    database.begin();
    EntityImpl doc1 = new EntityImpl();
    doc1.save(database.getClusterNameById(database.getDefaultClusterId()));
    EntityImpl doc2 = new EntityImpl();
    doc2.save(database.getClusterNameById(database.getDefaultClusterId()));
    EntityImpl doc3 = new EntityImpl();
    doc3.save(database.getClusterNameById(database.getDefaultClusterId()));
    EntityImpl doc4 = new EntityImpl();
    doc4.save(database.getClusterNameById(database.getDefaultClusterId()));
    database.commit();

    final RID rid1 = doc1.getIdentity();
    final RID rid2 = doc2.getIdentity();
    final RID rid3 = doc3.getIdentity();
    final RID rid4 = doc4.getIdentity();

    final Schema schema = database.getMetadata().getSchema();
    SchemaClass clazz = schema.createClass("TestMultikeyWithoutFieldNoNullSupport");

    clazz.createProperty(database, "state", PropertyType.BYTE);
    clazz.createProperty(database, "users", PropertyType.LINKSET);
    clazz.createProperty(database, "time", PropertyType.LONG);
    clazz.createProperty(database, "reg", PropertyType.LONG);
    clazz.createProperty(database, "no", PropertyType.INTEGER);

    clazz.createIndex(database,
        "MultikeyWithoutFieldIndexNoNullSupport",
        INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"state", "users", "time", "reg", "no"});

    EntityImpl document = new EntityImpl("TestMultikeyWithoutFieldNoNullSupport");
    document.field("state", (byte) 1);

    Set<RID> users = new HashSet<>();
    users.add(rid1);
    users.add(rid2);

    document.field("users", users);
    document.field("time", 12L);
    document.field("reg", 14L);
    document.field("no", 12);

    database.begin();
    document.save();
    database.commit();

    Index index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getInternal().size(database), 2);

    // we support first and last keys check only for embedded storage
    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        if (rid1.compareTo(rid2) < 0) {
          Assert.assertEquals(
              keyStream.iterator().next(), new CompositeKey((byte) 1, rid1, 12L, 14L, 12));
        } else {
          Assert.assertEquals(
              keyStream.iterator().next(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
        }
      }
      try (Stream<RawPair<Object, RID>> descStream = index.getInternal().descStream(database)) {
        if (rid1.compareTo(rid2) < 0) {
          Assert.assertEquals(
              descStream.iterator().next().first, new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
        } else {
          Assert.assertEquals(
              descStream.iterator().next().first, new CompositeKey((byte) 1, rid1, 12L, 14L, 12));
        }
      }
    }

    final RID rid = document.getIdentity();

    database.close();
    database = acquireSession();

    database.begin();
    document = database.load(rid);
    users = document.field("users");
    users.remove(rid1);

    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getInternal().size(database), 1);
    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        Assert.assertEquals(
            keyStream.iterator().next(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
      }
    }

    database.close();
    database = acquireSession();

    database.begin();
    document = database.load(rid);

    users = document.field("users");
    users.remove(rid2);
    Assert.assertTrue(users.isEmpty());

    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getInternal().size(database), 0);

    database.close();
    database = acquireSession();
    database.begin();

    document = database.load(rid);
    users = document.field("users");
    users.add(rid3);

    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getInternal().size(database), 1);

    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        Assert.assertEquals(
            keyStream.iterator().next(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
      }
    }

    database.close();
    database = acquireSession();

    database.begin();

    document = database.bindToSession(document);
    users = document.field("users");
    users.add(rid4);

    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getInternal().size(database), 2);

    if (!(database.isRemote())) {
      try (Stream<Object> keyStream = index.getInternal().keyStream()) {
        if (rid3.compareTo(rid4) < 0) {
          Assert.assertEquals(
              keyStream.iterator().next(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
        } else {
          Assert.assertEquals(
              keyStream.iterator().next(), new CompositeKey((byte) 1, rid4, 12L, 14L, 12));
        }
      }
      try (Stream<RawPair<Object, RID>> descStream = index.getInternal().descStream(database)) {
        if (rid3.compareTo(rid4) < 0) {
          Assert.assertEquals(
              descStream.iterator().next().first, new CompositeKey((byte) 1, rid4, 12L, 14L, 12));
        } else {
          Assert.assertEquals(
              descStream.iterator().next().first, new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
        }
      }
    }

    database.close();
    database = acquireSession();

    database.begin();
    document = database.bindToSession(document);
    document.removeField("users");

    document.save();
    database.commit();

    index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getInternal().size(database), 0);
  }

  public void testNullValuesCountSBTreeUnique() {
    checkEmbeddedDB();

    SchemaClass nullSBTreeClass = database.getSchema().createClass("NullValuesCountSBTreeUnique");
    nullSBTreeClass.createProperty(database, "field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(database, "NullValuesCountSBTreeUniqueIndex", INDEX_TYPE.UNIQUE,
        "field");

    database.begin();
    EntityImpl docOne = new EntityImpl("NullValuesCountSBTreeUnique");
    docOne.field("field", 1);
    docOne.save();

    EntityImpl docTwo = new EntityImpl("NullValuesCountSBTreeUnique");
    docTwo.field("field", (Integer) null);
    docTwo.save();
    database.commit();

    Index index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "NullValuesCountSBTreeUniqueIndex");
    Assert.assertEquals(index.getInternal().size(database), 2);
    try (Stream<RawPair<Object, RID>> stream = index.getInternal().stream(database)) {
      try (Stream<RID> nullStream = index.getInternal().getRids(database, null)) {
        Assert.assertEquals(
            stream.map((pair) -> pair.first).distinct().count() + nullStream.count(), 2);
      }
    }
  }

  public void testNullValuesCountSBTreeNotUniqueOne() {
    checkEmbeddedDB();

    SchemaClass nullSBTreeClass =
        database.getMetadata().getSchema().createClass("NullValuesCountSBTreeNotUniqueOne");
    nullSBTreeClass.createProperty(database, "field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(database,
        "NullValuesCountSBTreeNotUniqueOneIndex", INDEX_TYPE.NOTUNIQUE, "field");

    database.begin();
    EntityImpl docOne = new EntityImpl("NullValuesCountSBTreeNotUniqueOne");
    docOne.field("field", 1);
    docOne.save();

    EntityImpl docTwo = new EntityImpl("NullValuesCountSBTreeNotUniqueOne");
    docTwo.field("field", (Integer) null);
    docTwo.save();
    database.commit();

    Index index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "NullValuesCountSBTreeNotUniqueOneIndex");
    Assert.assertEquals(index.getInternal().size(database), 2);
    try (Stream<RawPair<Object, RID>> stream = index.getInternal().stream(database)) {
      try (Stream<RID> nullStream = index.getInternal().getRids(database, null)) {
        Assert.assertEquals(
            stream.map((pair) -> pair.first).distinct().count() + nullStream.count(), 2);
      }
    }
  }

  public void testNullValuesCountSBTreeNotUniqueTwo() {
    checkEmbeddedDB();

    SchemaClass nullSBTreeClass =
        database.getMetadata().getSchema().createClass("NullValuesCountSBTreeNotUniqueTwo");
    nullSBTreeClass.createProperty(database, "field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(database,
        "NullValuesCountSBTreeNotUniqueTwoIndex", INDEX_TYPE.NOTUNIQUE, "field");

    database.begin();
    EntityImpl docOne = new EntityImpl("NullValuesCountSBTreeNotUniqueTwo");
    docOne.field("field", (Integer) null);
    docOne.save();

    EntityImpl docTwo = new EntityImpl("NullValuesCountSBTreeNotUniqueTwo");
    docTwo.field("field", (Integer) null);
    docTwo.save();
    database.commit();

    Index index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "NullValuesCountSBTreeNotUniqueTwoIndex");
    try (Stream<RawPair<Object, RID>> stream = index.getInternal().stream(database)) {
      try (Stream<RID> nullStream = index.getInternal().getRids(database, null)) {
        Assert.assertEquals(
            stream.map((pair) -> pair.first).distinct().count()
                + nullStream.findAny().map(v -> 1).orElse(0),
            1);
      }
    }
    Assert.assertEquals(index.getInternal().size(database), 2);
  }

  public void testNullValuesCountHashUnique() {
    checkEmbeddedDB();
    SchemaClass nullSBTreeClass = database.getSchema().createClass("NullValuesCountHashUnique");
    nullSBTreeClass.createProperty(database, "field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(database,
        "NullValuesCountHashUniqueIndex", INDEX_TYPE.UNIQUE_HASH_INDEX, "field");

    database.begin();
    EntityImpl docOne = new EntityImpl("NullValuesCountHashUnique");
    docOne.field("field", 1);
    docOne.save();

    EntityImpl docTwo = new EntityImpl("NullValuesCountHashUnique");
    docTwo.field("field", (Integer) null);
    docTwo.save();
    database.commit();

    Index index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "NullValuesCountHashUniqueIndex");
    Assert.assertEquals(index.getInternal().size(database), 2);
    try (Stream<RawPair<Object, RID>> stream = index.getInternal().stream(database)) {
      try (Stream<RID> nullStream = index.getInternal().getRids(database, null)) {
        Assert.assertEquals(
            stream.map((pair) -> pair.first).distinct().count() + nullStream.count(), 2);
      }
    }
  }

  public void testNullValuesCountHashNotUniqueOne() {
    checkEmbeddedDB();

    SchemaClass nullSBTreeClass = database.getSchema()
        .createClass("NullValuesCountHashNotUniqueOne");
    nullSBTreeClass.createProperty(database, "field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(database,
        "NullValuesCountHashNotUniqueOneIndex", INDEX_TYPE.NOTUNIQUE_HASH_INDEX, "field");

    database.begin();
    EntityImpl docOne = new EntityImpl("NullValuesCountHashNotUniqueOne");
    docOne.field("field", 1);
    docOne.save();

    EntityImpl docTwo = new EntityImpl("NullValuesCountHashNotUniqueOne");
    docTwo.field("field", (Integer) null);
    docTwo.save();
    database.commit();

    Index index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "NullValuesCountHashNotUniqueOneIndex");
    Assert.assertEquals(index.getInternal().size(database), 2);
    try (Stream<RawPair<Object, RID>> stream = index.getInternal().stream(database)) {
      try (Stream<RID> nullStream = index.getInternal().getRids(database, null)) {
        Assert.assertEquals(
            stream.map((pair) -> pair.first).distinct().count() + nullStream.count(), 2);
      }
    }
  }

  public void testNullValuesCountHashNotUniqueTwo() {
    checkEmbeddedDB();

    SchemaClass nullSBTreeClass =
        database.getMetadata().getSchema().createClass("NullValuesCountHashNotUniqueTwo");
    nullSBTreeClass.createProperty(database, "field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(database,
        "NullValuesCountHashNotUniqueTwoIndex", INDEX_TYPE.NOTUNIQUE_HASH_INDEX, "field");

    database.begin();
    EntityImpl docOne = new EntityImpl("NullValuesCountHashNotUniqueTwo");
    docOne.field("field", (Integer) null);
    docOne.save();

    EntityImpl docTwo = new EntityImpl("NullValuesCountHashNotUniqueTwo");
    docTwo.field("field", (Integer) null);
    docTwo.save();
    database.commit();

    Index index =
        database
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(database, "NullValuesCountHashNotUniqueTwoIndex");
    try (Stream<RawPair<Object, RID>> stream = index.getInternal().stream(database)) {
      try (Stream<RID> nullStream = index.getInternal().getRids(database, null)) {
        Assert.assertEquals(
            stream.map(pair -> pair.first).distinct().count()
                + nullStream.findAny().map(v -> 1).orElse(0),
            1);
      }
    }
    Assert.assertEquals(index.getInternal().size(database), 2);
  }

  @Test
  public void testParamsOrder() {
    database.command("CREATE CLASS Task extends V").close();
    database
        .command("CREATE PROPERTY Task.projectId STRING (MANDATORY TRUE, NOTNULL, MAX 20)")
        .close();
    database.command("CREATE PROPERTY Task.seq SHORT ( MANDATORY TRUE, NOTNULL, MIN 0)").close();
    database.command("CREATE INDEX TaskPK ON Task (projectId, seq) UNIQUE").close();

    database.begin();
    database.command("INSERT INTO Task (projectId, seq) values ( 'foo', 2)").close();
    database.command("INSERT INTO Task (projectId, seq) values ( 'bar', 3)").close();
    database.commit();

    var results =
        database
            .query("select from Task where projectId = 'foo' and seq = 2")
            .vertexStream()
            .toList();
    Assert.assertEquals(results.size(), 1);
  }

  private void assertIndexUsage(ResultSet resultSet) {
    var executionPlan = resultSet.getExecutionPlan().orElseThrow();
    for (var step : executionPlan.getSteps()) {
      if (assertIndexUsage(step, "Profile.nick")) {
        return;
      }
    }

    Assert.fail("Index " + "Profile.nick" + " was not used in the query");
  }

  private boolean assertIndexUsage(ExecutionStep executionStep, String indexName) {
    if (executionStep instanceof FetchFromIndexStep fetchFromIndexStep
        && fetchFromIndexStep.getIndexName().equals(indexName)) {
      return true;
    }
    for (var subStep : executionStep.getSubSteps()) {
      if (assertIndexUsage(subStep, indexName)) {
        return true;
      }
    }

    return false;
  }
}
