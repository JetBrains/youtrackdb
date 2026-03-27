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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IndexTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    generateCompanyData();
  }

  @Test
  @Order(1)
  void testDuplicatedIndexOnUnique() {
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
      fail();

    } catch (RecordDuplicatedException e) {
      assertTrue(true);
    }
  }

  @Test
  @Order(5)
  void populateIndexDocuments() {
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

  @Test
  @Order(14)
  void linkedIndexedProperty() {
    try (var db = acquireSession()) {
      if (!db.getMetadata().getSchema().existsClass("TestClass")) {
        var testClass =
            db.getMetadata().getSchema().createClass("TestClass", 1);
        var testLinkClass =
            db.getMetadata().getSchema().createClass("TestLinkClass", 1);
        testClass
            .createProperty("testLink", PropertyType.LINK, testLinkClass)
            .createIndex(INDEX_TYPE.NOTUNIQUE);
        testClass.createProperty("name", PropertyType.STRING)
            .createIndex(INDEX_TYPE.UNIQUE);
        testLinkClass.createProperty("testBoolean", PropertyType.BOOLEAN);
        testLinkClass.createProperty("testString", PropertyType.STRING);
      }

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
      assertEquals(1, result.size());
      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.String
      result =
          db.query("select from TestClass where testLink.testString = 'Test Link Class 1'")
              .toList();
      assertEquals(1, result.size());
    }
  }

  @Test
  @Order(16)
  void createInheritanceIndex() {
    try (var db = acquireSession()) {
      if (!db.getMetadata().getSchema().existsClass("BaseTestClass")) {
        var baseClass =
            db.getMetadata().getSchema().createClass("BaseTestClass", 1);
        var childClass =
            db.getMetadata().getSchema().createClass("ChildTestClass", 1);
        var anotherChildClass =
            db.getMetadata().getSchema().createClass("AnotherChildTestClass", 1);

        if (!baseClass.isSuperClassOf(childClass)) {
          childClass.addSuperClass(baseClass);
        }
        if (!baseClass.isSuperClassOf(anotherChildClass)) {
          anotherChildClass.addSuperClass(baseClass);
        }

        baseClass
            .createProperty("testParentProperty", PropertyType.LONG)
            .createIndex(INDEX_TYPE.NOTUNIQUE);
      }

      db.begin();
      var childClassDocument = db.newInstance("ChildTestClass");
      childClassDocument.setProperty("testParentProperty", 10L);

      var anotherChildClassDocument = db.newInstance("AnotherChildTestClass");
      anotherChildClassDocument.setProperty("testParentProperty", 11L);

      db.commit();

      assertNotEquals(
          new RecordId(-1, RID.COLLECTION_POS_INVALID),
          childClassDocument.getIdentity());
      assertNotEquals(
          new RecordId(-1, RID.COLLECTION_POS_INVALID),
          anotherChildClassDocument.getIdentity());
    }
  }

  @Test
  @Order(18)
  void testTransactionUniqueIndexTestOne() {

    var db = acquireSession();
    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexTest")) {
      final var termClass =
          db.getMetadata()
              .getSchema()
              .createClass("TransactionUniqueIndexTest", 1);
      termClass.createProperty("label", PropertyType.STRING);
      termClass.createIndex(
          "idxTransactionUniqueIndexTest",
          INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true), new String[] {"label"});
    }

    db.begin();
    var docOne = ((EntityImpl) db.newEntity("TransactionUniqueIndexTest"));
    docOne.setProperty("label", "A");

    db.commit();

    final var index =
        db.getSharedContext().getIndexManager().getIndex("idxTransactionUniqueIndexTest");
    db.begin();
    assertEquals(1, index.size(db));
    db.rollback();

    db.begin();
    try {
      var docTwo = ((EntityImpl) db.newEntity("TransactionUniqueIndexTest"));
      docTwo.setProperty("label", "A");

      db.commit();
      fail();
    } catch (RecordDuplicatedException ignored) {
    }

    db.begin();
    assertEquals(1, index.size(db));
    db.rollback();
  }

  @Test
  @Order(20)
  void testTransactionUniqueIndexTestWithDotNameOne() {

    var db = acquireSession();
    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexWithDotTest")) {
      final var termClass =
          db.getMetadata()
              .getSchema()
              .createClass("TransactionUniqueIndexWithDotTest", 1);
      termClass.createProperty("label", PropertyType.STRING).createIndex(INDEX_TYPE.UNIQUE);
    }

    db.begin();
    var docOne = ((EntityImpl) db.newEntity("TransactionUniqueIndexWithDotTest"));
    docOne.setProperty("label", "A");

    db.commit();

    final var index =
        db.getSharedContext()
            .getIndexManager()
            .getIndex("TransactionUniqueIndexWithDotTest.label");
    db.begin();
    assertEquals(1, index.size(db));
    var countClassBefore = db.countClass("TransactionUniqueIndexWithDotTest");
    db.rollback();

    db.begin();
    try {
      var docTwo = ((EntityImpl) db.newEntity("TransactionUniqueIndexWithDotTest"));
      docTwo.setProperty("label", "A");

      db.commit();
      fail();
    } catch (RecordDuplicatedException ignored) {
    }

    db.begin();
    assertEquals(
        countClassBefore,
        db.query("select from TransactionUniqueIndexWithDotTest").toList()
            .size());

    assertEquals(1, index.size(db));
    db.rollback();
  }

  @Test
  @Order(2)
  void testIndexRebuildDuringNonProxiedObjectDelete() {

    session.begin();
    var profile = session.newEntity("Profile");
    profile.setProperty("nick", "NonProxiedObjectToDelete");
    profile.setProperty("name", "NonProxiedObjectToDelete");
    profile.setProperty("surname", "NonProxiedObjectToDelete");
    session.commit();

    var idxManager = session.getSharedContext().getIndexManager();
    var nickIndex = idxManager.getIndex("Profile.nick");

    session.begin();
    try (var stream = nickIndex
        .getRids(session, "NonProxiedObjectToDelete")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();
    final Entity loadedProfile = session.load(profile.getIdentity());
    session.delete(loadedProfile);
    session.commit();

    session.begin();
    try (var stream = nickIndex
        .getRids(session, "NonProxiedObjectToDelete")) {
      assertFalse(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void indexLinks() {

    session
        .getMetadata()
        .getSchema()
        .getClass("Whiz")
        .getProperty("account")
        .createIndex(INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var resultSet = executeQuery("select * from Account limit 1");
    final var idx =
        session.getSharedContext().getIndexManager().getIndex("Whiz.account");

    for (var i = 0; i < 5; i++) {
      final var whiz = ((EntityImpl) session.newEntity("Whiz"));

      whiz.setProperty("id", i);
      whiz.setProperty("text", "This is a test");
      whiz.setPropertyInChain("account", resultSet.getFirst().asEntityOrNull().getIdentity());

    }
    session.commit();

    session.begin();
    assertEquals(5, idx.size(session));
    session.rollback();

    session.begin();
    var indexedResult =
        executeQuery("select * from Whiz where account = ?",
            resultSet.getFirst().getIdentity());
    assertEquals(5, indexedResult.size());

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
    assertTrue(((EntityImpl) whiz.getProperty("account")).getIdentity().isValidPosition());
    ((EntityImpl) whiz.getProperty("account")).delete();
    whiz.delete();
    session.commit();
  }

  @Test
  @Order(22)
  void testConcurrentRemoveDelete() {

    try (var db = acquireSession()) {
      if (!db.getMetadata().getSchema().existsClass("MyFruit")) {
        var fruitClass = db.getMetadata().getSchema().createClass("MyFruit", 1);
        fruitClass.createProperty("name", PropertyType.STRING);
        fruitClass.createProperty("color", PropertyType.STRING);

        db.getMetadata()
            .getSchema()
            .getClass("MyFruit")
            .getProperty("name")
            .createIndex(INDEX_TYPE.UNIQUE);

        db.getMetadata()
            .getSchema()
            .getClass("MyFruit")
            .getProperty("color")
            .createIndex(INDEX_TYPE.NOTUNIQUE);
      }

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
        db.begin();
        assertEquals(
            expectedIndexSize,
            db.getSharedContext()
                .getIndexManager()
                .getClassIndex(db, "MyFruit", "MyFruit.color")

                .size(db),
            "After add");
        db.rollback();

        // do delete
        db.begin();
        for (final var recordToDelete : recordsToDelete) {
          var activeTx = db.getActiveTransaction();
          db.delete(activeTx.<EntityImpl>load(recordToDelete));
        }
        db.commit();

        expectedIndexSize -= recordsToDelete.size();
        db.begin();
        assertEquals(
            expectedIndexSize,
            db.getSharedContext()
                .getIndexManager()
                .getClassIndex(db, "MyFruit", "MyFruit.color")

                .size(db),
            "After delete");
        db.rollback();
      }
    }
  }

  @Test
  @Order(22)
  void testIndexParamsAutoConversion() {

    final EntityImpl doc;
    final RecordIdInternal result;
    try (var db = acquireSession()) {
      if (!db.getMetadata().getSchema().existsClass("IndexTestTerm")) {
        final var termClass =
            db.getMetadata().getSchema().createClass("IndexTestTerm", 1);
        termClass.createProperty("label", PropertyType.STRING);
        termClass.createIndex(
            "idxTerm",
            INDEX_TYPE.UNIQUE.toString(),
            null,
            Map.of("ignoreNullValues", true), new String[] {"label"});
      }

      db.begin();
      doc = ((EntityImpl) db.newEntity("IndexTestTerm"));
      doc.setProperty("label", "42");

      db.commit();

      db.begin();
      try (var stream =
          db.getSharedContext()
              .getIndexManager()
              .getIndex("idxTerm")

              .getRids(db, "42")) {
        result = (RecordIdInternal) stream.findAny().orElse(null);
      }
      db.rollback();
    }
    assertNotNull(result);
    assertEquals(doc.getIdentity(), result.getIdentity());
  }

  @Test
  @Order(22)
  void testNotUniqueIndexKeySize() {

    final Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("IndexNotUniqueIndexKeySize");
    cls.createProperty("value", PropertyType.INTEGER);
    cls.createIndex("IndexNotUniqueIndexKeySizeIndex", INDEX_TYPE.NOTUNIQUE, "value");

    var idxManager = session.getSharedContext().getIndexManager();

    final var idx = idxManager.getIndex("IndexNotUniqueIndexKeySizeIndex");

    final Set<Integer> keys = new HashSet<>();
    for (var i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      session.begin();
      final var doc = ((EntityImpl) session.newEntity("IndexNotUniqueIndexKeySize"));
      doc.setProperty("value", key);

      session.commit();

      keys.add(key);
    }

    session.begin();
    try (var stream = idx.stream(session)) {
      assertEquals(keys.size(), stream.map(RawPair::first).distinct().count());
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void testNotUniqueIndexSize() {

    final Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("IndexNotUniqueIndexSize");
    cls.createProperty("value", PropertyType.INTEGER);
    cls.createIndex("IndexNotUniqueIndexSizeIndex", INDEX_TYPE.NOTUNIQUE, "value");

    var idxManager = session.getSharedContext().getIndexManager();
    final var idx = idxManager.getIndex("IndexNotUniqueIndexSizeIndex");

    for (var i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      session.begin();
      final var doc = ((EntityImpl) session.newEntity("IndexNotUniqueIndexSize"));
      doc.setProperty("value", key);

      session.commit();
    }

    session.begin();
    assertEquals(99, idx.size(session));
    session.rollback();
  }

  @Test
  @Order(22)
  void testIndexInCompositeQuery() {
    var classOne =
        session.getMetadata().getSchema()
            .createClass("CompoundSQLIndexTest1", 1);
    var classTwo =
        session.getMetadata().getSchema()
            .createClass("CompoundSQLIndexTest2", 1);

    classTwo.createProperty("address", PropertyType.LINK, classOne);

    classTwo.createIndex("CompoundSQLIndexTestIndex", INDEX_TYPE.UNIQUE, "address");

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
    assertEquals(1, result.size());
    assertEquals(docTwo.getIdentity(), result.getFirst().getIdentity());
  }

  @Test
  @Order(22)
  void testIndexWithLimitAndOffset() {
    final var schema = session.getMetadata().getSchema();
    final var indexWithLimitAndOffset =
        schema.createClass("IndexWithLimitAndOffsetClass", 1);
    indexWithLimitAndOffset.createProperty("val", PropertyType.INTEGER);
    indexWithLimitAndOffset.createProperty("index", PropertyType.INTEGER);

    session
        .execute(
            "create index IndexWithLimitAndOffset on IndexWithLimitAndOffsetClass (val) notunique")
        .close();

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
    assertEquals(2, resultSet.size());

    for (var i = 0; i < 2; i++) {
      var result = resultSet.get(i);
      assertEquals(1, result.<Object>getProperty("val"));
      assertEquals(15 + i, result.<Object>getProperty("index"));
    }
    tx.commit();
  }

  @Test
  @Order(22)
  void testNullIndexKeysSupport() {
    final var schema = session.getMetadata().getSchema();
    final var clazz = schema.createClass("NullIndexKeysSupport", 1);
    clazz.createProperty("nullField", PropertyType.STRING);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "NullIndexKeysSupportIndex",
        INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[] {"nullField"});
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
    assertEquals(1, resultSet.size());

    assertEquals("val3", resultSet.getFirst().getProperty("nullField"));

    resultSet = executeQuery("select from NullIndexKeysSupport where nullField is null");

    assertEquals(4, resultSet.size());
    for (var result : resultSet) {
      assertNull(result.getProperty("nullField"));
    }
    session.commit();
  }

  @Test
  @Order(22)
  void testNullHashIndexKeysSupport() {
    final var schema = session.getMetadata().getSchema();
    final var clazz = schema.createClass("NullHashIndexKeysSupport", 1);
    clazz.createProperty("nullField", PropertyType.STRING);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "NullHashIndexKeysSupportIndex",
        INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[] {"nullField"});
    for (var i = 0; i < 20; i++) {
      session.begin();
      if (i % 5 == 0) {
        var document = ((EntityImpl) session.newEntity("NullHashIndexKeysSupport"));
        document.setProperty("nullField", null);

      } else {
        var document = ((EntityImpl) session.newEntity("NullHashIndexKeysSupport"));
        document.setProperty("nullField", "val" + i);

      }
      session.commit();
    }

    session.begin();
    var result =
        session.query(
            "select from NullHashIndexKeysSupport where nullField = 'val3'").toList();
    assertEquals(1, result.size());

    var entity = result.getFirst();
    assertEquals("val3", entity.getProperty("nullField"));

    result =
        session.query("select from NullHashIndexKeysSupport where nullField is null").toList();

    assertEquals(4, result.size());
    for (var document : result) {
      assertNull(document.getProperty("nullField"));
    }

    session.commit();
  }

  @Test
  @Order(22)
  void testNullIndexKeysSupportInTx() {
    final var schema = session.getMetadata().getSchema();
    final var clazz = schema.createClass("NullIndexKeysSupportInTx", 1);
    clazz.createProperty("nullField", PropertyType.STRING);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "NullIndexKeysSupportInTxIndex",
        INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[] {"nullField"});

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
    assertEquals(1, result.size());

    var entity = result.getFirst();
    assertEquals("val3", entity.getProperty("nullField"));

    result =
        session.query(
            "select from NullIndexKeysSupportInTx where nullField is null").toList();

    assertEquals(4, result.size());
    for (var document : result) {
      assertNull(document.getProperty("nullField"));
    }
    session.commit();
  }

  @Test
  @Order(22)
  void testNullIndexKeysSupportInMiddleTx() {
    final var schema = session.getMetadata().getSchema();
    final var clazz = schema.createClass("NullIndexKeysSupportInMiddleTx", 1);
    clazz.createProperty("nullField", PropertyType.STRING);

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    clazz.createIndex(
        "NullIndexKeysSupportInMiddleTxIndex",
        INDEX_TYPE.NOTUNIQUE.toString(),
        null,
        metadata, new String[] {"nullField"});

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
    assertEquals(1, result.size());

    var entity = result.getFirst();
    assertEquals("val3", entity.getProperty("nullField"));

    result =
        session.query(
            "select from NullIndexKeysSupportInMiddleTx where nullField is null").toList();

    assertEquals(4, result.size());
    for (var document : result) {
      assertNull(document.getProperty("nullField"));
    }

    session.commit();
  }

  @Test
  @Order(22)
  void testCreateIndexAbstractClass() {
    final var schema = session.getSchema();

    var abstractClass = schema.createAbstractClass("TestCreateIndexAbstractClass");
    abstractClass
        .createProperty("value", PropertyType.STRING)
        .setMandatory(true)
        .createIndex(INDEX_TYPE.UNIQUE);

    schema.createClass("TestCreateIndexAbstractClassChildOne", abstractClass);
    schema.createClass("TestCreateIndexAbstractClassChildTwo", abstractClass);

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("TestCreateIndexAbstractClassChildOne"));
    docOne.setProperty("value", "val1");

    var docTwo = ((EntityImpl) session.newEntity("TestCreateIndexAbstractClassChildTwo"));
    docTwo.setProperty("value", "val2");

    session.commit();

    session.begin();
    final var queryOne = "select from TestCreateIndexAbstractClass where value = 'val1'";

    var resultOne = executeQuery(queryOne);
    assertEquals(1, resultOne.size());
    assertEquals(docOne.getIdentity(), resultOne.getFirst().getIdentity());

    try (var result = session.execute("explain " + queryOne)) {
      var explain = result.next();
      assertTrue(
          explain
              .<String>getProperty("executionPlanAsString")
              .contains("FETCH FROM INDEX TestCreateIndexAbstractClass.value"));

      final var queryTwo = "select from TestCreateIndexAbstractClass where value = 'val2'";

      var resultTwo = executeQuery(queryTwo);
      assertEquals(1, resultTwo.size());
      assertEquals(docTwo.getIdentity(), resultTwo.getFirst().getIdentity());
    }
    session.commit();
  }

  @Test
  @Order(22)
  @Disabled("Test was historically disabled — needs investigation")
  void testValuesContainerIsRemovedIfIndexIsRemoved() {
    final var schema = session.getMetadata().getSchema();
    var clazz =
        schema.createClass("ValuesContainerIsRemovedIfIndexIsRemovedClass", 1);
    clazz.createProperty("val", PropertyType.STRING);

    session
        .execute(
            "create index ValuesContainerIsRemovedIfIndexIsRemovedIndex on"
                + " ValuesContainerIsRemovedIfIndexIsRemovedClass (val) notunique")
        .close();

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
    assertTrue(writeCache.exists("ValuesContainerIsRemovedIfIndexIsRemovedIndex.irs"));
    session.execute("drop index ValuesContainerIsRemovedIfIndexIsRemovedIndex").close();
    assertFalse(writeCache.exists("ValuesContainerIsRemovedIfIndexIsRemovedIndex.irs"));
  }

  @Test
  @Order(22)
  void testPreservingIdentityInIndexTx() {
    if (!session.getMetadata().getSchema().existsClass("PreservingIdentityInIndexTxParent")) {
      session.createVertexClass("PreservingIdentityInIndexTxParent");
    }
    if (!session.getMetadata().getSchema().existsClass("PreservingIdentityInIndexTxEdge")) {
      session.createEdgeClass("PreservingIdentityInIndexTxEdge");
    }
    var fieldClass = (SchemaClassInternal) session.getClass("PreservingIdentityInIndexTxChild");
    if (fieldClass == null) {
      fieldClass = (SchemaClassInternal) session.createVertexClass(
          "PreservingIdentityInIndexTxChild");
      fieldClass.createProperty("name", PropertyType.STRING);
      fieldClass.createProperty("in_field", PropertyType.LINK);
      fieldClass.createIndex("nameParentIndex", INDEX_TYPE.NOTUNIQUE, "in_field", "name");
    }

    session.begin();
    var parent = session.newVertex("PreservingIdentityInIndexTxParent");
    var child = session.newVertex("PreservingIdentityInIndexTxChild");
    session.newEdge(parent, child, "PreservingIdentityInIndexTxEdge");
    child.setProperty("name", "pokus");

    var parent2 = session.newVertex("PreservingIdentityInIndexTxParent");
    var child2 = session.newVertex("PreservingIdentityInIndexTxChild");
    session.newEdge(parent2, child2, "PreservingIdentityInIndexTxEdge");
    child2.setProperty("name", "pokus2");
    session.commit();

    session.begin();
    {
      fieldClass = session.getClassInternal("PreservingIdentityInIndexTxChild");
      var index = fieldClass.getClassIndex(session, "nameParentIndex");
      var key = new CompositeKey(parent.getIdentity(), "pokus");

      Collection<RID> h;
      try (var stream = index.getRids(session, key)) {
        h = stream.toList();
      }
      for (var o : h) {
        assertNotNull(session.load(o));
      }
    }

    {
      fieldClass = (SchemaClassInternal) session.getClass("PreservingIdentityInIndexTxChild");
      var index = fieldClass.getClassIndex(session, "nameParentIndex");
      var key = new CompositeKey(parent2.getIdentity(), "pokus2");

      Collection<RID> h;
      try (var stream = index.getRids(session, key)) {
        h = stream.toList();
      }
      for (var o : h) {
        assertNotNull(session.load(o));
      }
    }
    session.rollback();

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

  @Test
  @Order(22)
  void testEmptyNotUniqueIndex() {

    var emptyNotUniqueIndexClazz =
        session
            .getMetadata()
            .getSchema()
            .createClass("EmptyNotUniqueIndexTest", 1);
    emptyNotUniqueIndexClazz.createProperty("prop", PropertyType.STRING);

    emptyNotUniqueIndexClazz.createIndex(
        "EmptyNotUniqueIndexTestIndex", INDEX_TYPE.NOTUNIQUE, "prop");
    final var notUniqueIndex = session.getIndex("EmptyNotUniqueIndexTestIndex");

    session.begin();
    var document = ((EntityImpl) session.newEntity("EmptyNotUniqueIndexTest"));
    document.setProperty("prop", "keyOne");

    document = ((EntityImpl) session.newEntity("EmptyNotUniqueIndexTest"));
    document.setProperty("prop", "keyTwo");

    session.commit();

    session.begin();
    try (var stream = notUniqueIndex.getRids(session, "RandomKeyOne")) {
      assertFalse(stream.findAny().isPresent());
    }
    try (var stream = notUniqueIndex.getRids(session, "keyOne")) {
      assertTrue(stream.findAny().isPresent());
    }

    try (var stream = notUniqueIndex.getRids(session, "RandomKeyTwo")) {
      assertFalse(stream.findAny().isPresent());
    }
    try (var stream = notUniqueIndex.getRids(session, "keyTwo")) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void testNullIteration() {
    var v = session.getSchema().getClass("V");
    var testNullIteration =
        session.getMetadata().getSchema().createClass("NullIterationTest", v);
    testNullIteration.createProperty("name", PropertyType.STRING);
    testNullIteration.createProperty("birth", PropertyType.DATETIME);

    session.begin();
    session
        .execute("CREATE VERTEX NullIterationTest SET name = 'Andrew', birth = sysdate()")
        .close();
    session
        .execute("CREATE VERTEX NullIterationTest SET name = 'Marcel', birth = sysdate()")
        .close();
    session.execute("CREATE VERTEX NullIterationTest SET name = 'Olivier'").close();
    session.commit();

    var metadata = Map.<String, Object>of("ignoreNullValues", false);

    testNullIteration.createIndex(
        "NullIterationTestIndex",
        INDEX_TYPE.NOTUNIQUE.name(),
        null,
        metadata, new String[] {"birth"});

    var result = session.query("SELECT FROM NullIterationTest ORDER BY birth ASC");
    assertEquals(3, result.stream().count());

    result = session.query("SELECT FROM NullIterationTest ORDER BY birth DESC");
    assertEquals(3, result.stream().count());

    result = session.query("SELECT FROM NullIterationTest");
    assertEquals(3, result.stream().count());
  }

  @Test
  @Order(22)
  void testMultikeyWithoutFieldAndNullSupport() {

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
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("TestMultikeyWithoutField");

    clazz.createProperty("state", PropertyType.BYTE);
    clazz.createProperty("users", PropertyType.LINKSET);
    clazz.createProperty("time", PropertyType.LONG);
    clazz.createProperty("reg", PropertyType.LONG);
    clazz.createProperty("no", PropertyType.INTEGER);

    var mt = Map.<String, Object>of("ignoreNullValues", false);
    clazz.createIndex(
        "MultikeyWithoutFieldIndex",
        INDEX_TYPE.UNIQUE.toString(),
        null,
        mt, new String[] {"state", "users", "time", "reg", "no"});

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
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndex");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    // we support first and last keys check only for embedded storage
    // we support first and last keys check only for embedded storage
    var txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      if (rid1.compareTo(rid2) < 0) {
        assertEquals(
            new CompositeKey((byte) 1, rid1, 12L, 14L, 12),
            keyStream.iterator().next());
      } else {
        assertEquals(
            new CompositeKey((byte) 1, rid2, 12L, 14L, 12),
            keyStream.iterator().next());
      }
    }
    try (var descStream = index.descStream(session)) {
      if (rid1.compareTo(rid2) < 0) {
        assertEquals(
            new CompositeKey((byte) 1, rid2, 12L, 14L, 12),
            descStream.iterator().next().first());
      } else {
        assertEquals(
            new CompositeKey((byte) 1, rid1, 12L, 14L, 12),
            descStream.iterator().next().first());
      }
    }
    session.rollback();

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
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndex");
    session.begin();
    assertEquals(1, index.size(session));
    session.rollback();
    txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      assertEquals(
          new CompositeKey((byte) 1, rid2, 12L, 14L, 12),
          keyStream.iterator().next());
    }
    session.rollback();

    session.close();
    session = acquireSession();

    session.begin();
    document = session.load(rid);

    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    users = document.getProperty("users");
    users.remove(rid2);
    assertTrue(users.isEmpty());

    session.commit();

    index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndex");

    session.begin();
    assertEquals(1, index.size(session));
    session.rollback();
    txRead = session.begin();
    try (var keyStreamAsc = index.keyStream(txRead.getAtomicOperation())) {
      assertEquals(
          new CompositeKey((byte) 1, null, 12L, 14L, 12),
          keyStreamAsc.iterator().next());
    }
    session.rollback();

    session.close();
    session = acquireSession();

    session.begin();
    document = session.load(rid);
    users = document.getProperty("users");
    users.add(rid3);

    session.commit();

    index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndex");

    session.begin();
    assertEquals(1, index.size(session));
    session.rollback();
    txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      assertEquals(
          new CompositeKey((byte) 1, rid3, 12L, 14L, 12),
          keyStream.iterator().next());
    }
    session.rollback();

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
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndex");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      if (rid3.compareTo(rid4) < 0) {
        assertEquals(
            new CompositeKey((byte) 1, rid3, 12L, 14L, 12),
            keyStream.iterator().next());
      } else {
        assertEquals(
            new CompositeKey((byte) 1, rid4, 12L, 14L, 12),
            keyStream.iterator().next());
      }
    }
    try (var descStream = index.descStream(session)) {
      if (rid3.compareTo(rid4) < 0) {
        assertEquals(
            new CompositeKey((byte) 1, rid4, 12L, 14L, 12),
            descStream.iterator().next().first());
      } else {
        assertEquals(
            new CompositeKey((byte) 1, rid3, 12L, 14L, 12),
            descStream.iterator().next().first());
      }
    }
    session.rollback();

    session.close();
    session = acquireSession();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.removeProperty("users");

    session.commit();

    index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndex");
    session.begin();
    assertEquals(1, index.size(session));
    session.rollback();

    txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      assertEquals(
          new CompositeKey((byte) 1, null, 12L, 14L, 12),
          keyStream.iterator().next());
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void testMultikeyWithoutFieldAndNoNullSupport() {

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

    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("TestMultikeyWithoutFieldNoNullSupport");

    clazz.createProperty("state", PropertyType.BYTE);
    clazz.createProperty("users", PropertyType.LINKSET);
    clazz.createProperty("time", PropertyType.LONG);
    clazz.createProperty("reg", PropertyType.LONG);
    clazz.createProperty("no", PropertyType.INTEGER);

    clazz.createIndex(
        "MultikeyWithoutFieldIndexNoNullSupport",
        INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"state", "users", "time", "reg", "no"});

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
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    // we support first and last keys check only for embedded storage
    var txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      if (rid1.compareTo(rid2) < 0) {
        assertEquals(
            new CompositeKey((byte) 1, rid1, 12L, 14L, 12),
            keyStream.iterator().next());
      } else {
        assertEquals(
            new CompositeKey((byte) 1, rid2, 12L, 14L, 12),
            keyStream.iterator().next());
      }
    }
    try (var descStream = index.descStream(session)) {
      if (rid1.compareTo(rid2) < 0) {
        assertEquals(
            new CompositeKey((byte) 1, rid2, 12L, 14L, 12),
            descStream.iterator().next().first());
      } else {
        assertEquals(
            new CompositeKey((byte) 1, rid1, 12L, 14L, 12),
            descStream.iterator().next().first());
      }
    }
    session.rollback();

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
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    session.begin();
    assertEquals(1, index.size(session));
    session.rollback();
    txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      assertEquals(
          new CompositeKey((byte) 1, rid2, 12L, 14L, 12),
          keyStream.iterator().next());
    }
    session.rollback();

    session.close();
    session = acquireSession();

    session.begin();
    document = session.load(rid);

    users = document.getProperty("users");
    users.remove(rid2);
    assertTrue(users.isEmpty());

    session.commit();

    index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();

    session.close();
    session = acquireSession();
    session.begin();

    document = session.load(rid);
    users = document.getProperty("users");
    users.add(rid3);

    session.commit();

    index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    session.begin();
    assertEquals(1, index.size(session));
    session.rollback();

    txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      assertEquals(
          new CompositeKey((byte) 1, rid3, 12L, 14L, 12),
          keyStream.iterator().next());
    }
    session.rollback();

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
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    txRead = session.begin();
    try (var keyStream = index.keyStream(txRead.getAtomicOperation())) {
      if (rid3.compareTo(rid4) < 0) {
        assertEquals(
            new CompositeKey((byte) 1, rid3, 12L, 14L, 12),
            keyStream.iterator().next());
      } else {
        assertEquals(
            new CompositeKey((byte) 1, rid4, 12L, 14L, 12),
            keyStream.iterator().next());
      }
    }
    session.rollback();
    session.begin();
    try (var descStream = index.descStream(session)) {
      if (rid3.compareTo(rid4) < 0) {
        assertEquals(
            new CompositeKey((byte) 1, rid4, 12L, 14L, 12),
            descStream.iterator().next().first());
      } else {
        assertEquals(
            new CompositeKey((byte) 1, rid3, 12L, 14L, 12),
            descStream.iterator().next().first());
      }
    }
    session.rollback();

    session.close();
    session = acquireSession();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.removeProperty("users");

    session.commit();

    index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  @Order(22)
  void testNullValuesCountSBTreeUnique() {

    var nullSBTreeClass = session.getSchema().createClass("NullValuesCountSBTreeUnique");
    nullSBTreeClass.createProperty("field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex("NullValuesCountSBTreeUniqueIndex", INDEX_TYPE.UNIQUE,
        "field");

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountSBTreeUnique"));
    docOne.setProperty("field", 1);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountSBTreeUnique"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("NullValuesCountSBTreeUniqueIndex");
    session.begin();
    assertEquals(2, index.size(session));
    try (var stream = index.stream(session)) {
      try (var nullStream = index.getRids(session, null)) {
        assertEquals(
            2,
            stream.map(RawPair::first).distinct().count() + nullStream.count());
      }
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void testNullValuesCountSBTreeNotUniqueOne() {

    var nullSBTreeClass =
        session.getMetadata().getSchema().createClass("NullValuesCountSBTreeNotUniqueOne");
    nullSBTreeClass.createProperty("field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(
        "NullValuesCountSBTreeNotUniqueOneIndex", INDEX_TYPE.NOTUNIQUE, "field");

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountSBTreeNotUniqueOne"));
    docOne.setProperty("field", 1);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountSBTreeNotUniqueOne"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("NullValuesCountSBTreeNotUniqueOneIndex");
    session.begin();
    assertEquals(2, index.size(session));
    try (var stream = index.stream(session)) {
      try (var nullStream = index.getRids(session, null)) {
        assertEquals(
            2,
            stream.map(RawPair::first).distinct().count() + nullStream.count());
      }
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void testNullValuesCountSBTreeNotUniqueTwo() {

    var nullSBTreeClass =
        session.getMetadata().getSchema().createClass("NullValuesCountSBTreeNotUniqueTwo");
    nullSBTreeClass.createProperty("field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(
        "NullValuesCountSBTreeNotUniqueTwoIndex", INDEX_TYPE.NOTUNIQUE, "field");

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountSBTreeNotUniqueTwo"));
    docOne.setProperty("field", null);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountSBTreeNotUniqueTwo"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("NullValuesCountSBTreeNotUniqueTwoIndex");
    session.begin();
    try (var stream = index.stream(session)) {
      try (var nullStream = index.getRids(session, null)) {
        assertEquals(
            1,
            stream.map(RawPair::first).distinct().count()
                + nullStream.findAny().map(v -> 1).orElse(0));
      }
    }
    assertEquals(2, index.size(session));
    session.rollback();
  }

  @Test
  @Order(22)
  void testNullValuesCountHashUnique() {
    var nullSBTreeClass = session.getSchema().createClass("NullValuesCountHashUnique");
    nullSBTreeClass.createProperty("field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(
        "NullValuesCountHashUniqueIndex", INDEX_TYPE.UNIQUE, "field");

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountHashUnique"));
    docOne.setProperty("field", 1);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountHashUnique"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("NullValuesCountHashUniqueIndex");
    session.begin();
    assertEquals(2, index.size(session));
    try (var stream = index.stream(session)) {
      try (var nullStream = index.getRids(session, null)) {
        assertEquals(
            2,
            stream.map(RawPair::first).distinct().count() + nullStream.count());
      }
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void testNullValuesCountHashNotUniqueOne() {

    var nullSBTreeClass = session.getSchema()
        .createClass("NullValuesCountHashNotUniqueOne");
    nullSBTreeClass.createProperty("field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(
        "NullValuesCountHashNotUniqueOneIndex", INDEX_TYPE.NOTUNIQUE, "field");

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountHashNotUniqueOne"));
    docOne.setProperty("field", 1);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountHashNotUniqueOne"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("NullValuesCountHashNotUniqueOneIndex");
    session.begin();
    assertEquals(2, index.size(session));
    try (var stream = index.stream(session)) {
      try (var nullStream = index.getRids(session, null)) {
        assertEquals(
            2,
            stream.map(RawPair::first).distinct().count() + nullStream.count());
      }
    }
    session.rollback();
  }

  @Test
  @Order(22)
  void testNullValuesCountHashNotUniqueTwo() {

    var nullSBTreeClass =
        session.getMetadata().getSchema().createClass("NullValuesCountHashNotUniqueTwo");
    nullSBTreeClass.createProperty("field", PropertyType.INTEGER);
    nullSBTreeClass.createIndex(
        "NullValuesCountHashNotUniqueTwoIndex", INDEX_TYPE.NOTUNIQUE, "field");

    session.begin();
    var docOne = ((EntityImpl) session.newEntity("NullValuesCountHashNotUniqueTwo"));
    docOne.setProperty("field", null);

    var docTwo = ((EntityImpl) session.newEntity("NullValuesCountHashNotUniqueTwo"));
    docTwo.setProperty("field", null);

    session.commit();

    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("NullValuesCountHashNotUniqueTwoIndex");
    session.begin();
    try (var stream = index.stream(session)) {
      try (var nullStream = index.getRids(session, null)) {
        assertEquals(
            1,
            stream.map(RawPair::first).distinct().count()
                + nullStream.findAny().map(v -> 1).orElse(0));
      }
    }
    assertEquals(2, index.size(session));
    session.rollback();
  }

  @Test
  @Order(22)
  void testParamsOrder() {
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
    assertEquals(1, results.size());
  }

  // ---- Order 3: depends on testDuplicatedIndexOnUnique (order 1) ----

  @Test
  @Order(6)
  void testUseOfIndex() {
    session.begin();
    var resultSet = executeQuery("select * from Profile where nick = 'Jay'");

    assertFalse(resultSet.isEmpty());

    Entity record;
    for (var entries : resultSet) {
      record = entries.asEntityOrNull();
      assertTrue(record.<String>getProperty("name").equalsIgnoreCase("Jay"));
    }
    session.commit();
  }

  @Test
  @Order(6)
  void testIndexEntries() {

    session.begin();
    var resultSet = executeQuery("select * from Profile where nick is not null");

    var idx =
        session.getSharedContext().getIndexManager().getIndex("Profile.nick");

    assertEquals(resultSet.size(), idx.size(session));
    session.rollback();
  }

  @Test
  @Order(6)
  void testIndexSize() {

    session.begin();
    var resultSet = executeQuery("select * from Profile where nick is not null");
    var profileSize = resultSet.size();
    session.commit();

    session.begin();
    assertEquals(
        profileSize,
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("Profile.nick")

            .size(session));
    session.rollback();
    for (var i = 0; i < 10; i++) {
      session.begin();
      var profile = session.newEntity("Profile");
      profile.setProperty("nick", "Yay-" + i);
      profile.setProperty("name", "Jay");
      profile.setProperty("surname", "Miner");
      session.commit();

      profileSize++;
      session.begin();
      try (var stream =
          session
              .getSharedContext()
              .getIndexManager()
              .getIndex("Profile.nick")

              .getRids(session, "Yay-" + i)) {
        assertTrue(stream.findAny().isPresent());
      }
      session.rollback();
    }
  }

  // ---- Order 3: depends on populateIndexDocuments (order 2) ----

  @Test
  @Order(6)
  void testIndexInUniqueIndex() {
    session.begin();
    assertEquals(
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        session.getMetadata().getSchema().getClassInternal("Profile")
            .getInvolvedIndexesInternal(session, "nick").iterator().next().getType());
    try (var resultSet =
        session.query(
            "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0'"
                + " ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")) {
      assertIndexUsage(resultSet);

      final List<String> expectedSurnames =
          new ArrayList<>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

      var result = resultSet.entityStream().toList();
      assertEquals(3, result.size());
      for (final var profile : result) {
        expectedSurnames.remove(profile.<String>getProperty("surname"));
      }

      assertEquals(0, expectedSurnames.size());
    }
    session.commit();
  }

  @Test
  @Order(6)
  void testIndexInMajorSelect() {
    session.begin();
    try (var resultSet =
        session.query("select * from Profile where nick > 'ZZZJayLongNickIndex3'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks =
          new ArrayList<>(Arrays.asList("ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

      var result = resultSet.entityStream().toList();
      assertEquals(2, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  @Test
  @Order(6)
  void testIndexInMajorEqualsSelect() {
    session.begin();
    try (var resultSet =
        session.query("select * from Profile where nick >= 'ZZZJayLongNickIndex3'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks =
          new ArrayList<>(
              Arrays.asList(
                  "ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

      var result = resultSet.entityStream().toList();
      assertEquals(3, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  @Test
  @Order(6)
  void testIndexInMinorSelect() {
    session.begin();
    try (var resultSet = session.query("select * from Profile where nick < '002'")) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("000", "001"));

      var result = resultSet.entityStream().toList();
      assertEquals(2, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  @Test
  @Order(6)
  void testIndexInMinorEqualsSelect() {
    session.begin();
    try (var resultSet = session.query("select * from Profile where nick <= '002'")) {
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("000", "001", "002"));

      var result = resultSet.entityStream().toList();
      assertEquals(3, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  @Test
  @Order(6)
  @Disabled("Test was historically disabled — needs investigation")
  void testIndexBetweenSelect() {
    var query = "select * from Profile where nick between '001' and '004'";
    try (var resultSet = session.query(query)) {
      assertIndexUsage(resultSet);
      final List<String> expectedNicks =
          new ArrayList<>(Arrays.asList("001", "002", "003", "004"));

      var result = resultSet.entityStream().toList();
      assertEquals(4, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      assertEquals(0, expectedNicks.size());
    }
  }

  @Test
  @Order(6)
  @Disabled("Test was historically disabled — needs investigation")
  void testIndexInComplexSelectOne() {
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
      assertEquals(3, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      assertEquals(0, expectedNicks.size());
    }
  }

  @Test
  @Order(6)
  @Disabled("Test was historically disabled — needs investigation")
  void testIndexInComplexSelectTwo() {
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
      assertEquals(3, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      assertEquals(0, expectedNicks.size());
    }
  }

  // ---- Order 12: depends on linkedIndexedProperty (order 11) ----

  @Test
  @Order(15)
  void testLinkedIndexedPropertyInTx() {
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
      assertEquals(2, result.size());
      // THIS WILL THROW A java.lang.ClassCastException:
      // com.orientechnologies.core.id.RecordId cannot be cast to
      // java.lang.String
      result =
          db.query(
              "select from TestClass where testLink.testString = 'Test Link Class 2'").toList();
      assertEquals(1, result.size());
    }
  }

  @Test
  @Order(15)
  void testIndexRemoval() {

    final var index = getIndex("Profile.nick");

    Object key;
    RID ridToDelete;
    session.begin();
    try (var stream = index.stream(session)) {
      var streamIterator = stream.iterator();
      assertTrue(streamIterator.hasNext());

      var pair = streamIterator.next();
      key = pair.first();
      ridToDelete = pair.second();
    }
    session.rollback();

    session.begin();
    var transaction = session.getActiveTransaction();
    transaction.load(ridToDelete).delete();
    session.commit();

    session.begin();
    try (var stream = index.getRids(session, key)) {
      assertFalse(stream.findAny().isPresent());
    }
    session.rollback();
  }

  // ---- Order 14: depends on createInheritanceIndex (order 13) ----

  @Test
  @Order(17)
  void testIndexReturnOnlySpecifiedClass() {

    session.begin();
    try (var result =
        session.execute("select * from ChildTestClass where testParentProperty = 10")) {

      assertEquals(10L, result.next().<Object>getProperty("testParentProperty"));
      assertFalse(result.hasNext());
    }

    try (var result =
        session.execute("select * from AnotherChildTestClass where testParentProperty = 11")) {
      assertEquals(11L, result.next().<Object>getProperty("testParentProperty"));
      assertFalse(result.hasNext());
    }
    session.commit();
  }

  // ---- Order 16: depends on testTransactionUniqueIndexTestOne (order 15) ----

  @Test
  @Order(19)
  void testTransactionUniqueIndexTestTwo() {

    var session = acquireSession();
    if (!session.getMetadata().getSchema().existsClass("TransactionUniqueIndexTest")) {
      final var termClass =
          session.getMetadata()
              .getSchema()
              .createClass("TransactionUniqueIndexTest", 1);

      termClass.createProperty("label", PropertyType.STRING);
      termClass.createIndex(
          "idxTransactionUniqueIndexTest",
          INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true), new String[] {"label"});
    }
    final var index =
        session.getSharedContext().getIndexManager()
            .getIndex("idxTransactionUniqueIndexTest");
    this.session.begin();
    assertEquals(1, index.size(this.session));
    this.session.rollback();

    session.begin();
    try {
      var docOne = ((EntityImpl) session.newEntity("TransactionUniqueIndexTest"));
      docOne.setProperty("label", "B");

      var docTwo = ((EntityImpl) session.newEntity("TransactionUniqueIndexTest"));
      docTwo.setProperty("label", "B");

      session.commit();
      fail();
    } catch (RecordDuplicatedException oie) {
      session.rollback();
    }

    this.session.begin();
    assertEquals(1, index.size(this.session));
    this.session.rollback();
  }

  // ---- Order 18: depends on testTransactionUniqueIndexTestWithDotNameOne (order 17) ----

  @Test
  @Order(21)
  void testTransactionUniqueIndexTestWithDotNameTwo() {

    var db = acquireSession();
    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexWithDotTest")) {
      final var termClass =
          db.getMetadata()
              .getSchema()
              .createClass("TransactionUniqueIndexWithDotTest", 1);
      termClass.createProperty("label", PropertyType.STRING)
          .createIndex(INDEX_TYPE.UNIQUE);
    }

    final var index =
        db.getSharedContext()
            .getIndexManager()
            .getIndex("TransactionUniqueIndexWithDotTest.label");
    this.session.begin();
    assertEquals(1, index.size(this.session));
    this.session.rollback();

    db.begin();
    try {
      var docOne = ((EntityImpl) db.newEntity("TransactionUniqueIndexWithDotTest"));
      docOne.setProperty("label", "B");

      var docTwo = ((EntityImpl) db.newEntity("TransactionUniqueIndexWithDotTest"));
      docTwo.setProperty("label", "B");

      db.commit();
      fail();
    } catch (RecordDuplicatedException oie) {
      db.rollback();
    }

    this.session.begin();
    assertEquals(1, index.size(this.session));
    this.session.rollback();
  }

  // ---- Order 20: depends on testIndexRebuildDuringNonProxiedObjectDelete (order 19) ----

  @Test
  @Order(3)
  void testIndexRebuildDuringDetachAllNonProxiedObjectDelete() {

    session.begin();
    var profile = session.newEntity("Profile");
    profile.setProperty("nick", "NonProxiedObjectToDelete");
    profile.setProperty("name", "NonProxiedObjectToDelete");
    profile.setProperty("surname", "NonProxiedObjectToDelete");
    session.commit();

    session.begin();
    var idxManager = session.getSharedContext().getIndexManager();
    var nickIndex = idxManager.getIndex("Profile.nick");

    try (var stream = nickIndex
        .getRids(session, "NonProxiedObjectToDelete")) {
      assertTrue(stream.findAny().isPresent());
    }

    final Entity loadedProfile = session.load(profile.getIdentity());
    session.commit();
    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(loadedProfile));
    session.commit();

    session.begin();
    try (var stream = nickIndex
        .getRids(session, "NonProxiedObjectToDelete")) {
      assertFalse(stream.findAny().isPresent());
    }
    session.rollback();
  }

  // ---- Order 4: depends on testUseOfIndex (order 3) ----

  @Test
  @Order(7)
  void testChangeOfIndexToNotUnique() {
    dropIndexes();

    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .createIndex(INDEX_TYPE.NOTUNIQUE);
  }

  // ---- Order 21: depends on testIndexRebuildDuringDetachAllNonProxiedObjectDelete (order 20) ----

  @Test
  @Order(4)
  void testRestoreUniqueIndex() {
    dropIndexes();
    session
        .execute(
            "CREATE INDEX Profile.nick on Profile (nick) UNIQUE METADATA {ignoreNullValues: true}")
        .close();
    session.getMetadata().reload();
  }

  // ---- Order 5: depends on testChangeOfIndexToNotUnique (order 4) ----

  @Test
  @Order(8)
  void testDuplicatedIndexOnNotUnique() {
    session.begin();
    var nickNolte = session.newEntity("Profile");
    nickNolte.setProperty("nick", "Jay");
    nickNolte.setProperty("name", "Nick");
    nickNolte.setProperty("surname", "Nolte");

    session.commit();
  }

  // ---- Order 6: depends on testDuplicatedIndexOnNotUnique (order 5) ----

  @Test
  @Order(9)
  void testChangeOfIndexToUnique() {
    try {
      dropIndexes();
      session
          .getMetadata()
          .getSchema()
          .getClass("Profile")
          .getProperty("nick")
          .createIndex(INDEX_TYPE.UNIQUE);
      fail();
    } catch (RecordDuplicatedException e) {
      assertTrue(true);
    }
  }

  // ---- Order 7: depends on testChangeOfIndexToUnique (order 6) ----

  @Test
  @Order(10)
  void removeNotUniqueIndexOnNick() {
    dropIndexes();
  }

  // ---- Order 8: depends on removeNotUniqueIndexOnNick (order 7) ----

  @Test
  @Order(11)
  void testQueryingWithoutNickIndex() {
    assertFalse(
        session.getMetadata().getSchema().getClassInternal("Profile")
            .getInvolvedIndexes(session, "name").isEmpty());

    assertTrue(
        session.getMetadata().getSchema().getClassInternal("Profile")
            .getInvolvedIndexes(session, "nick")
            .isEmpty());

    var result =
        session
            .query("SELECT FROM Profile WHERE nick = 'Jay'").toList();
    assertEquals(2, result.size());

    result =
        session
            .query(
                "SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Jay'")
            .toList();
    assertEquals(1, result.size());

    result =
        session
            .query(
                "SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Nick'")
            .toList();
    assertEquals(1, result.size());
  }

  // ---- Order 9: depends on testQueryingWithoutNickIndex (order 8) ----

  @Test
  @Order(12)
  void createNotUniqueIndexOnNick() {
    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .createIndex(INDEX_TYPE.NOTUNIQUE);
  }

  // ---- Order 10: depends on createNotUniqueIndexOnNick (order 9) + populateIndexDocuments (order 2) ----

  @Test
  @Order(13)
  void testIndexInNotUniqueIndex() {
    assertEquals(
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        session.getClassInternal("Profile").getInvolvedIndexesInternal(session, "nick").iterator()
            .next().getType());

    session.begin();

    try (var resultSet =
        session.query(
            "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0'"
                + " ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")) {
      final List<String> expectedSurnames =
          new ArrayList<>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

      var result = resultSet.entityStream().toList();
      assertEquals(3, result.size());
      for (final var profile : result) {
        expectedSurnames.remove(profile.<String>getProperty("surname"));
      }

      assertEquals(0, expectedSurnames.size());
    }
    session.commit();
  }

  // ---- Private helper methods ----

  private void dropIndexes() {
    for (var indexName : session.getMetadata().getSchema().getClassInternal("Profile")
        .getPropertyInternal("nick").getAllIndexes()) {
      session.getSharedContext().getIndexManager().dropIndex(session, indexName);
    }
  }

  private static void assertIndexUsage(ResultSet resultSet) {
    var executionPlan = resultSet.getExecutionPlan();
    for (var step : executionPlan.getSteps()) {
      if (assertIndexUsage(step, "Profile.nick")) {
        return;
      }
    }

    fail("Index " + "Profile.nick" + " was not used in the query");
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
