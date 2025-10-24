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

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaPropertyInToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@SuppressWarnings({"deprecation"})
@Test
public class IndexTest extends BaseDBTest {

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateCompanyData();
  }

  public void testDuplicatedIndexOnUnique() {
    session.begin();
    var jayMiner = session.newEntity("Profile");
    jayMiner.setProperty("nick", "Jay");
    jayMiner.setProperty("name", "Jay");
    jayMiner.setProperty("surname", "Miner");

    session.commit();

    session.begin();
    var jacobMiner = session.newEntity("Profile");
    jacobMiner.setProperty("nick", "Jay");
    jacobMiner.setProperty("name", "Jacob");
    jacobMiner.setProperty("surname", "Miner");

    try {
      session.commit();

      // IT SHOULD GIVE ERROR ON DUPLICATED KEY
      Assert.fail();

    } catch (RecordDuplicatedException e) {
      Assert.assertTrue(true);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInUniqueIndex() {
    session.begin();
    Assert.assertEquals(
        session.getMetadata().getFastImmutableSchemaSnapshot().getClass("Profile")
            .getInvolvedIndexes("nick").iterator().next().getType(),
        ImmutableSchema.IndexType.UNIQUE);
    try (var resultSet =
        session.query(
            "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0'"
                + " ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")) {
      assertIndexUsage(resultSet);

      final List<String> expectedSurnames =
          new ArrayList<>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (final var profile : result) {
        expectedSurnames.remove(profile.<String>getProperty("surname"));
      }

      Assert.assertEquals(expectedSurnames.size(), 0);
    }
    session.commit();
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testUseOfIndex() {
    session.begin();
    var resultSet = executeQuery("select * from Profile where nick = 'Jay'");

    Assert.assertFalse(resultSet.isEmpty());

    Entity record;
    for (var entries : resultSet) {
      record = entries.asEntityOrNull();
      Assert.assertTrue(record.<String>getProperty("name").equalsIgnoreCase("Jay"));
    }
    session.commit();
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testIndexAscEntries() {

    var resultSet = executeQuery("select * from Profile where nick is not null");

    var idx =
        session.getMetadata().getFastImmutableSchemaSnapshot().getIndex("Profile.nick");

    Assert.assertEquals(idx.size(session), resultSet.size());
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testIndexSize() {

    session.begin();
    var resultSet = executeQuery("select * from Profile where nick is not null");
    var profileSize = resultSet.size();
    session.commit();

    Assert.assertEquals(
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("Profile.nick")

            .size(session),
        profileSize);
    for (var i = 0; i < 10; i++) {
      session.begin();
      var profile = session.newEntity("Profile");
      profile.setProperty("nick", "Yay-" + i);
      profile.setProperty("name", "Jay");
      profile.setProperty("surname", "Miner");
      session.commit();

      profileSize++;
      try (var iterator =
          session
              .getMetadata()
              .getFastImmutableSchemaSnapshot()
              .getIndex("Profile.nick")

              .getRids(session, "Yay-" + i)) {
        Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
      }
    }
  }

  @Test(dependsOnMethods = "testUseOfIndex")
  public void testChangeOfIndexToNotUnique() {
    dropIndexes();

    graph.autoExecuteInTx(g ->
        g.schemaClass("Profile").schemaClassProperty("nick")
            .createPropertyIndex(IndexType.NOT_UNIQUE)
    );
  }

  private void dropIndexes() {
    graph.autoExecuteInTx(g ->
        g.schemaClass("Profile").schemaClassProperty("nick").
            in(YTDBSchemaPropertyInToken.propertyToIndex).drop()
    );
  }

  @Test(dependsOnMethods = "testChangeOfIndexToNotUnique")
  public void testDuplicatedIndexOnNotUnique() {
    session.begin();
    var nickNolte = session.newEntity("Profile");
    nickNolte.setProperty("nick", "Jay");
    nickNolte.setProperty("name", "Nick");
    nickNolte.setProperty("surname", "Nolte");

    session.commit();
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnNotUnique")
  public void testChangeOfIndexToUnique() {
    try {
      dropIndexes();

      graph.autoExecuteInTx(g -> g.schemaClass("Profile").
          schemaClassProperty("nick")
          .createPropertyIndex(IndexType.UNIQUE)
      );

      Assert.fail();
    } catch (RecordDuplicatedException e) {
      Assert.assertTrue(true);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMajorSelect() {
    session.begin();
    try (var resultSet =
        session.query("select * from Profile where nick > 'ZZZJayLongNickIndex3'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks =
          new ArrayList<>(Arrays.asList("ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 2);
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
    session.commit();
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMajorEqualsSelect() {
    session.begin();
    try (var resultSet =
        session.query("select * from Profile where nick >= 'ZZZJayLongNickIndex3'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks =
          new ArrayList<>(
              Arrays.asList(
                  "ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
    session.commit();
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMinorSelect() {
    session.begin();
    try (var resultSet = session.query("select * from Profile where nick < '002'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("000", "001"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 2);
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
    session.commit();
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMinorEqualsSelect() {
    session.begin();
    try (var resultSet = session.query("select * from Profile where nick <= '002'")) {
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("000", "001", "002"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
    session.commit();
  }

  @Test(dependsOnMethods = "populateIndexDocuments", enabled = false)
  public void testIndexBetweenSelect() {
    var query = "select * from Profile where nick between '001' and '004'";
    try (var resultSet = session.query(query)) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("001", "002", "003", "004"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 4);
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments", enabled = false)
  public void testIndexInComplexSelectOne() {
    try (var resultSet =
        session.query(
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
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments", enabled = false)
  public void testIndexInComplexSelectTwo() {
    try (var resultSet =
        session.query(
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
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(expectedNicks.size(), 0);
    }
  }

  public void populateIndexDocuments() {
    for (var i = 0; i <= 5; i++) {
      session.begin();
      final var profile = session.newEntity("Profile");
      profile.setProperty("nick", "ZZZJayLongNickIndex" + i);
      profile.setProperty("name", "NickIndex" + i);
      profile.setProperty("surname", "NolteIndex" + i);
      session.commit();
    }

    for (var i = 0; i <= 5; i++) {
      session.begin();
      final var profile = session.newEntity("Profile");
      profile.setProperty("nick", "00" + i);
      profile.setProperty("name", "NickIndex" + i);
      profile.setProperty("surname", "NolteIndex" + i);
      session.commit();
    }
  }

  @Test(dependsOnMethods = "testChangeOfIndexToUnique")
  public void removeNotUniqueIndexOnNick() {
    dropIndexes();
  }

  @Test(dependsOnMethods = "removeNotUniqueIndexOnNick")
  public void testQueryingWithoutNickIndex() {
    Assert.assertFalse(
        session.getMetadata().getSlowMutableSchema().getClass("Profile")
            .getInvolvedIndexesNames("name").isEmpty());

    Assert.assertTrue(
        session.getMetadata().getSlowMutableSchema().getClass("Profile")
            .getInvolvedIndexesNames("nick")
            .isEmpty());

    var result =
        session
            .query("SELECT FROM Profile WHERE nick = 'Jay'").toList();
    Assert.assertEquals(result.size(), 2);

    result =
        session
            .query(
                "SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Jay'").toList();
    Assert.assertEquals(result.size(), 1);

    result =
        session
            .query(
                "SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Nick'").toList();
    Assert.assertEquals(result.size(), 1);
  }

  @Test(dependsOnMethods = "testQueryingWithoutNickIndex")
  public void createNotUniqueIndexOnNick() {
    graph.autoExecuteInTx(g ->
        g.schemaClass("Profile").schemaClassProperty("nick")
            .createPropertyIndex(IndexType.NOT_UNIQUE)
    );
  }

  @Test(dependsOnMethods = {"createNotUniqueIndexOnNick", "populateIndexDocuments"})
  public void testIndexInNotUniqueIndex() {
    Assert.assertEquals(
        session.getMetadata().getFastImmutableSchemaSnapshot().getClass("Profile").
            getInvolvedIndexes("nick").iterator()
            .next().getType(),
        ImmutableSchema.IndexType.NOT_UNIQUE);

    session.begin();
    try (var resultSet =
        session.query(
            "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0'"
                + " ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")) {
      final List<String> expectedSurnames =
          new ArrayList<>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(result.size(), 3);
      for (final var profile : result) {
        expectedSurnames.remove(profile.<String>getProperty("surname"));
      }

      Assert.assertEquals(expectedSurnames.size(), 0);
    }
    session.commit();
  }

  @Test
  public void indexLinks() {

    graph.autoExecuteInTx(g ->
        g.schemaClass("Whiz").schemaClassProperty("account")
            .createPropertyIndex(IndexType.NOT_UNIQUE)
    );

    session.begin();
    var resultSet = executeQuery("select * from Account limit 1");
    final var idx =
        session.getMetadata().getFastImmutableSchemaSnapshot().getIndex("Whiz.account");

    for (var i = 0; i < 5; i++) {
      final var whiz = ((EntityImpl) session.newEntity("Whiz"));

      whiz.setProperty("id", i);
      whiz.setProperty("text", "This is a test");
      whiz.setPropertyInChain("account", resultSet.getFirst().asEntityOrNull().getIdentity());

    }
    session.commit();

    Assert.assertEquals(idx.size(session), 5);

    session.begin();
    var indexedResult =
        executeQuery("select * from Whiz where account = ?",
            resultSet.getFirst().getIdentity());
    Assert.assertEquals(indexedResult.size(), 5);

    for (var res : indexedResult) {
      res.asEntityOrNull().delete();
    }

    var whiz = session.newEntity("Whiz");
    whiz.setProperty("id", 100);
    whiz.setProperty("text", "This is a test!");
    whiz.setProperty("account",
        ((EntityImpl) session.newEntity("Company")).setPropertyInChain("id", 9999));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    whiz = activeTx.load(whiz);
    Assert.assertTrue(((EntityImpl) whiz.getProperty("account")).getIdentity().isValidPosition());
    ((EntityImpl) whiz.getProperty("account")).delete();
    whiz.delete();
    session.commit();
  }

  public void linkedIndexedProperty() {
    try (var db = acquireSession()) {
      graph.autoExecuteInTx(g -> g.schemaClass("TestClass").fold().coalesce(
          __.unfold(),
          __.createSchemaClass("TestLinkClass").createSchemaClass("TestClass",
              __.createSchemaProperty("testLink", PropertyType.LINK, "TestLinkClass")
                  .createPropertyIndex(IndexType.NOT_UNIQUE),
              __.createSchemaProperty("name", PropertyType.STRING)
                  .createPropertyIndex(IndexType.UNIQUE),
              __.createSchemaProperty("testBoolean", PropertyType.BOOLEAN),
              __.createSchemaProperty("testString", PropertyType.STRING)
          )
      ));

      db.begin();
      var testClassDocument = db.newInstance("TestClass");
      testClassDocument.setProperty("name", "Test Class 1");
      var testLinkClassDocument = ((EntityImpl) db.newEntity("TestLinkClass"));
      testLinkClassDocument.setProperty("testString", "Test Link Class 1");
      testLinkClassDocument.setProperty("testBoolean", true);
      testClassDocument.setProperty("testLink", testLinkClassDocument);

      db.commit();
      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.Boolean
      var result =
          db.query("select from TestClass where testLink.testBoolean = true").toList();
      Assert.assertEquals(result.size(), 1);
      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.String
      result =
          db.query("select from TestClass where testLink.testString = 'Test Link Class 1'")
              .toList();
      Assert.assertEquals(result.size(), 1);
    }
  }

  @Test(dependsOnMethods = "linkedIndexedProperty")
  public void testLinkedIndexedPropertyInTx() {
    try (var db = acquireSession()) {
      db.begin();
      var testClassDocument = db.newInstance("TestClass");
      testClassDocument.setProperty("name", "Test Class 2");
      var testLinkClassDocument = ((EntityImpl) db.newEntity("TestLinkClass"));
      testLinkClassDocument.setProperty("testString", "Test Link Class 2");
      testLinkClassDocument.setProperty("testBoolean", true);
      testClassDocument.setProperty("testLink", testLinkClassDocument);

      db.commit();

      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.Boolean
      var result =
          db.query(
              "select from TestClass where testLink.testBoolean = true").toList();
      Assert.assertEquals(result.size(), 2);
      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.String
      result =
          db.query(
              "select from TestClass where testLink.testString = 'Test Link Class 2'").toList();
      Assert.assertEquals(result.size(), 1);
    }
  }

  public void testConcurrentRemoveDelete() {

    try (var db = acquireSession()) {
      graph.autoExecuteInTx(g -> g.schemaClass("MyFruit").fold().coalesce(
          __.unfold(),
          __.createSchemaClass("MyFruit",
              __.createSchemaProperty("name", PropertyType.STRING)
                  .createPropertyIndex(IndexType.UNIQUE),
              __.createSchemaProperty("color", PropertyType.STRING)
                  .createPropertyIndex(IndexType.NOT_UNIQUE)
          )
      ));

      long expectedIndexSize = 0;

      final var passCount = 10;
      final var chunkSize = 10;

      for (var pass = 0; pass < passCount; pass++) {
        List<EntityImpl> recordsToDelete = new ArrayList<>();
        db.begin();
        for (var i = 0; i < chunkSize; i++) {
          var d =
              ((EntityImpl) db.newEntity("MyFruit"))
                  .setPropertyInChain("name", "ABC" + pass + 'K' + i)
                  .setPropertyInChain("color", "FOO" + pass);

          if (i < chunkSize / 2) {
            recordsToDelete.add(d);
          }
        }
        db.commit();

        expectedIndexSize += chunkSize;
        Assert.assertEquals(
            db.getMetadata().getFastImmutableSchemaSnapshot()
                .getClass("MyFruit").getClassIndex("MyFruit.color")

                .size(db),
            expectedIndexSize,
            "After add");

        // do delete
        db.begin();
        for (final var recordToDelete : recordsToDelete) {
          var activeTx = db.getActiveTransaction();
          db.delete(activeTx.<EntityImpl>load(recordToDelete));
        }
        db.commit();

        expectedIndexSize -= recordsToDelete.size();
        Assert.assertEquals(
            db.getMetadata().getFastImmutableSchemaSnapshot()
                .getClass("MyFruit")
                .getClassIndex("MyFruit.color")

                .size(db),
            expectedIndexSize,
            "After delete");
      }
    }
  }

  public void testIndexParamsAutoConversion() {

    final EntityImpl doc;
    final RecordIdInternal result;
    try (var db = acquireSession()) {
      graph.autoExecuteInTx(g -> g.schemaClass("IndexTestTerm").fold().coalesce(
          __.unfold(),
          __.createSchemaClass("IndexTestTerm",
              __.createSchemaProperty("label", PropertyType.STRING)
                  .createPropertyIndex("idxTerm", IndexType.UNIQUE, true)
          )
      ));

      db.begin();
      doc = ((EntityImpl) db.newEntity("IndexTestTerm"));
      doc.setProperty("label", "42");

      db.commit();

      try (var iterator =
          db.getMetadata()
              .getFastImmutableSchemaSnapshot()
              .getIndex("idxTerm")
              .getRids(db, "42")) {
        result = (RecordIdInternal) YTDBIteratorUtils.findFirst(iterator).orElse(null);
      }
    }
    Assert.assertNotNull(result);
    Assert.assertEquals(result.getIdentity(), doc.getIdentity());
  }

  public void testTransactionUniqueIndexTestOne() {

    var db = acquireSession();
    graph.autoExecuteInTx(g -> g.schemaClass("TransactionUniqueIndexTest").fold().coalesce(
        __.unfold(),
        __.createSchemaClass("TransactionUniqueIndexTest",
            __.createSchemaProperty("label", PropertyType.STRING)
                .createPropertyIndex("idxTransactionUniqueIndexTest", IndexType.UNIQUE, true)
        )
    ));

    db.begin();
    var docOne = ((EntityImpl) db.newEntity("TransactionUniqueIndexTest"));
    docOne.setProperty("label", "A");

    db.commit();

    final var index =
        db.getMetadata().getFastImmutableSchemaSnapshot().getIndex("idxTransactionUniqueIndexTest");
    Assert.assertEquals(index.size(this.session), 1);

    db.begin();
    try {
      var docTwo = ((EntityImpl) db.newEntity("TransactionUniqueIndexTest"));
      docTwo.setProperty("label", "A");

      db.commit();
      Assert.fail();
    } catch (RecordDuplicatedException ignored) {
    }

    Assert.assertEquals(index.size(this.session), 1);
  }

  @Test(dependsOnMethods = "testTransactionUniqueIndexTestOne")
  public void testTransactionUniqueIndexTestTwo() {
    var session = acquireSession();
    final var index =
        session.getMetadata().getFastImmutableSchemaSnapshot()
            .getIndex("idxTransactionUniqueIndexTest");
    Assert.assertEquals(index.size(this.session), 1);

    session.begin();
    try {
      var docOne = ((EntityImpl) session.newEntity("TransactionUniqueIndexTest"));
      docOne.setProperty("label", "B");

      var docTwo = ((EntityImpl) session.newEntity("TransactionUniqueIndexTest"));
      docTwo.setProperty("label", "B");

      session.commit();
      Assert.fail();
    } catch (RecordDuplicatedException oie) {
      session.rollback();
    }

    Assert.assertEquals(index.size(this.session), 1);
  }

  public void testTransactionUniqueIndexTestWithDotNameOne() {
    var db = acquireSession();

    graph.autoExecuteInTx(g ->
        g.schemaClass("TransactionUniqueIndexWithDotTest").fold().coalesce(
            __.unfold(),
            __.createSchemaClass("TransactionUniqueIndexWithDotTest",
                __.createSchemaProperty("label", PropertyType.STRING)
                    .createPropertyIndex(IndexType.UNIQUE)
            )
        ));

    db.begin();
    var docOne = ((EntityImpl) db.newEntity("TransactionUniqueIndexWithDotTest"));
    docOne.setProperty("label", "A");

    db.commit();

    final var index =
        db.getMetadata().getFastImmutableSchemaSnapshot()
            .getIndex("TransactionUniqueIndexWithDotTest.label");
    Assert.assertEquals(index.size(this.session), 1);

    var countClassBefore = db.countClass("TransactionUniqueIndexWithDotTest");
    db.begin();
    try {
      var docTwo = ((EntityImpl) db.newEntity("TransactionUniqueIndexWithDotTest"));
      docTwo.setProperty("label", "A");

      db.commit();
      Assert.fail();
    } catch (RecordDuplicatedException ignored) {
    }

    Assert.assertEquals(
        db.query("select from TransactionUniqueIndexWithDotTest").toList()
            .size(),
        countClassBefore);

    Assert.assertEquals(index.size(db), 1);
  }

  @Test(dependsOnMethods = "testTransactionUniqueIndexTestWithDotNameOne")
  public void testTransactionUniqueIndexTestWithDotNameTwo() {
    var db = acquireSession();

    graph.autoExecuteInTx(g -> g.schemaClass("TransactionUniqueIndexWithDotTest").fold().coalesce(
        __.unfold(),
        __.createSchemaClass("TransactionUniqueIndexWithDotTest")
            .createSchemaProperty("label", PropertyType.STRING)
            .createPropertyIndex(IndexType.UNIQUE)
    ));

    final var index =
        db.getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("TransactionUniqueIndexWithDotTest.label");
    Assert.assertEquals(index.size(this.session), 1);

    db.begin();
    try {
      var docOne = ((EntityImpl) db.newEntity("TransactionUniqueIndexWithDotTest"));
      docOne.setProperty("label", "B");

      var docTwo = ((EntityImpl) db.newEntity("TransactionUniqueIndexWithDotTest"));
      docTwo.setProperty("label", "B");

      db.commit();
      Assert.fail();
    } catch (RecordDuplicatedException oie) {
      db.rollback();
    }

    Assert.assertEquals(index.size(this.session), 1);
  }

  @Test(dependsOnMethods = "linkedIndexedProperty")
  public void testIndexRemoval() {

    final var index = getIndex("Profile.nick");

    Object key;
    try (var iterator = index.ascEntries(session)) {
      Assert.assertTrue(iterator.hasNext());

      var pair = iterator.next();
      key = pair.first();

      session.begin();
      var transaction = session.getActiveTransaction();
      transaction.load(pair.second()).delete();
      session.commit();
    }

    try (var iterator = index.getRids(session, key)) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
  }

  public void createInheritanceIndex() {
    try (var db = acquireSession()) {
      graph.autoExecuteInTx(g -> g.schemaClass("BaseTestClass").fold().coalesce(
          __.unfold(),
          __.createSchemaClass("BaseTestClass")
              .createSchemaProperty("testParentProperty", PropertyType.LONG)
              .createPropertyIndex(IndexType.NOT_UNIQUE).
              createSchemaClass("ChildTestClass", "BaseTestClass").
              createSchemaClass("AnotherChildTestClass", "BaseTestClass")
      ));

      db.begin();
      var childClassDocument = db.newInstance("ChildTestClass");
      childClassDocument.setProperty("testParentProperty", 10L);

      var anotherChildClassDocument = db.newInstance("AnotherChildTestClass");
      anotherChildClassDocument.setProperty("testParentProperty", 11L);

      db.commit();

      Assert.assertNotEquals(
          childClassDocument.getIdentity(), new RecordId(-1, RID.COLLECTION_POS_INVALID));
      Assert.assertNotEquals(
          anotherChildClassDocument.getIdentity(), new RecordId(-1, RID.COLLECTION_POS_INVALID));
    }
  }

  @Test(dependsOnMethods = "createInheritanceIndex")
  public void testIndexReturnOnlySpecifiedClass() {

    session.begin();
    try (var result =
        session.execute("select * from ChildTestClass where testParentProperty = 10")) {

      Assert.assertEquals(result.next().<Object>getProperty("testParentProperty"), 10L);
      Assert.assertFalse(result.hasNext());
    }

    try (var result =
        session.execute("select * from AnotherChildTestClass where testParentProperty = 11")) {
      Assert.assertEquals(result.next().<Object>getProperty("testParentProperty"), 11L);
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  public void testNotUniqueIndexKeySize() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("IndexNotUniqueIndexKeySize").
        createSchemaProperty("value", PropertyType.INTEGER)
        .createPropertyIndex("IndexNotUniqueIndexKeySizeIndex", IndexType.NOT_UNIQUE));

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var idx = schema.getIndex("IndexNotUniqueIndexKeySizeIndex");

    final Set<Integer> keys = new HashSet<>();
    for (var i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      session.begin();
      final var doc = ((EntityImpl) session.newEntity("IndexNotUniqueIndexKeySize"));
      doc.setProperty("value", key);

      session.commit();

      keys.add(key);
    }

    try (var iterator = idx.ascEntries(session)) {
      Assert.assertEquals(
          YTDBIteratorUtils.set(YTDBIteratorUtils.map(iterator, RawPair::first)).size(),
          keys.size());
    }
  }

  public void testNotUniqueIndexSize() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("IndexNotUniqueIndexSize").
        createSchemaProperty("value", PropertyType.INTEGER)
        .createPropertyIndex("IndexNotUniqueIndexSizeIndex", IndexType.NOT_UNIQUE)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var idx = schema.getIndex("IndexNotUniqueIndexSizeIndex");

    for (var i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      session.begin();
      final var doc = ((EntityImpl) session.newEntity("IndexNotUniqueIndexSize"));
      doc.setProperty("value", key);

      session.commit();
    }

    Assert.assertEquals(idx.size(session), 99);
  }

  @Test
  public void testIndexRebuildDuringNonProxiedObjectDelete() {

    session.begin();
    var profile = session.newEntity("Profile");
    profile.setProperty("nick", "NonProxiedObjectToDelete");
    profile.setProperty("name", "NonProxiedObjectToDelete");
    profile.setProperty("surname", "NonProxiedObjectToDelete");
    session.commit();

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var nickIndex = schema.getIndex("Profile.nick");

    try (var iterator = nickIndex
        .getRids(session, "NonProxiedObjectToDelete")) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    session.begin();
    final Entity loadedProfile = session.load(profile.getIdentity());
    session.delete(loadedProfile);
    session.commit();

    try (var iterator = nickIndex
        .getRids(session, "NonProxiedObjectToDelete")) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
  }

  @Test(dependsOnMethods = "testIndexRebuildDuringNonProxiedObjectDelete")
  public void testIndexRebuildDuringDetachAllNonProxiedObjectDelete() {

    session.begin();
    var profile = session.newEntity("Profile");
    profile.setProperty("nick", "NonProxiedObjectToDelete");
    profile.setProperty("name", "NonProxiedObjectToDelete");
    profile.setProperty("surname", "NonProxiedObjectToDelete");
    session.commit();

    session.begin();
    var idxManager = session.getMetadata().getFastImmutableSchemaSnapshot();
    var nickIndex = idxManager.getIndex("Profile.nick");

    try (var iterator = nickIndex
        .getRids(session, "NonProxiedObjectToDelete")) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    final Entity loadedProfile = session.load(profile.getIdentity());
    session.commit();
    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(loadedProfile));
    session.commit();

    try (var iterator = nickIndex
        .getRids(session, "NonProxiedObjectToDelete")) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
  }

  @Test(dependsOnMethods = "testIndexRebuildDuringDetachAllNonProxiedObjectDelete")
  public void testRestoreUniqueIndex() {
    dropIndexes();
    session
        .execute(
            "CREATE INDEX Profile.nick on Profile (nick) UNIQUE METADATA {ignoreNullValues: true}")
        .close();
    session.getMetadata().reload();
  }

  @Test
  public void testIndexInCompositeQuery() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("CompoundSQLIndexTest1")
        .createSchemaClass("CompoundSQLIndexTest2",
            __.createSchemaProperty("address", PropertyType.LINK, "CompoundSQLIndexTest1")
                .createPropertyIndex("CompoundSQLIndexTestIndex", IndexType.UNIQUE)
        )
    );

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("CompoundSQLIndexTest1"));
    docOne.setProperty("city", "Montreal");

    var docTwo = ((EntityImpl) session.newEntity("CompoundSQLIndexTest2"));
    docTwo.setProperty("address", docOne);

    session.commit();

    var result =
        executeQuery(
            "select from CompoundSQLIndexTest2 where address in (select from"
                + " CompoundSQLIndexTest1 where city='Montreal')");
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.getFirst().getIdentity(), docTwo.getIdentity());
  }

  public void testIndexWithLimitAndOffset() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("IndexWithLimitAndOffsetClass",
            __.createSchemaProperty("val", PropertyType.INTEGER)
                .createPropertyIndex("IndexWithLimitAndOffset", IndexType.NOT_UNIQUE),
            __.createSchemaProperty("index", PropertyType.INTEGER)
        ));

    for (var i = 0; i < 30; i++) {
      session.begin();
      final var document = ((EntityImpl) session.newEntity("IndexWithLimitAndOffsetClass"));
      document.setProperty("val", i / 10);
      document.setProperty("index", i);

      session.commit();
    }

    var tx = session.begin();
    var resultSet =
        executeQuery("select from IndexWithLimitAndOffsetClass where val = 1 offset 5 limit 2");
    Assert.assertEquals(resultSet.size(), 2);

    for (var i = 0; i < 2; i++) {
      var result = resultSet.get(i);
      Assert.assertEquals(result.<Object>getProperty("val"), 1);
      Assert.assertEquals(result.<Object>getProperty("index"), 15 + i);
    }
    tx.commit();
  }

  public void testNullIndexKeysSupport() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("NullIndexKeysSupport")
            .createSchemaProperty("nullField", PropertyType.STRING)
            .createPropertyIndex("NullIndexKeysSupportIndex", IndexType.NOT_UNIQUE)
    );

    for (var i = 0; i < 20; i++) {
      session.begin();
      if (i % 5 == 0) {
        var document = ((EntityImpl) session.newEntity("NullIndexKeysSupport"));
        document.setProperty("nullField", null);

      } else {
        var document = ((EntityImpl) session.newEntity("NullIndexKeysSupport"));
        document.setProperty("nullField", "val" + i);

      }
      session.commit();
    }

    session.begin();
    var resultSet =
        executeQuery("select from NullIndexKeysSupport where nullField = 'val3'");
    Assert.assertEquals(resultSet.size(), 1);

    Assert.assertEquals(resultSet.getFirst().getProperty("nullField"), "val3");

    final var query = "select from NullIndexKeysSupport where nullField is null";
    resultSet = executeQuery("select from NullIndexKeysSupport where nullField is null");

    Assert.assertEquals(resultSet.size(), 4);
    for (var result : resultSet) {
      Assert.assertNull(result.getProperty("nullField"));
    }
    session.commit();
  }


  public void testNullIndexKeysSupportInTx() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("NullIndexKeysSupportInTx")
            .createSchemaProperty("nullField", PropertyType.STRING)
            .createPropertyIndex(IndexType.NOT_UNIQUE)
    );
    session.begin();

    for (var i = 0; i < 20; i++) {
      if (i % 5 == 0) {
        var document = ((EntityImpl) session.newEntity("NullIndexKeysSupportInTx"));
        document.setProperty("nullField", null);

      } else {
        var document = ((EntityImpl) session.newEntity("NullIndexKeysSupportInTx"));
        document.setProperty("nullField", "val" + i);

      }
    }

    session.commit();

    session.begin();
    var result = session.query(
        "select from NullIndexKeysSupportInTx where nullField = 'val3'").toList();
    Assert.assertEquals(result.size(), 1);

    var entity = result.getFirst();
    Assert.assertEquals(entity.getProperty("nullField"), "val3");

    result =
        session.query(
            "select from NullIndexKeysSupportInTx where nullField is null").toList();

    Assert.assertEquals(result.size(), 4);
    for (var document : result) {
      Assert.assertNull(document.getProperty("nullField"));
    }
    session.commit();
  }

  public void testNullIndexKeysSupportInMiddleTx() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("NullIndexKeysSupportInMiddleTx")
            .createSchemaProperty("nullField", PropertyType.STRING)
            .createPropertyIndex(IndexType.NOT_UNIQUE)
    );

    session.begin();

    for (var i = 0; i < 20; i++) {
      if (i % 5 == 0) {
        var document = ((EntityImpl) session.newEntity("NullIndexKeysSupportInMiddleTx"));
        document.setProperty("nullField", null);

      } else {
        var document = ((EntityImpl) session.newEntity("NullIndexKeysSupportInMiddleTx"));
        document.setProperty("nullField", "val" + i);

      }
    }

    var result =
        session.query(

            "select from NullIndexKeysSupportInMiddleTx where nullField = 'val3'").toList();
    Assert.assertEquals(result.size(), 1);

    var entity = result.getFirst();
    Assert.assertEquals(entity.getProperty("nullField"), "val3");

    result =
        session.query(
            "select from NullIndexKeysSupportInMiddleTx where nullField is null").toList();

    Assert.assertEquals(result.size(), 4);
    for (var document : result) {
      Assert.assertNull(document.getProperty("nullField"));
    }

    session.commit();
  }

  public void testCreateIndexAbstractClass() {
    graph.autoExecuteInTx(g ->
        g.createAbstractSchemaClass("TestCreateIndexAbstractClass").
            createSchemaProperty("value", PropertyType.STRING).mandatoryAttr(true)
            .createPropertyIndex(IndexType.UNIQUE).
            createSchemaClass("TestCreateIndexAbstractClassChildOne",
                "TestCreateIndexAbstractClass").
            createSchemaClass("TestCreateIndexAbstractClassChildTwo",
                "TestCreateIndexAbstractClass")
    );

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("TestCreateIndexAbstractClassChildOne"));
    docOne.setProperty("value", "val1");

    var docTwo = ((EntityImpl) session.newEntity("TestCreateIndexAbstractClassChildTwo"));
    docTwo.setProperty("value", "val2");

    session.commit();

    session.begin();
    final var queryOne = "select from TestCreateIndexAbstractClass where value = 'val1'";

    var resultOne = executeQuery(queryOne);
    Assert.assertEquals(resultOne.size(), 1);
    Assert.assertEquals(resultOne.getFirst().getIdentity(), docOne.getIdentity());

    try (var result = session.execute("explain " + queryOne)) {
      var explain = result.next();
      Assert.assertTrue(
          explain
              .<String>getProperty("executionPlanAsString")
              .contains("FETCH FROM INDEX TestCreateIndexAbstractClass.value"));

      final var queryTwo = "select from TestCreateIndexAbstractClass where value = 'val2'";

      var resultTwo = executeQuery(queryTwo);
      Assert.assertEquals(resultTwo.size(), 1);
      Assert.assertEquals(resultTwo.getFirst().getIdentity(), docTwo.getIdentity());
    }
    session.commit();
  }

  @Test(enabled = false)
  public void testValuesContainerIsRemovedIfIndexIsRemoved() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("TestValuesContainerIsRemovedIfIndexIsRemoved").
        createSchemaProperty("value", PropertyType.STRING).
        createPropertyIndex("TestValuesContainerIsRemovedIfIndexIsRemovedIndex",
            IndexType.NOT_UNIQUE)
    );

    for (var i = 0; i < 10; i++) {
      for (var j = 0; j < 100; j++) {
        session.begin();
        var document = ((EntityImpl) session.newEntity(
            "ValuesContainerIsRemovedIfIndexIsRemovedClass"));
        document.setProperty("val", "value" + i);

        session.commit();
      }
    }

    final var storageLocalAbstract = session.getStorage();

    final var writeCache = storageLocalAbstract.getWriteCache();
    Assert.assertTrue(writeCache.exists("ValuesContainerIsRemovedIfIndexIsRemovedIndex.irs"));
    session.execute("drop index ValuesContainerIsRemovedIfIndexIsRemovedIndex").close();
    Assert.assertFalse(writeCache.exists("ValuesContainerIsRemovedIfIndexIsRemovedIndex.irs"));
  }

  public void testPreservingIdentityInIndexTx() {
    graph.autoExecuteInTx(g ->
        g.schemaClass("PreservingIdentityInIndexTxParent").fold().coalesce(
            __.unfold(),
            __.createSchemaClass("PreservingIdentityInIndexTxParent")
                .createStateFullEdgeClass("PreservingIdentityInIndexTxEdge")
        ).schemaClass("PreservingIdentityInIndexTxChild").fold().coalesce(
            __.unfold(),
            __.createSchemaClass("PreservingIdentityInIndexTxChild",
                __.createSchemaProperty("name", PropertyType.STRING),
                __.createSchemaProperty("parent", PropertyType.LINK)
            ).createClassIndex("nameParentIndex", IndexType.NOT_UNIQUE, "in_field",
                "name")
        )
    );

    session.begin();
    var parent = session.newVertex("PreservingIdentityInIndexTxParent");
    var child = session.newVertex("PreservingIdentityInIndexTxChild");
    session.newStatefulEdge(parent, child, "PreservingIdentityInIndexTxEdge");
    child.setProperty("name", "pokus");

    var parent2 = session.newVertex("PreservingIdentityInIndexTxParent");
    var child2 = session.newVertex("PreservingIdentityInIndexTxChild");
    session.newStatefulEdge(parent2, child2, "preservingIdentityInIndexTxEdge");
    child2.setProperty("name", "pokus2");
    session.commit();

    {
      var fieldClass = session.getMetadata().getFastImmutableSchemaSnapshot()
          .getClass("PreservingIdentityInIndexTxChild");
      var index = fieldClass.getClassIndex("nameParentIndex");
      var key = new CompositeKey(parent.getIdentity(), "pokus");

      Collection<RID> h;
      try (var iterator = index.getRids(session, key)) {
        h = YTDBIteratorUtils.list(iterator);
      }
      for (var o : h) {
        Assert.assertNotNull(session.load(o));
      }
    }

    {
      var fieldClass = session.getMetadata().getFastImmutableSchemaSnapshot()
          .getClass("PreservingIdentityInIndexTxChild");
      var index = fieldClass.getClassIndex("nameParentIndex");
      var key = new CompositeKey(parent2.getIdentity(), "pokus2");

      Collection<RID> h;
      try (var iterator = index.getRids(session, key)) {
        h = YTDBIteratorUtils.list(iterator);
      }
      for (var o : h) {
        Assert.assertNotNull(session.load(o));
      }
    }

    session.begin();
    var activeTx3 = session.getActiveTransaction();
    session.delete(activeTx3.<Vertex>load(parent));
    var activeTx2 = session.getActiveTransaction();
    session.delete(activeTx2.<Vertex>load(child));

    var activeTx1 = session.getActiveTransaction();
    session.delete(activeTx1.<Vertex>load(parent2));
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Vertex>load(child2));
    session.commit();
  }

  public void testEmptyNotUniqueIndex() {

    var emptyNotUniqueIndexClazz = "EmptyNotUniqueIndexTest";
    graph.autoExecuteInTx(g -> g.createSchemaClass(emptyNotUniqueIndexClazz).
        createSchemaProperty("prop", PropertyType.STRING)
        .createPropertyIndex("EmptyNotUniqueIndexTestIndex", IndexType.NOT_UNIQUE));

    final var notUniqueIndex = session.getMetadata().getFastImmutableSchemaSnapshot()
        .getIndex("EmptyNotUniqueIndexTestIndex");

    session.begin();
    var document = ((EntityImpl) session.newEntity("EmptyNotUniqueIndexTest"));
    document.setProperty("prop", "keyOne");

    document = ((EntityImpl) session.newEntity("EmptyNotUniqueIndexTest"));
    document.setProperty("prop", "keyTwo");

    session.commit();

    try (var iterator = notUniqueIndex.getRids(session, "RandomKeyOne")) {
      Assert.assertFalse(iterator.hasNext());
    }
    try (var iterator = notUniqueIndex.getRids(session, "keyOne")) {
      Assert.assertTrue(iterator.hasNext());
    }

    try (var iterator = notUniqueIndex.getRids(session, "RandomKeyTwo")) {
      Assert.assertFalse(iterator.hasNext());
    }
    try (var iterator = notUniqueIndex.getRids(session, "keyTwo")) {
      Assert.assertTrue(iterator.hasNext());
    }
  }

  public void testNullIteration() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("NullIterationTest",
            __.createSchemaProperty("name", PropertyType.STRING),
            __.createSchemaProperty("birth", PropertyType.DATETIME)
        )
    );

    session.begin();
    session
        .execute("CREATE VERTEX NullIterationTest SET name = 'Andrew', birth = sysdate()")
        .close();
    session
        .execute("CREATE VERTEX NullIterationTest SET name = 'Marcel', birth = sysdate()")
        .close();
    session.execute("CREATE VERTEX NullIterationTest SET name = 'Olivier'").close();
    session.commit();

    graph.autoExecuteInTx(g -> g.schemaClass("NullIterationTest").schemaClassProperty("birth")
        .createPropertyIndex(IndexType.NOT_UNIQUE)
    );

    var result = session.query("SELECT FROM NullIterationTest ORDER BY birth ASC");
    Assert.assertEquals(result.stream().count(), 3);

    result = session.query("SELECT FROM NullIterationTest ORDER BY birth DESC");
    Assert.assertEquals(result.stream().count(), 3);

    result = session.query("SELECT FROM NullIterationTest");
    Assert.assertEquals(result.stream().count(), 3);
  }

  public void testMultikeyWithoutFieldAndNullSupport() {

    // generates stubs for index
    session.begin();
    var doc1 = ((EntityImpl) session.newEntity());

    var doc2 = ((EntityImpl) session.newEntity());

    var doc3 = ((EntityImpl) session.newEntity());

    var doc4 = ((EntityImpl) session.newEntity());

    session.commit();

    final RID rid1 = doc1.getIdentity();
    final RID rid2 = doc2.getIdentity();
    final RID rid3 = doc3.getIdentity();
    final RID rid4 = doc4.getIdentity();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("TestMultikeyWithoutField",
            __.createSchemaProperty("state", PropertyType.BYTE),
            __.createSchemaProperty("users", PropertyType.LINKSET),
            __.createSchemaProperty("time", PropertyType.LONG),
            __.createSchemaProperty("reg", PropertyType.LONG),
            __.createSchemaProperty("no", PropertyType.INTEGER)
        ).createClassIndex("MultikeyWithoutFieldIndex", IndexType.UNIQUE, "state", "users", "time",
            "reg", "no")
    );

    session.begin();
    var document = ((EntityImpl) session.newEntity("TestMultikeyWithoutField"));
    document.setProperty("state", (byte) 1);

    var users = session.newLinkSet();
    users.add(rid1);
    users.add(rid2);

    document.setProperty("users", users);
    document.setProperty("time", 12L);
    document.setProperty("reg", 14L);
    document.setProperty("no", 12);

    session.commit();

    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.size(session), 2);

    // we support first and last keys check only for embedded storage
    // we support first and last keys check only for embedded storage
    try (var keyIterator = index.keys()) {
      if (rid1.compareTo(rid2) < 0) {
        Assert.assertEquals(
            keyIterator.next(), new CompositeKey((byte) 1, rid1, 12L, 14L, 12));
      } else {
        Assert.assertEquals(
            keyIterator.next(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
      }
    }
    try (var descIterator = index.descEntries(session)) {
      if (rid1.compareTo(rid2) < 0) {
        Assert.assertEquals(
            descIterator.next().first(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
      } else {
        Assert.assertEquals(
            descIterator.next().first(), new CompositeKey((byte) 1, rid1, 12L, 14L, 12));
      }
    }

    final RID rid = document.getIdentity();

    session.close();
    session = acquireSession();

    session.begin();
    document = session.load(rid);

    users = document.getProperty("users");
    users.remove(rid1);

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.size(session), 1);
    try (var keyIterator = index.keys()) {
      Assert.assertEquals(
          keyIterator.next(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
    }

    session.close();
    session = acquireSession();

    session.begin();
    document = session.load(rid);

    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    users = document.getProperty("users");
    users.remove(rid2);
    Assert.assertTrue(users.isEmpty());

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndex");

    Assert.assertEquals(index.size(session), 1);
    try (var keyIteratorAsc = index.keys()) {
      Assert.assertEquals(
          keyIteratorAsc.next(), new CompositeKey((byte) 1, null, 12L, 14L, 12));
    }

    session.close();
    session = acquireSession();

    session.begin();
    document = session.load(rid);
    users = document.getProperty("users");
    users.add(rid3);

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndex");

    Assert.assertEquals(index.size(session), 1);
    try (var keyIterator = index.keys()) {
      Assert.assertEquals(
          keyIterator.next(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
    }

    session.close();
    session = acquireSession();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    users = document.getProperty("users");
    users.add(rid4);

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.size(session), 2);

    try (var keyIterator = index.keys()) {
      if (rid3.compareTo(rid4) < 0) {
        Assert.assertEquals(
            keyIterator.next(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
      } else {
        Assert.assertEquals(
            keyIterator.next(), new CompositeKey((byte) 1, rid4, 12L, 14L, 12));
      }
    }
    try (var descIterator = index.descEntries(session)) {
      if (rid3.compareTo(rid4) < 0) {
        Assert.assertEquals(
            descIterator.next().first(), new CompositeKey((byte) 1, rid4, 12L, 14L, 12));
      } else {
        Assert.assertEquals(
            descIterator.next().first(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
      }
    }

    session.close();
    session = acquireSession();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.removeProperty("users");

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.size(session), 1);

    try (var keyIterator = index.keys()) {
      Assert.assertEquals(
          keyIterator.next(), new CompositeKey((byte) 1, null, 12L, 14L, 12));
    }
  }

  public void testMultikeyWithoutFieldAndNoNullSupport() {

    // generates stubs for index
    session.begin();
    var doc1 = ((EntityImpl) session.newEntity());

    var doc2 = ((EntityImpl) session.newEntity());

    var doc3 = ((EntityImpl) session.newEntity());

    var doc4 = ((EntityImpl) session.newEntity());

    session.commit();

    final RID rid1 = doc1.getIdentity();
    final RID rid2 = doc2.getIdentity();
    final RID rid3 = doc3.getIdentity();
    final RID rid4 = doc4.getIdentity();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("TestMultikeyWithoutFieldNoNullSupport",
            __.createSchemaProperty("state", PropertyType.BYTE),
            __.createSchemaProperty("users", PropertyType.LINKSET),
            __.createSchemaProperty("time", PropertyType.LONG),
            __.createSchemaProperty("reg", PropertyType.LONG),
            __.createSchemaProperty("no", PropertyType.INTEGER)
        ).createClassIndex("MultikeyWithoutFieldIndexNoNullSupport", IndexType.UNIQUE, true,
            "state", "users", "time", "reg", "no")
    );

    session.begin();
    var document = ((EntityImpl) session.newEntity("TestMultikeyWithoutFieldNoNullSupport"));
    document.setProperty("state", (byte) 1);

    var users = session.newLinkSet();
    users.add(rid1);
    users.add(rid2);

    document.setProperty("users", users);
    document.setProperty("time", 12L);
    document.setProperty("reg", 14L);
    document.setProperty("no", 12);

    session.commit();

    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.size(session), 2);

    // we support first and last keys check only for embedded storage
    try (var keyIterator = index.keys()) {
      if (rid1.compareTo(rid2) < 0) {
        Assert.assertEquals(
            keyIterator.next(), new CompositeKey((byte) 1, rid1, 12L, 14L, 12));
      } else {
        Assert.assertEquals(
            keyIterator.next(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
      }
    }
    try (var descIterator = index.descEntries(session)) {
      if (rid1.compareTo(rid2) < 0) {
        Assert.assertEquals(
            descIterator.next().first(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
      } else {
        Assert.assertEquals(
            descIterator.next().first(), new CompositeKey((byte) 1, rid1, 12L, 14L, 12));
      }
    }

    final RID rid = document.getIdentity();

    session.close();
    session = acquireSession();

    session.begin();
    document = session.load(rid);
    users = document.getProperty("users");
    users.remove(rid1);

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.size(session), 1);
    try (var keyIterator = index.keys()) {
      Assert.assertEquals(
          keyIterator.next(), new CompositeKey((byte) 1, rid2, 12L, 14L, 12));
    }

    session.close();
    session = acquireSession();

    session.begin();
    document = session.load(rid);

    users = document.getProperty("users");
    users.remove(rid2);
    Assert.assertTrue(users.isEmpty());

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.size(session), 0);

    session.close();
    session = acquireSession();
    session.begin();

    document = session.load(rid);
    users = document.getProperty("users");
    users.add(rid3);

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.size(session), 1);

    try (var keyIterator = index.keys()) {
      Assert.assertEquals(
          keyIterator.next(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
    }

    session.close();
    session = acquireSession();

    session.begin();

    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    users = document.getProperty("users");
    users.add(rid4);

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.size(session), 2);

    try (var keyIterator = index.keys()) {
      if (rid3.compareTo(rid4) < 0) {
        Assert.assertEquals(
            keyIterator.next(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
      } else {
        Assert.assertEquals(
            keyIterator.next(), new CompositeKey((byte) 1, rid4, 12L, 14L, 12));
      }
    }
    try (var descIterator = index.descEntries(session)) {
      if (rid3.compareTo(rid4) < 0) {
        Assert.assertEquals(
            descIterator.next().first(), new CompositeKey((byte) 1, rid4, 12L, 14L, 12));
      } else {
        Assert.assertEquals(
            descIterator.next().first(), new CompositeKey((byte) 1, rid3, 12L, 14L, 12));
      }
    }

    session.close();
    session = acquireSession();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.removeProperty("users");

    session.commit();

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.size(session), 0);
  }

  public void testNullValuesCountSBTreeUnique() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("NullValuesCountSBTreeUnique").
            createSchemaProperty("field", PropertyType.INTEGER).
            createPropertyIndex("NullValuesCountSBTreeUniqueIndex", IndexType.UNIQUE)
    );

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountSBTreeUnique"));
    docOne.setProperty("field", 1);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountSBTreeUnique"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("NullValuesCountSBTreeUniqueIndex");
    Assert.assertEquals(index.size(session), 2);
    try (var iterator = index.ascEntries(session)) {
      try (var nullIterator = index.getRids(session, null)) {
        Assert.assertEquals(
            YTDBIteratorUtils.set(YTDBIteratorUtils.map(iterator, RawPair::first)).size()
                + YTDBIteratorUtils.list(nullIterator).size(), 2);
      }
    }
  }

  public void testNullValuesCountSBTreeNotUniqueOne() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("NullValuesCountSBTreeNotUniqueOne").
            createSchemaProperty("field", PropertyType.INTEGER).
            createPropertyIndex("NullValuesCountSBTreeNotUniqueOneIndex", IndexType.NOT_UNIQUE)
    );

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountSBTreeNotUniqueOne"));
    docOne.setProperty("field", 1);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountSBTreeNotUniqueOne"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("NullValuesCountSBTreeNotUniqueOneIndex");
    Assert.assertEquals(index.size(session), 2);
    try (var iterator = index.ascEntries(session)) {
      try (var nullIterator = index.getRids(session, null)) {
        Assert.assertEquals(
            YTDBIteratorUtils.set(YTDBIteratorUtils.map(iterator, RawPair::first)).size()
                + YTDBIteratorUtils.count(nullIterator), 2);
      }
    }
  }

  public void testNullValuesCountSBTreeNotUniqueTwo() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("NullValuesCountSBTreeNotUniqueTwo").
            createSchemaProperty("field", PropertyType.INTEGER)
            .createPropertyIndex("NullValuesCountSBTreeNotUniqueTwoIndex", IndexType.NOT_UNIQUE)
    );

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountSBTreeNotUniqueTwo"));
    docOne.setProperty("field", null);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountSBTreeNotUniqueTwo"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("NullValuesCountSBTreeNotUniqueTwoIndex");
    try (var iterator = index.ascEntries(session)) {
      try (var nullIterator = index.getRids(session, null)) {
        Assert.assertEquals(
            YTDBIteratorUtils.set(
                YTDBIteratorUtils.map(iterator, RawPair::first)
            ).size()
                + YTDBIteratorUtils.findFirst(nullIterator).map(v -> 1).orElse(0),
            1);
      }
    }
    Assert.assertEquals(index.size(session), 2);
  }

  public void testNullValuesCountHashUnique() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("NullValuesCountHashUnique").
            createSchemaProperty("field", PropertyType.INTEGER).
            createPropertyIndex("NullValuesCountHashUniqueIndex", IndexType.UNIQUE)
    );

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountHashUnique"));
    docOne.setProperty("field", 1);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountHashUnique"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("NullValuesCountHashUniqueIndex");
    Assert.assertEquals(index.size(session), 2);

    try (var iterator = index.ascEntries(session)) {
      try (var nullIterator = index.getRids(session, null)) {
        Assert.assertEquals(
            YTDBIteratorUtils.set(YTDBIteratorUtils.map(iterator, RawPair::first)).size()
                + YTDBIteratorUtils.count(nullIterator), 2);
      }
    }
  }

  @Test
  public void testParamsOrder() {
    session.execute("CREATE CLASS Task extends V").close();
    session
        .execute("CREATE PROPERTY Task.projectId STRING (MANDATORY TRUE, NOTNULL, MAX 20)")
        .close();
    session.execute("CREATE PROPERTY Task.seq SHORT ( MANDATORY TRUE, NOTNULL, MIN 0)").close();
    session.execute("CREATE INDEX TaskPK ON Task (projectId, seq) UNIQUE").close();

    session.begin();
    session.execute("INSERT INTO Task (projectId, seq) values ( 'foo', 2)").close();
    session.execute("INSERT INTO Task (projectId, seq) values ( 'bar', 3)").close();
    session.commit();

    var results =
        session
            .query("select from Task where projectId = 'foo' and seq = 2")
            .vertexStream()
            .toList();
    Assert.assertEquals(results.size(), 1);
  }

  private static void assertIndexUsage(ResultSet resultSet) {
    var executionPlan = resultSet.getExecutionPlan();
    for (var step : executionPlan.getSteps()) {
      if (assertIndexUsage(step, "Profile.nick")) {
        return;
      }
    }

    Assert.fail("Index " + "Profile.nick" + " was not used in the query");
  }

  private static boolean assertIndexUsage(ExecutionStep executionStep, String indexName) {
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
