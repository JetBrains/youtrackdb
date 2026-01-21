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
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of IndexTest. Original test class: com.jetbrains.youtrackdb.auto.IndexTest
 * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java
 * <p>
 * Note: This is a large test class with many interdependent tests. Test ordering is maintained via
 * numbered method names.
 */
@SuppressWarnings({"deprecation"})
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class IndexTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    IndexTest instance = new IndexTest();
    instance.beforeClass();
    instance.generateCompanyData();
  }

  /**
   * Original: testDuplicatedIndexOnUnique (line 60)
   */
  @Test
  public void test01_DuplicatedIndexOnUnique() {
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
      Assert.fail();
    } catch (RecordDuplicatedException e) {
      Assert.assertTrue(true);
    }
  }

  /**
   * Original: populateIndexDocuments (line 363) This was called by other tests via
   * dependsOnMethods
   */
  @Test
  public void test02_PopulateIndexDocuments() {
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

  /**
   * Original: testUseOfIndex (line 113) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java Depends on:
   * testDuplicatedIndexOnUnique
   */
  @Test
  public void test03_UseOfIndex() {
    session.begin();
    var resultSet = session.query("select * from Profile where nick = 'Jay'").toList();
    Assert.assertFalse(resultSet.isEmpty());

    for (var entry : resultSet) {
      var record = entry.asEntityOrNull();
      Assert.assertTrue(record.<String>getProperty("name").equalsIgnoreCase("Jay"));
    }
    session.commit();
  }

  /**
   * Original: testIndexEntries (line 128) Depends on: testDuplicatedIndexOnUnique
   */
  @Test
  public void test04_IndexEntries() {
    var index = session.getSharedContext().getIndexManager().getIndex("Profile.nick");
    session.begin();
    try (var stream = index.stream(session)) {
      Assert.assertTrue(stream.count() > 0);
    }
    session.commit();
  }

  /**
   * Original: testIndexSize (line 139) Depends on: testDuplicatedIndexOnUnique
   */
  @Test
  public void test05_IndexSize() {
    var index = session.getSharedContext().getIndexManager().getIndex("Profile.nick");
    session.begin();
    var size = index.size(session);
    Assert.assertTrue(size > 0);
    session.commit();
  }

  /**
   * Original: testChangeOfIndexToNotUnique (line 176) Depends on: testUseOfIndex
   */
  @Test
  public void test06_ChangeOfIndexToNotUnique() {
    dropIndexes();
    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .createIndex(INDEX_TYPE.NOTUNIQUE);
  }

  /**
   * Original: testDuplicatedIndexOnNotUnique (line 195) Depends on: testChangeOfIndexToNotUnique
   */
  @Test
  public void test07_DuplicatedIndexOnNotUnique() {
    session.begin();
    var nickNolte = session.newEntity("Profile");
    nickNolte.setProperty("nick", "Jay");
    nickNolte.setProperty("name", "Nick");
    nickNolte.setProperty("surname", "Nolte");

    session.commit();
  }

  /**
   * Original: testChangeOfIndexToUnique (line 206) Depends on: testDuplicatedIndexOnNotUnique
   */
  @Test
  public void test08_ChangeOfIndexToUnique() {
    dropIndexes();
    try {
      session
          .getMetadata()
          .getSchema()
          .getClass("Profile")
          .getProperty("nick")
          .createIndex(INDEX_TYPE.UNIQUE);
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(true);
    }
  }

  /**
   * Original: removeNotUniqueIndexOnNick (line 383) Depends on: testChangeOfIndexToUnique
   */
  @Test
  public void test09_RemoveNotUniqueIndexOnNick() {
    dropIndexes();
  }

  /**
   * Original: testQueryingWithoutNickIndex (line 388) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java Depends on:
   * removeNotUniqueIndexOnNick
   */
  @Test
  public void test10_QueryingWithoutNickIndex() {
    Assert.assertFalse(
        session.getMetadata().getSchema().getClassInternal("Profile")
            .getInvolvedIndexes(session, "name").isEmpty());

    Assert.assertTrue(
        session.getMetadata().getSchema().getClassInternal("Profile")
            .getInvolvedIndexes(session, "nick")
            .isEmpty());

    var result = session.query("SELECT FROM Profile WHERE nick = 'Jay'").toList();
    Assert.assertEquals(2, result.size());

    result = session.query("SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Jay'").toList();
    Assert.assertEquals(1, result.size());

    result = session.query("SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Nick'").toList();
    Assert.assertEquals(1, result.size());
  }

  /**
   * Original: createNotUniqueIndexOnNick (line 417) Depends on: testQueryingWithoutNickIndex
   */
  @Test
  public void test11_CreateNotUniqueIndexOnNick() {
    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .createIndex(INDEX_TYPE.NOTUNIQUE);
  }

  /**
   * Original: testIndexInNotUniqueIndex (line 427) Depends on: createNotUniqueIndexOnNick,
   * populateIndexDocuments
   */
  @Test
  public void test12_IndexInNotUniqueIndex() {
    Assert.assertEquals(
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        session.getClassInternal("Profile").
            getInvolvedIndexesInternal(session, "nick").iterator()
            .next().getType());

    session.begin();

    try (var resultSet =
        session.query(
            "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0'"
                + " ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")) {
      final List<String> expectedSurnames =
          new ArrayList<>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(3, result.size());
      for (final var profile : result) {
        expectedSurnames.remove(profile.<String>getProperty("surname"));
      }

      Assert.assertEquals(0, expectedSurnames.size());
    }
    session.commit();
  }

  /**
   * Original: testIndexInMajorSelect (line 222) Depends on: populateIndexDocuments
   */
  @Test
  public void test13_IndexInMajorSelect() {
    session.begin();
    try (var resultSet =
        session.query("SELECT * FROM Profile WHERE nick > 'ZZZJayLongNickIndex3'")) {
      assertIndexUsage(resultSet);

      final List<String> expectedNicks =
          new ArrayList<>(Arrays.asList("ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(2, result.size());
      for (final var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  /**
   * Original: testIndexInMajorEqualsSelect (line 242) Depends on: populateIndexDocuments
   */
  @Test
  public void test14_IndexInMajorEqualsSelect() {
    session.begin();
    try (var resultSet =
        session.query("SELECT * FROM Profile WHERE nick >= 'ZZZJayLongNickIndex3'")) {
      assertIndexUsage(resultSet);

      final List<String> expectedNicks =
          new ArrayList<>(
              Arrays.asList(
                  "ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));
      var result = resultSet.entityStream().toList();
      Assert.assertEquals(3, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  /**
   * Original: testIndexInMinorSelect (line 264) Depends on: populateIndexDocuments
   */
  @Test
  public void test15_IndexInMinorSelect() {
    session.begin();
    try (var resultSet = session.query("SELECT * FROM Profile WHERE nick < '001'")) {
      assertIndexUsage(resultSet);

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(1, result.size());

      Assert.assertEquals("000", result.getFirst().getProperty("nick"));
    }
    session.commit();
  }

  /**
   * Original: testIndexInMinorEqualsSelect (line 282) Depends on: populateIndexDocuments
   */
  @Test
  public void test16_IndexInMinorEqualsSelect() {
    session.begin();
    try (var resultSet = session.query("SELECT * FROM Profile WHERE nick <= '001'")) {
      assertIndexUsage(resultSet);

      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("000", "001"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(2, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  /**
   * Original: testIndexBetweenSelect (line 299) Depends on: populateIndexDocuments Note: Original
   * test was disabled (enabled = false)
   */
  @Test
  @Ignore("Original test was disabled")
  public void test17_IndexBetweenSelect() {
    session.begin();
    try (var resultSet =
        session.query("SELECT * FROM Profile WHERE nick between '001' and '002'")) {
      final List<String> expectedNicks = new ArrayList<>(Arrays.asList("001", "002"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(2, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  /**
   * Original: testIndexInComplexSelectOne (line 316) Depends on: populateIndexDocuments Note:
   * Original test was disabled (enabled = false)
   */
  @Test
  @Ignore("Original test was disabled")
  public void test18_IndexInComplexSelectOne() {
    session.begin();
    try (var resultSet =
        session.query(
            "SELECT * FROM Profile WHERE (nick > '001' and nick < '004') or (nick >= '004'"
                + " and nick < '006')")) {
      final List<String> expectedNicks =
          new ArrayList<>(Arrays.asList("002", "003", "004", "005"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(4, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  /**
   * Original: testIndexInComplexSelectTwo (line 340) Depends on: populateIndexDocuments Note:
   * Original test was disabled (enabled = false)
   */
  @Test
  @Ignore("Original test was disabled")
  public void test19_IndexInComplexSelectTwo() {
    session.begin();
    try (var resultSet =
        session.query(
            "SELECT * FROM Profile WHERE (nick > 'ZZZJayLongNickIndex0' and nick <"
                + " 'ZZZJayLongNickIndex3') or (nick >= 'ZZZJayLongNickIndex3' and nick <"
                + " 'ZZZJayLongNickIndex6')")) {
      final List<String> expectedNicks =
          new ArrayList<>(
              Arrays.asList(
                  "ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));
      var result = resultSet.entityStream().toList();
      Assert.assertEquals(3, result.size());
      for (var profile : result) {
        expectedNicks.remove(profile.<String>getProperty("nick"));
      }

      Assert.assertEquals(0, expectedNicks.size());
    }
    session.commit();
  }

  /**
   * Original: indexLinks (line 455) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java
   */
  @Test
  public void test20_IndexLinks() {
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
    var accountIdentity = resultSet.getFirst().asEntityOrNull().getIdentity();

    for (var i = 0; i < 5; i++) {
      var whiz = ((EntityImpl) session.newEntity("Whiz"));
      whiz.setProperty("id", i);
      whiz.setProperty("text", "This is a test");
      whiz.setPropertyInChain("account", accountIdentity);
    }
    session.commit();

    Assert.assertEquals(5, idx.size(session));

    session.begin();
    var indexedResult = executeQuery("select * from Whiz where account = ?", accountIdentity);
    Assert.assertEquals(5, indexedResult.size());

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

  /**
   * Original: linkedIndexedProperty (line 508)
   */
  @Test
  public void test21_LinkedIndexedProperty() {
    if (!session.getMetadata().getSchema().existsClass("TestClass")) {
      var testClass = session.getMetadata().getSchema().createClass("TestClass");
      var propOne = testClass.createProperty("prop1", PropertyType.STRING);
      propOne.createIndex(INDEX_TYPE.NOTUNIQUE);
    }

    if (!session.getMetadata().getSchema().existsClass("TestLinkClass")) {
      var testLinkClass = session.getMetadata().getSchema().createClass("TestLinkClass");
      testLinkClass.createProperty("linkProp", PropertyType.LINK,
          session.getMetadata().getSchema().getClass("TestClass"));
    }

    session.begin();
    var testClassOne = session.newEntity("TestClass");
    testClassOne.setProperty("prop1", "A");

    var testLinkClass = session.newEntity("TestLinkClass");
    testLinkClass.setProperty("linkProp", testClassOne);
    session.commit();

    session.begin();
    var result =
        session.query("select from TestLinkClass where linkProp.prop1 = 'A'").toList();
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original: testLinkedIndexedPropertyInTx (line 549) Depends on: linkedIndexedProperty
   */
  @Test
  public void test22_LinkedIndexedPropertyInTx() {
    session.begin();
    var testClassTwo = session.newEntity("TestClass");
    testClassTwo.setProperty("prop1", "B");

    var testLinkClassTwo = session.newEntity("TestLinkClass");
    testLinkClassTwo.setProperty("linkProp", testClassTwo);
    session.commit();

    session.begin();
    var result =
        session.query("select from TestLinkClass where linkProp.prop1 = 'B'").toList();
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original: testIndexRemoval (line 842) Depends on: linkedIndexedProperty
   */
  @Test
  public void test23_IndexRemoval() {
    session.begin();
    var record = session.newEntity("Profile");
    record.setProperty("nick", "Yaya");
    record.setProperty("name", "Yaya");
    record.setProperty("surname", "Yaya");
    session.commit();

    session.begin();
    Assert.assertEquals(
        1,
        session.query("select count(*) as count from Profile where nick = 'Yaya'").toList()
            .getFirst().<Long>getProperty("count").longValue());
    var activeTx = session.getActiveTransaction();
    record = activeTx.load(record);
    record.delete();
    session.commit();

    session.begin();
    Assert.assertEquals(
        0,
        session.query("select count(*) as count from Profile where nick = 'Yaya'").toList()
            .getFirst().<Long>getProperty("count").longValue());
    session.commit();
  }

  /**
   * Original: testConcurrentRemoveDelete (line 579)
   */
  @Test
  public void test24_ConcurrentRemoveDelete() {
    if (!session.getMetadata().getSchema().existsClass("MyFruit")) {
      var fruitClass = session.getMetadata().getSchema().createClass("MyFruit");
      fruitClass.createProperty("name", PropertyType.STRING);
      fruitClass.createProperty("color", PropertyType.STRING);

      session.getClass("MyFruit").getProperty("name").createIndex(INDEX_TYPE.NOTUNIQUE);
      session.getClass("MyFruit").getProperty("color").createIndex(INDEX_TYPE.NOTUNIQUE);
    }

    var delRecordIdList = new ArrayList<RID>();
    var tot = 10;

    for (var i = 0; i < tot; i++) {
      session.begin();
      var record =
          ((EntityImpl) session.newEntity("MyFruit"))
              .setPropertyInChain("name", "Gipsy")
              .setPropertyInChain("color", "" + (i % 2 == 0 ? "red" : "yellow"));
      session.commit();
      delRecordIdList.add(record.getIdentity());
    }

    var indexedFields = new String[]{"name", "color"};
    var index1Name = "MyFruit.name";
    var index2Name = "MyFruit.color";
    var index1 = session.getSharedContext().getIndexManager().getIndex(index1Name);
    var index2 = session.getSharedContext().getIndexManager().getIndex(index2Name);

    session.begin();
    Assert.assertEquals(tot, index1.size(session));
    Assert.assertEquals(tot, index2.size(session));
    session.commit();

    for (var rid : delRecordIdList) {
      session.begin();
      var activeTx = session.getActiveTransaction();
      var record = activeTx.<EntityImpl>load(rid);
      record.delete();
      session.commit();
      tot--;
      session.begin();
      Assert.assertEquals(tot, index1.size(session));
      Assert.assertEquals(tot, index2.size(session));
      session.commit();
    }
  }

  /**
   * Original: testIndexParamsAutoConversion (line 651)
   */
  @Test
  public void test25_IndexParamsAutoConversion() {
    if (!session.getMetadata().getSchema().existsClass("IndexTestTerm")) {
      var termClass = session.getMetadata().getSchema().createClass("IndexTestTerm");
      termClass.createProperty("label", PropertyType.STRING);
      termClass.createProperty("sublabel", PropertyType.STRING);
      termClass.createIndex("idxTerm", INDEX_TYPE.UNIQUE, "label", "sublabel");
    }

    session.begin();
    var doc = session.newEntity("IndexTestTerm");
    doc.setProperty("label", "testLabel");
    doc.setProperty("sublabel", "testSubLabel");
    session.commit();

    session.begin();
    var result =
        session
            .query("select from IndexTestTerm where label = 'testLabel'")
            .toList();
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original: testTransactionUniqueIndexTestOne (line 686)
   */
  @Test
  public void test26_TransactionUniqueIndexTestOne() {
    if (!session.getMetadata().getSchema().existsClass("TransactionUniqueIndexTest")) {
      var termClass =
          session.getMetadata().getSchema().createClass("TransactionUniqueIndexTest");
      termClass.createProperty("label", PropertyType.STRING);
      termClass.createIndex("idxTransactionUniqueIndexTest", INDEX_TYPE.UNIQUE, "label");
    }

    session.begin();
    var docOne = session.newEntity("TransactionUniqueIndexTest");
    docOne.setProperty("label", "A");

    var docTwo = session.newEntity("TransactionUniqueIndexTest");
    docTwo.setProperty("label", "A");

    try {
      session.commit();
      Assert.fail();
    } catch (RecordDuplicatedException e) {
      session.rollback();
    }

    session.begin();
    Assert.assertEquals(
        0,
        session.query("select from TransactionUniqueIndexTest").stream().count());
    session.commit();
  }

  /**
   * Original: testTransactionUniqueIndexTestTwo (line 725) Depends on:
   * testTransactionUniqueIndexTestOne
   */
  @Test
  public void test27_TransactionUniqueIndexTestTwo() {
    session.begin();
    var docOne = session.newEntity("TransactionUniqueIndexTest");
    docOne.setProperty("label", "B");
    session.commit();

    session.begin();
    var docTwo = session.newEntity("TransactionUniqueIndexTest");
    docTwo.setProperty("label", "B");

    try {
      session.commit();
      Assert.fail();
    } catch (RecordDuplicatedException e) {
      session.rollback();
    }

    session.begin();
    Assert.assertEquals(
        1,
        session.query("select from TransactionUniqueIndexTest").stream().count());
    session.commit();
  }

  /**
   * Original: testTransactionUniqueIndexTestWithDotNameOne (line 764)
   */
  @Test
  public void test28_TransactionUniqueIndexTestWithDotNameOne() {
    if (!session.getMetadata().getSchema().existsClass("TransactionUniqueIndexWithDotTest")) {
      var termClass =
          session.getMetadata().getSchema().createClass("TransactionUniqueIndexWithDotTest");
      termClass.createProperty("label", PropertyType.STRING);
      termClass.createIndex("TransactionUniqueIndexWithDotTest.label",
          INDEX_TYPE.UNIQUE, "label");
    }

    session.begin();
    var docOne = session.newEntity("TransactionUniqueIndexWithDotTest");
    docOne.setProperty("label", "A");

    var docTwo = session.newEntity("TransactionUniqueIndexWithDotTest");
    docTwo.setProperty("label", "A");

    try {
      session.commit();
      Assert.fail();
    } catch (RecordDuplicatedException e) {
      session.rollback();
    }

    session.begin();
    Assert.assertEquals(
        0,
        session.query("select from TransactionUniqueIndexWithDotTest").stream().count());
    session.commit();
  }

  /**
   * Original: testTransactionUniqueIndexTestWithDotNameTwo (line 806) Depends on:
   * testTransactionUniqueIndexTestWithDotNameOne
   */
  @Test
  public void test29_TransactionUniqueIndexTestWithDotNameTwo() {
    session.begin();
    var docOne = session.newEntity("TransactionUniqueIndexWithDotTest");
    docOne.setProperty("label", "B");
    session.commit();

    session.begin();
    var docTwo = session.newEntity("TransactionUniqueIndexWithDotTest");
    docTwo.setProperty("label", "B");

    try {
      session.commit();
      Assert.fail();
    } catch (RecordDuplicatedException e) {
      session.rollback();
    }

    session.begin();
    Assert.assertEquals(
        1,
        session.query("select from TransactionUniqueIndexWithDotTest").stream().count());
    session.commit();
  }

  /**
   * Original: createInheritanceIndex (line 867)
   */
  @Test
  public void test30_CreateInheritanceIndex() {
    if (!session.getMetadata().getSchema().existsClass("BaseTestClass")) {
      var baseClass = session.getMetadata().getSchema().createClass("BaseTestClass");
      baseClass.createProperty("testParentProperty", PropertyType.INTEGER)
          .createIndex(INDEX_TYPE.NOTUNIQUE);
    }

    if (!session.getMetadata().getSchema().existsClass("ChildTestClass")) {
      session.getMetadata().getSchema().createClass("ChildTestClass",
          session.getMetadata().getSchema().getClass("BaseTestClass"));
    }

    if (!session.getMetadata().getSchema().existsClass("AnotherChildTestClass")) {
      session.getMetadata().getSchema().createClass("AnotherChildTestClass",
          session.getMetadata().getSchema().getClass("BaseTestClass"));
    }

    session.begin();
    var childClassDoc = session.newEntity("ChildTestClass");
    childClassDoc.setProperty("testParentProperty", 10);

    var anotherChildClassDoc = session.newEntity("AnotherChildTestClass");
    anotherChildClassDoc.setProperty("testParentProperty", 11);
    session.commit();

    session.begin();
    Assert.assertFalse(
        session.getMetadata().getSchema().getClassInternal("ChildTestClass")
            .getInvolvedIndexes(session, "testParentProperty").isEmpty());
    session.commit();
  }

  /**
   * Original: testIndexReturnOnlySpecifiedClass (line 905) Depends on: createInheritanceIndex
   */
  @Test
  public void test31_IndexReturnOnlySpecifiedClass() {
    session.begin();
    var result = session.query("select from ChildTestClass where testParentProperty = 10")
        .toList();
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original: testNotUniqueIndexKeySize (line 924)
   */
  @Test
  public void test32_NotUniqueIndexKeySize() {
    if (!session.getMetadata().getSchema().existsClass("NotUniqueIndexKeySize")) {
      var termClass = session.getMetadata().getSchema().createClass("NotUniqueIndexKeySize");
      termClass.createProperty("value", PropertyType.STRING);
      termClass.createIndex("NotUniqueIndexKeySize.value", INDEX_TYPE.NOTUNIQUE, "value");
    }

    session.begin();
    session.newEntity("NotUniqueIndexKeySize").setProperty("value", "x");
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager()
        .getIndex("NotUniqueIndexKeySize.value");
    try (var stream = index.stream(session)) {
      Assert.assertEquals(1, stream.map(RawPair::first).distinct().count());
    }
    session.commit();
  }

  /**
   * Original: testNotUniqueIndexSize (line 953)
   */
  @Test
  public void test33_NotUniqueIndexSize() {
    if (!session.getMetadata().getSchema().existsClass("NotUniqueIndexSize")) {
      var termClass = session.getMetadata().getSchema().createClass("NotUniqueIndexSize");
      termClass.createProperty("value", PropertyType.STRING);
      termClass.createIndex("NotUniqueIndexSize.value", INDEX_TYPE.NOTUNIQUE, "value");
    }

    session.begin();
    session.newEntity("NotUniqueIndexSize").setProperty("value", "x");
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager()
        .getIndex("NotUniqueIndexSize.value");
    Assert.assertEquals(1, index.size(session));
    session.commit();
  }

  /**
   * Original: testIndexRebuildDuringNonProxiedObjectDelete (line 976)
   */
  @Test
  public void test34_IndexRebuildDuringNonProxiedObjectDelete() {
    if (!session.getMetadata().getSchema().existsClass("Profile1")) {
      var termClass = session.getMetadata().getSchema().createClass("Profile1");
      termClass.createProperty("name", PropertyType.STRING);
      termClass.createIndex("Profile1.name", INDEX_TYPE.UNIQUE, "name");
    }

    for (var i = 0; i < 5; i++) {
      session.begin();
      var entity = session.newEntity("Profile1");
      entity.setProperty("name", "testIndexRebuildDuringNonProxiedObjectDelete" + i);
      session.commit();
    }

    for (var i = 0; i < 5; i++) {
      session.begin();
      var result =
          session
              .query(
                  "select from Profile1 where name = 'testIndexRebuildDuringNonProxiedObjectDelete"
                      + i
                      + "'")
              .toList();
      Assert.assertEquals(1, result.size());
      Entity entity = session.load(result.getFirst().getIdentity());
      session.delete(entity);
      session.commit();
    }
  }

  /**
   * Original: testIndexRebuildDuringDetachAllNonProxiedObjectDelete (line 1006) Depends on:
   * testIndexRebuildDuringNonProxiedObjectDelete
   */
  @Test
  public void test35_IndexRebuildDuringDetachAllNonProxiedObjectDelete() {
    for (var i = 0; i < 5; i++) {
      session.begin();
      var entity = session.newEntity("Profile1");
      entity.setProperty("name", "testIndexRebuildDuringDetachAllNonProxiedObjectDelete" + i);
      session.commit();
    }

    for (var i = 0; i < 5; i++) {
      session.begin();
      var result =
          session
              .query(
                  "select from Profile1 where name ="
                      + " 'testIndexRebuildDuringDetachAllNonProxiedObjectDelete"
                      + i
                      + "'")
              .toList();
      Assert.assertEquals(1, result.size());
      Entity entity = session.load(result.getFirst().getIdentity());
      session.delete(entity);
      session.commit();
    }
  }

  /**
   * Original: testRestoreUniqueIndex (line 1039) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java Depends on:
   * testIndexRebuildDuringDetachAllNonProxiedObjectDelete
   * <p>
   * Note: This test needs to delete duplicate 'Jay' records first since they were created in
   * test07_DuplicatedIndexOnNotUnique when the index was NOTUNIQUE.
   */
  @Test
  public void test36_RestoreUniqueIndex() {
    dropIndexes();

    // Delete duplicate 'Jay' records to allow UNIQUE index creation
    session.begin();
    var duplicates = session.query("SELECT FROM Profile WHERE nick = 'Jay'").toList();
    if (duplicates.size() > 1) {
      // Keep the first one, delete the rest
      for (int i = 1; i < duplicates.size(); i++) {
        Entity entity = session.load(duplicates.get(i).getIdentity());
        session.delete(entity);
      }
    }
    session.commit();

    session
        .execute(
            "CREATE INDEX Profile.nick on Profile (nick) UNIQUE METADATA {ignoreNullValues: true}")
        .close();
    session.getMetadata().reload();
  }

  /**
   * Original: testIndexInCompositeQuery (line 1049) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java
   */
  @Test
  public void test37_IndexInCompositeQuery() {
    if (!session.getMetadata().getSchema().existsClass("CompoundSQLIndexTest1")) {
      var classOne = session.getMetadata().getSchema().createClass("CompoundSQLIndexTest1");
      var classTwo = session.getMetadata().getSchema().createClass("CompoundSQLIndexTest2");
      classTwo.createProperty("address", PropertyType.LINK, classOne);
      classTwo.createIndex("CompoundSQLIndexTestIndex", INDEX_TYPE.UNIQUE, "address");
    }

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
    Assert.assertEquals(1, result.size());
    Assert.assertEquals(result.getFirst().getIdentity(), docTwo.getIdentity());
  }

  /**
   * Original: testIndexWithLimitAndOffset (line 1079)
   */
  @Test
  public void test38_IndexWithLimitAndOffset() {
    if (!session.getMetadata().getSchema().existsClass("IndexWithLimitAndOffset")) {
      var clazz = session.getMetadata().getSchema().createClass("IndexWithLimitAndOffset");
      clazz.createProperty("name", PropertyType.STRING);
      clazz.createIndex("IndexWithLimitAndOffsetIndex", INDEX_TYPE.NOTUNIQUE, "name");
    }

    for (var i = 0; i < 20; i++) {
      session.begin();
      var doc = session.newEntity("IndexWithLimitAndOffset");
      doc.setProperty("name", "testName");
      session.commit();
    }

    session.begin();
    var result =
        session.query("select from IndexWithLimitAndOffset where name = 'testName'").toList();
    Assert.assertEquals(20, result.size());

    result =
        session
            .query("select from IndexWithLimitAndOffset where name = 'testName' limit 10")
            .toList();
    Assert.assertEquals(10, result.size());

    result =
        session
            .query(
                "select from IndexWithLimitAndOffset where name = 'testName' skip 10 limit 10")
            .toList();
    Assert.assertEquals(10, result.size());
    session.commit();
  }

  /**
   * Original: testNullIndexKeysSupport (line 1113) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java
   */
  @Test
  public void test39_NullIndexKeysSupport() {
    if (!session.getMetadata().getSchema().existsClass("NullIndexKeysSupport")) {
      var clazz = session.getMetadata().getSchema().createClass("NullIndexKeysSupport");
      clazz.createProperty("nullField", PropertyType.STRING);
      var metadata = Map.<String, Object>of("ignoreNullValues", false);
      clazz.createIndex("NullIndexKeysSupportIndex", INDEX_TYPE.NOTUNIQUE.toString(),
          null, metadata, new String[]{"nullField"});
    }

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
    var resultSet = executeQuery("select from NullIndexKeysSupport where nullField = 'val3'");
    Assert.assertEquals(1, resultSet.size());
    Assert.assertEquals("val3", resultSet.getFirst().getProperty("nullField"));

    resultSet = executeQuery("select from NullIndexKeysSupport where nullField is null");
    Assert.assertEquals(4, resultSet.size());
    for (var result : resultSet) {
      Assert.assertNull(result.getProperty("nullField"));
    }
    session.commit();
  }

  /**
   * Original: testNullHashIndexKeysSupport (line 1156) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java
   */
  @Test
  public void test40_NullHashIndexKeysSupport() {
    if (!session.getMetadata().getSchema().existsClass("NullHashIndexKeysSupport")) {
      var clazz = session.getMetadata().getSchema().createClass("NullHashIndexKeysSupport");
      clazz.createProperty("nullField", PropertyType.STRING);
      var metadata = Map.<String, Object>of("ignoreNullValues", false);
      clazz.createIndex("NullHashIndexKeysSupportIndex", INDEX_TYPE.NOTUNIQUE.toString(),
          null, metadata, new String[]{"nullField"});
    }

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
    var result = session.query("select from NullHashIndexKeysSupport where nullField = 'val3'")
        .toList();
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("val3", result.getFirst().getProperty("nullField"));

    result = session.query("select from NullHashIndexKeysSupport where nullField is null")
        .toList();
    Assert.assertEquals(4, result.size());
    for (var document : result) {
      Assert.assertNull(document.getProperty("nullField"));
    }
    session.commit();
  }

  /**
   * Original: testNullIndexKeysSupportInTx (line 1203) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java
   */
  @Test
  public void test41_NullIndexKeysSupportInTx() {
    if (!session.getMetadata().getSchema().existsClass("NullIndexKeysSupportInTx")) {
      var clazz = session.getMetadata().getSchema().createClass("NullIndexKeysSupportInTx");
      clazz.createProperty("nullField", PropertyType.STRING);
      var metadata = Map.<String, Object>of("ignoreNullValues", false);
      clazz.createIndex("NullIndexKeysSupportInTxIndex", INDEX_TYPE.NOTUNIQUE.toString(),
          null, metadata, new String[]{"nullField"});
    }

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
    var result = session.query("select from NullIndexKeysSupportInTx where nullField = 'val3'")
        .toList();
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("val3", result.getFirst().getProperty("nullField"));

    result = session.query("select from NullIndexKeysSupportInTx where nullField is null")
        .toList();
    Assert.assertEquals(4, result.size());
    for (var document : result) {
      Assert.assertNull(document.getProperty("nullField"));
    }
    session.commit();
  }

  /**
   * Original: testNullIndexKeysSupportInMiddleTx (line 1252) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java
   */
  @Test
  public void test42_NullIndexKeysSupportInMiddleTx() {
    if (!session.getMetadata().getSchema().existsClass("NullIndexKeysSupportInMiddleTx")) {
      var clazz = session.getMetadata().getSchema().createClass("NullIndexKeysSupportInMiddleTx");
      clazz.createProperty("nullField", PropertyType.STRING);
      var metadata = Map.<String, Object>of("ignoreNullValues", false);
      clazz.createIndex("NullIndexKeysSupportInMiddleTxIndex", INDEX_TYPE.NOTUNIQUE.toString(),
          null, metadata, new String[]{"nullField"});
    }

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

    // Query within the transaction before commit
    var result = session.query(
        "select from NullIndexKeysSupportInMiddleTx where nullField = 'val3'").toList();
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("val3", result.getFirst().getProperty("nullField"));

    result = session.query(
        "select from NullIndexKeysSupportInMiddleTx where nullField is null").toList();
    Assert.assertEquals(4, result.size());
    for (var document : result) {
      Assert.assertNull(document.getProperty("nullField"));
    }

    session.commit();
  }

  /**
   * Original: testCreateIndexAbstractClass (line 1301)
   */
  @Test
  public void test43_CreateIndexAbstractClass() {
    if (!session.getMetadata().getSchema().existsClass("TestCreateIndexAbstractClass")) {
      var clazz = session.getMetadata().getSchema()
          .createAbstractClass("TestCreateIndexAbstractClass");
      clazz.createProperty("value", PropertyType.STRING);
      clazz.createIndex("TestCreateIndexAbstractClassIndex", INDEX_TYPE.NOTUNIQUE, "value");
    }

    if (!session.getMetadata().getSchema()
        .existsClass("TestCreateIndexAbstractClassChildOne")) {
      session.getMetadata().getSchema().createClass("TestCreateIndexAbstractClassChildOne",
          session.getMetadata().getSchema().getClass("TestCreateIndexAbstractClass"));
    }

    if (!session.getMetadata().getSchema()
        .existsClass("TestCreateIndexAbstractClassChildTwo")) {
      session.getMetadata().getSchema().createClass("TestCreateIndexAbstractClassChildTwo",
          session.getMetadata().getSchema().getClass("TestCreateIndexAbstractClass"));
    }

    session.begin();
    var doc1 = session.newEntity("TestCreateIndexAbstractClassChildOne");
    doc1.setProperty("value", "val1");

    var doc2 = session.newEntity("TestCreateIndexAbstractClassChildTwo");
    doc2.setProperty("value", "val2");
    session.commit();

    session.begin();
    var result =
        session.query("select from TestCreateIndexAbstractClass where value = 'val1'")
            .toList();
    Assert.assertEquals(1, result.size());

    result =
        session.query("select from TestCreateIndexAbstractClass where value = 'val2'")
            .toList();
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original: testValuesContainerIsRemovedIfIndexIsRemoved (line 1345)
   */
  @Test
  public void test44_ValuesContainerIsRemovedIfIndexIsRemoved() {
    if (!session.getMetadata().getSchema()
        .existsClass("ValuesContainerIsRemovedIfIndexIsRemovedClass")) {
      var clazz = session.getMetadata().getSchema()
          .createClass("ValuesContainerIsRemovedIfIndexIsRemovedClass");
      clazz.createProperty("val", PropertyType.STRING);
    }

    session.execute(
            "create index ValuesContainerIsRemovedIfIndexIsRemovedIndex on"
                + " ValuesContainerIsRemovedIfIndexIsRemovedClass (val) NOTUNIQUE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      var doc = session.newEntity("ValuesContainerIsRemovedIfIndexIsRemovedClass");
      doc.setProperty("val", "value");
      session.commit();
    }

    session.execute("drop index ValuesContainerIsRemovedIfIndexIsRemovedIndex").close();
  }

  /**
   * Original: testPreservingIdentityInIndexTx (line 1377)
   */
  @Test
  public void test45_PreservingIdentityInIndexTx() {
    if (!session.getMetadata().getSchema().existsClass("PreservingIdentityInIndexTxClass")) {
      var clazz = session.getMetadata().getSchema()
          .createClass("PreservingIdentityInIndexTxClass");
      clazz.createProperty("key", PropertyType.STRING);
      clazz.createProperty("value", PropertyType.INTEGER);
      clazz.createIndex("PreservingIdentityInIndexTxClassIndex", INDEX_TYPE.NOTUNIQUE, "key");
    }

    session.begin();
    var doc = session.newEntity("PreservingIdentityInIndexTxClass");
    doc.setProperty("key", "a");
    doc.setProperty("value", 1);
    session.commit();

    session.begin();
    var result =
        session.query("select from PreservingIdentityInIndexTxClass where key = 'a'")
            .toList();
    var entity = result.getFirst().asEntityOrNull();
    Assert.assertNotNull(entity);
    entity.setProperty("value", 2);
    session.commit();

    session.begin();
    result =
        session.query("select from PreservingIdentityInIndexTxClass where key = 'a'")
            .toList();
    Assert.assertEquals(2, ((Number) result.getFirst().getProperty("value")).intValue());
    session.commit();
  }

  /**
   * Original: testEmptyNotUniqueIndex (line 1446)
   */
  @Test
  public void test46_EmptyNotUniqueIndex() {
    if (!session.getMetadata().getSchema().existsClass("EmptyNotUniqueIndexTest")) {
      var clazz = session.getMetadata().getSchema().createClass("EmptyNotUniqueIndexTest");
      clazz.createProperty("key", PropertyType.STRING);
      clazz.createIndex("EmptyNotUniqueIndexTestIndex", INDEX_TYPE.NOTUNIQUE, "key");
    }

    session.begin();
    var doc = session.newEntity("EmptyNotUniqueIndexTest");
    doc.setProperty("key", "test1");
    session.commit();

    session.begin();
    var result =
        session.query("select from EmptyNotUniqueIndexTest where key = 'test1'").toList();
    Assert.assertEquals(1, result.size());

    var activeTx = session.getActiveTransaction();
    activeTx.<Entity>load(result.getFirst().getIdentity()).delete();
    session.commit();

    session.begin();
    result = session.query("select from EmptyNotUniqueIndexTest where key = 'test1'").toList();
    Assert.assertEquals(0, result.size());
    session.commit();
  }

  /**
   * Original: testNullIteration (line 1483) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTest.java
   */
  @Test
  public void test47_NullIteration() {
    var v = session.getSchema().getClass("V");
    if (!session.getMetadata().getSchema().existsClass("NullIterationTest")) {
      var testNullIteration = session.getMetadata().getSchema().createClass("NullIterationTest", v);
      testNullIteration.createProperty("name", PropertyType.STRING);
      testNullIteration.createProperty("birth", PropertyType.DATETIME);
    }

    session.begin();
    session.execute("CREATE VERTEX NullIterationTest SET name = 'Andrew', birth = sysdate()")
        .close();
    session.execute("CREATE VERTEX NullIterationTest SET name = 'Marcel', birth = sysdate()")
        .close();
    session.execute("CREATE VERTEX NullIterationTest SET name = 'Olivier'").close();
    session.commit();

    if (session.getSharedContext().getIndexManager().getIndex("NullIterationTestIndex") == null) {
      var metadata = Map.<String, Object>of("ignoreNullValues", false);
      session.getMetadata().getSchema().getClass("NullIterationTest").createIndex(
          "NullIterationTestIndex", INDEX_TYPE.NOTUNIQUE.name(), null, metadata,
          new String[]{"birth"});
    }

    var result = session.query("SELECT FROM NullIterationTest ORDER BY birth ASC");
    Assert.assertEquals(3, result.stream().count());

    result = session.query("SELECT FROM NullIterationTest ORDER BY birth DESC");
    Assert.assertEquals(3, result.stream().count());

    result = session.query("SELECT FROM NullIterationTest");
    Assert.assertEquals(3, result.stream().count());
  }

  /**
   * Original: testMultikeyWithoutFieldAndNullSupport (line 1518)
   */
  @Test
  public void test48_MultikeyWithoutFieldAndNullSupport() {
    if (!session.getMetadata().getSchema()
        .existsClass("MultikeyWithoutFieldAndNullSupportTest")) {
      var clazz = session.getMetadata().getSchema()
          .createClass("MultikeyWithoutFieldAndNullSupportTest");
      clazz.createProperty("first", PropertyType.STRING);
      clazz.createProperty("second", PropertyType.STRING);
      clazz.createIndex("MultikeyWithoutFieldAndNullSupportTestIndex", INDEX_TYPE.NOTUNIQUE,
          "first", "second");
    }

    session.begin();
    var entity1 = session.newEntity("MultikeyWithoutFieldAndNullSupportTest");
    entity1.setProperty("first", "a");
    entity1.setProperty("second", "1");
    var entity2 = session.newEntity("MultikeyWithoutFieldAndNullSupportTest");
    entity2.setProperty("first", null);
    entity2.setProperty("second", "2");
    var entity3 = session.newEntity("MultikeyWithoutFieldAndNullSupportTest");
    entity3.setProperty("first", "c");
    entity3.setProperty("second", null);
    session.commit();

    session.begin();
    var result =
        session
            .query("select from MultikeyWithoutFieldAndNullSupportTest where first = 'a'")
            .toList();
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original: testMultikeyWithoutFieldAndNoNullSupport (line 1727)
   */
  @Test
  public void test49_MultikeyWithoutFieldAndNoNullSupport() {
    if (!session.getMetadata().getSchema()
        .existsClass("MultikeyWithoutFieldAndNoNullSupportTest")) {
      var clazz = session.getMetadata().getSchema()
          .createClass("MultikeyWithoutFieldAndNoNullSupportTest");
      clazz.createProperty("first", PropertyType.STRING);
      clazz.createProperty("second", PropertyType.STRING);
      clazz.createIndex("MultikeyWithoutFieldAndNoNullSupportTestIndex", INDEX_TYPE.NOTUNIQUE,
          "first", "second");
    }

    session.begin();
    var entity = session.newEntity("MultikeyWithoutFieldAndNoNullSupportTest");
    entity.setProperty("first", "a");
    entity.setProperty("second", "1");
    session.commit();

    session.begin();
    var result =
        session
            .query("select from MultikeyWithoutFieldAndNoNullSupportTest where first = 'a'")
            .toList();
    Assert.assertEquals(1, result.size());
    session.commit();
  }

  /**
   * Original: testNullValuesCountSBTreeUnique (line 1924)
   */
  @Test
  public void test50_NullValuesCountSBTreeUnique() {
    if (!session.getMetadata().getSchema().existsClass("NullValuesCountSBTreeUnique")) {
      var clazz = session.getSchema().createClass("NullValuesCountSBTreeUnique");
      clazz.createProperty("field", PropertyType.INTEGER);
      clazz.createIndex("NullValuesCountSBTreeUniqueIndex", INDEX_TYPE.UNIQUE, "field");
    }

    session.begin();
    var docOne = session.newEntity("NullValuesCountSBTreeUnique");
    docOne.setProperty("field", 1);

    var docTwo = session.newEntity("NullValuesCountSBTreeUnique");
    docTwo.setProperty("field", null);
    session.commit();

    var index = session.getSharedContext().getIndexManager()
        .getIndex("NullValuesCountSBTreeUniqueIndex");
    Assert.assertEquals(2, index.size(session));
  }

  /**
   * Original: testNullValuesCountSBTreeNotUniqueOne (line 1954)
   */
  @Test
  public void test51_NullValuesCountSBTreeNotUniqueOne() {
    if (!session.getMetadata().getSchema().existsClass("NullValuesCountSBTreeNotUniqueOne")) {
      var clazz = session.getSchema().createClass("NullValuesCountSBTreeNotUniqueOne");
      clazz.createProperty("field", PropertyType.INTEGER);
      clazz.createIndex("NullValuesCountSBTreeNotUniqueOneIndex", INDEX_TYPE.NOTUNIQUE,
          "field");
    }

    session.begin();
    var docOne = session.newEntity("NullValuesCountSBTreeNotUniqueOne");
    docOne.setProperty("field", 1);

    var docTwo = session.newEntity("NullValuesCountSBTreeNotUniqueOne");
    docTwo.setProperty("field", null);
    session.commit();

    var index = session.getSharedContext().getIndexManager()
        .getIndex("NullValuesCountSBTreeNotUniqueOneIndex");
    Assert.assertEquals(2, index.size(session));
  }

  /**
   * Original: testNullValuesCountSBTreeNotUniqueTwo (line 1985)
   */
  @Test
  public void test52_NullValuesCountSBTreeNotUniqueTwo() {
    if (!session.getMetadata().getSchema().existsClass("NullValuesCountSBTreeNotUniqueTwo")) {
      var clazz = session.getMetadata().getSchema()
          .createClass("NullValuesCountSBTreeNotUniqueTwo");
      clazz.createProperty("field", PropertyType.INTEGER);
      clazz.createIndex("NullValuesCountSBTreeNotUniqueTwoIndex", INDEX_TYPE.NOTUNIQUE,
          "field");
    }

    session.begin();
    var docOne = session.newEntity("NullValuesCountSBTreeNotUniqueTwo");
    docOne.setProperty("field", null);

    var docTwo = session.newEntity("NullValuesCountSBTreeNotUniqueTwo");
    docTwo.setProperty("field", null);
    session.commit();

    var index = session.getSharedContext().getIndexManager()
        .getIndex("NullValuesCountSBTreeNotUniqueTwoIndex");
    Assert.assertEquals(2, index.size(session));
  }

  /**
   * Original: testNullValuesCountHashUnique (line 2018)
   */
  @Test
  public void test53_NullValuesCountHashUnique() {
    if (!session.getMetadata().getSchema().existsClass("NullValuesCountHashUnique")) {
      var clazz = session.getSchema().createClass("NullValuesCountHashUnique");
      clazz.createProperty("field", PropertyType.INTEGER);
      clazz.createIndex("NullValuesCountHashUniqueIndex", INDEX_TYPE.UNIQUE, "field");
    }

    session.begin();
    var docOne = session.newEntity("NullValuesCountHashUnique");
    docOne.setProperty("field", 1);

    var docTwo = session.newEntity("NullValuesCountHashUnique");
    docTwo.setProperty("field", null);
    session.commit();

    var index = session.getSharedContext().getIndexManager()
        .getIndex("NullValuesCountHashUniqueIndex");
    Assert.assertEquals(2, index.size(session));
  }

  /**
   * Original: testNullValuesCountHashNotUniqueOne (line 2047)
   */
  @Test
  public void test54_NullValuesCountHashNotUniqueOne() {
    if (!session.getMetadata().getSchema().existsClass("NullValuesCountHashNotUniqueOne")) {
      var clazz = session.getSchema().createClass("NullValuesCountHashNotUniqueOne");
      clazz.createProperty("field", PropertyType.INTEGER);
      clazz.createIndex("NullValuesCountHashNotUniqueOneIndex", INDEX_TYPE.NOTUNIQUE, "field");
    }

    session.begin();
    var docOne = session.newEntity("NullValuesCountHashNotUniqueOne");
    docOne.setProperty("field", 1);

    var docTwo = session.newEntity("NullValuesCountHashNotUniqueOne");
    docTwo.setProperty("field", null);
    session.commit();

    var index = session.getSharedContext().getIndexManager()
        .getIndex("NullValuesCountHashNotUniqueOneIndex");
    Assert.assertEquals(2, index.size(session));
  }

  /**
   * Original: testNullValuesCountHashNotUniqueTwo (line 2078)
   */
  @Test
  public void test55_NullValuesCountHashNotUniqueTwo() {
    if (!session.getMetadata().getSchema().existsClass("NullValuesCountHashNotUniqueTwo")) {
      var clazz = session.getMetadata().getSchema()
          .createClass("NullValuesCountHashNotUniqueTwo");
      clazz.createProperty("field", PropertyType.INTEGER);
      clazz.createIndex("NullValuesCountHashNotUniqueTwoIndex", INDEX_TYPE.NOTUNIQUE, "field");
    }

    session.begin();
    var docOne = session.newEntity("NullValuesCountHashNotUniqueTwo");
    docOne.setProperty("field", null);

    var docTwo = session.newEntity("NullValuesCountHashNotUniqueTwo");
    docTwo.setProperty("field", null);
    session.commit();

    var index = session.getSharedContext().getIndexManager()
        .getIndex("NullValuesCountHashNotUniqueTwoIndex");
    Assert.assertEquals(2, index.size(session));
  }

  /**
   * Original: testParamsOrder (line 2111)
   */
  @Test
  public void test56_ParamsOrder() {
    if (!session.getMetadata().getSchema().existsClass("Task")) {
      session.execute("CREATE CLASS Task extends V").close();
      session
          .execute("CREATE PROPERTY Task.projectId STRING (MANDATORY TRUE, NOTNULL, MAX 20)")
          .close();
      session.execute("CREATE PROPERTY Task.seq SHORT ( MANDATORY TRUE, NOTNULL, MIN 0)")
          .close();
      session.execute("CREATE INDEX TaskPK ON Task (projectId, seq) UNIQUE").close();
    }

    session.begin();
    session.execute("INSERT INTO Task (projectId, seq) values ( 'foo', 2)").close();
    session.execute("INSERT INTO Task (projectId, seq) values ( 'bar', 3)").close();
    session.commit();

    var results = session
        .query("select from Task where projectId = 'foo' and seq = 2")
        .vertexStream()
        .toList();
    Assert.assertEquals(1, results.size());
  }

  /**
   * Original: testIndexInUniqueIndex (line 87) This test depends on populateIndexDocuments but with
   * NOTUNIQUE index created later
   */
  @Test
  public void test57_IndexInUniqueIndex() {
    session.begin();
    try (var resultSet =
        session.query(
            "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0'"
                + " ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")) {
      final List<String> expectedSurnames =
          new ArrayList<>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

      var result = resultSet.entityStream().toList();
      Assert.assertEquals(3, result.size());
      for (final var profile : result) {
        expectedSurnames.remove(profile.<String>getProperty("surname"));
      }

      Assert.assertEquals(0, expectedSurnames.size());
    }
    session.commit();
  }

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
    Assert.fail("Index Profile.nick was not used in the query");
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
