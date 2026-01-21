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

import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal.ATTRIBUTES_INTERNAL;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityComparator;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of CRUDDocumentValidationTest. Original test class:
 * com.jetbrains.youtrackdb.auto.CRUDDocumentValidationTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CRUDDocumentValidationTest extends BaseDBTest {

  private EntityImpl record;
  // Static to persist across test method invocations (JUnit creates new instance per test)
  private static RID accountRid;

  /**
   * Original test method: openDb Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:36
   */
  @Test
  public void test01_OpenDb() {
    session.begin();
    var account = ((EntityImpl) session.newEntity("Account"));

    account.setProperty("id", "1234567890");
    session.commit();
    accountRid = account.getIdentity();
  }

  /**
   * Original test method: validationMandatory Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:77 Depends
   * on: openDb
   */
  @Test(expected = ValidationException.class)
  public void test02_ValidationMandatory() {
    session.begin();
    record = session.newInstance("Whiz");

    session.commit();
  }

  /**
   * Original test method: validationMinString Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:85 Depends
   * on: validationMandatory
   */
  @Test(expected = ValidationException.class)
  public void test03_ValidationMinString() {
    session.begin();
    record = session.newInstance("Whiz");
    var activeTx = session.getActiveTransaction();
    var account = activeTx.<EntityImpl>load(accountRid);
    record.setProperty("account", account);
    record.setProperty("id", 23723);
    record.setProperty("text", "");

    session.commit();
  }

  /**
   * Original test method: validationMaxString Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:98 Depends
   * on: validationMinString Original expectedExceptionsMessageRegExp: (?s).*more.*than.*
   */
  @Test(expected = ValidationException.class)
  public void test04_ValidationMaxString() {
    session.begin();
    record = session.newInstance("Whiz");
    var activeTx = session.getActiveTransaction();
    var account = activeTx.<EntityImpl>load(accountRid);
    record.setProperty("account", account);
    record.setProperty("id", 23723);
    record.setProperty(
        "text",
        "clfdkkjsd hfsdkjhf fjdkghjkfdhgjdfh gfdgjfdkhgfd skdjaksdjf skdjf sdkjfsd jfkldjfkjsdf"
            + " kljdk fsdjf kldjgjdhjg khfdjgk hfjdg hjdfhgjkfhdgj kfhdjghrjg");

    session.commit();
  }

  /**
   * Original test method: validationMinDate Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:117 Depends
   * on: validationMaxString Original expectedExceptionsMessageRegExp: (?s).*precedes.*
   */
  @Test(expected = ValidationException.class)
  public void test05_ValidationMinDate() throws ParseException {
    session.begin();
    record = session.newInstance("Whiz");
    var activeTx = session.getActiveTransaction();
    var account = activeTx.<EntityImpl>load(accountRid);
    record.setProperty("account", account);
    record.setPropertyInChain("date", new SimpleDateFormat("dd/MM/yyyy").parse("01/33/1976"));
    record.setProperty("text", "test");

    session.commit();
  }

  /**
   * Original test method: validationEmbeddedType Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:133 Depends
   * on: validationMinDate
   */
  @Test(expected = IllegalArgumentException.class)
  public void test06_ValidationEmbeddedType() {
    session.begin();
    record = session.newInstance("Whiz");
    record.setPropertyInChain("account", session.getCurrentUser());

    session.commit();
  }

  /**
   * Original test method: validationStrictClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:142 Depends
   * on: validationEmbeddedType
   */
  @Test(expected = ValidationException.class)
  public void test07_ValidationStrictClass() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("StrictTest"));
    doc.setProperty("id", 122112);
    doc.setProperty("antani", "122112");

    session.commit();
  }

  /**
   * Original test method: closeDb Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:154 Depends
   * on: validationStrictClass
   */
  @Test
  public void test08_CloseDb() {
    session.close();
  }

  /**
   * Original test method: createSchemaForMandatoryNullableTest Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:159 Depends
   * on: closeDb
   */
  @Test
  public void test09_CreateSchemaForMandatoryNullableTest() {
    if (session.getMetadata().getSchema().existsClass("MyTestClass")) {
      session.getMetadata().getSchema().dropClass("MyTestClass");
    }

    session.execute("CREATE CLASS MyTestClass").close();
    session.execute("CREATE PROPERTY MyTestClass.keyField STRING").close();
    session.execute("ALTER PROPERTY MyTestClass.keyField MANDATORY true").close();
    session.execute("ALTER PROPERTY MyTestClass.keyField NOTNULL true").close();
    session.execute("CREATE PROPERTY MyTestClass.dateTimeField DATETIME").close();
    session.execute("ALTER PROPERTY MyTestClass.dateTimeField MANDATORY true").close();
    session.execute("ALTER PROPERTY MyTestClass.dateTimeField NOTNULL false").close();
    session.execute("CREATE PROPERTY MyTestClass.stringField STRING").close();
    session.execute("ALTER PROPERTY MyTestClass.stringField MANDATORY true").close();
    session.execute("ALTER PROPERTY MyTestClass.stringField NOTNULL false").close();

    session.begin();
    session
        .execute(
            "INSERT INTO MyTestClass (keyField,dateTimeField,stringField) VALUES"
                + " (\"K1\",null,null)")
        .close();
    session.commit();
    session.reload();
    session.getMetadata().reload();
    session.close();
    session = acquireSession();

    session.begin();
    var result =
        session.query("SELECT FROM MyTestClass WHERE keyField = ?", "K1").stream().toList();
    Assert.assertEquals(1, result.size());
    var doc = result.getFirst();
    Assert.assertTrue(doc.hasProperty("keyField"));
    Assert.assertTrue(doc.hasProperty("dateTimeField"));
    Assert.assertTrue(doc.hasProperty("stringField"));
    session.commit();
  }

  /**
   * Original test method: testUpdateDocDefined Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:199 Depends
   * on: createSchemaForMandatoryNullableTest
   */
  @Test
  public void test10_TestUpdateDocDefined() {
    session.begin();
    var result =
        session.query("SELECT FROM MyTestClass WHERE keyField = ?", "K1").stream().toList();
    Assert.assertEquals(1, result.size());
    var readDoc = result.getFirst().asEntityOrNull();
    assert readDoc != null;
    readDoc.setProperty("keyField", "K1N");
    session.commit();
  }

  /**
   * Original test method: validationMandatoryNullableCloseDb Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:211 Depends
   * on: testUpdateDocDefined
   */
  @Test
  public void test11_ValidationMandatoryNullableCloseDb() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("MyTestClass"));
    doc.setProperty("keyField", "K2");
    doc.setProperty("dateTimeField", null);
    doc.setProperty("stringField", null);

    session.commit();

    session.close();
    session = acquireSession();

    session.begin();
    var result =
        session.query("SELECT FROM MyTestClass WHERE keyField = ?", "K2").stream().toList();
    Assert.assertEquals(1, result.size());
    var readDoc = result.getFirst().asEntityOrNull();
    assert readDoc != null;
    readDoc.setProperty("keyField", "K2N");
    session.commit();
  }

  /**
   * Original test method: validationMandatoryNullableNoCloseDb Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:234 Depends
   * on: validationMandatoryNullableCloseDb
   */
  @Test
  public void test12_ValidationMandatoryNullableNoCloseDb() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("MyTestClass"));
    doc.setProperty("keyField", "K3");
    doc.setProperty("dateTimeField", null);
    doc.setProperty("stringField", null);

    session.commit();

    session.begin();
    var result =
        session.query("SELECT FROM MyTestClass WHERE keyField = ?", "K3").stream().toList();
    Assert.assertEquals(1, result.size());
    var readDoc = result.getFirst().asEntityOrNull();
    assert readDoc != null;
    readDoc.setProperty("keyField", "K3N");
    session.commit();
  }

  /**
   * Original test method: validationDisabledAdDatabaseLevel Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:45 Depends
   * on: validationMandatoryNullableNoCloseDb
   */
  @Test
  public void test13_ValidationDisabledAdDatabaseLevel() {
    session.getMetadata().reload();
    try {
      session.begin();
      var entity = ((EntityImpl) session.newEntity("MyTestClass"));
      session.commit();
      Assert.fail();
    } catch (ValidationException ignored) {
    }

    session
        .execute("ALTER DATABASE " + ATTRIBUTES_INTERNAL.VALIDATION.name() + " FALSE")
        .close();
    try {
      session.begin();
      var doc = ((EntityImpl) session.newEntity("MyTestClass"));
      session.commit();

      session.begin();
      var activeTx = session.getActiveTransaction();
      activeTx.<EntityImpl>load(doc).delete();
      session.commit();
    } finally {
      session.setValidationEnabled(true);
      session
          .execute("ALTER DATABASE " + DatabaseSessionInternal.ATTRIBUTES_INTERNAL.VALIDATION.name()
              + " TRUE")
          .close();
    }
  }

  /**
   * Original test method: dropSchemaForMandatoryNullableTest Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:254 Depends
   * on: validationDisabledAdDatabaseLevel
   */
  @Test
  public void test14_DropSchemaForMandatoryNullableTest() {
    session.execute("DROP CLASS MyTestClass").close();
    session.getMetadata().reload();
  }

  /**
   * Original test method: testNullComparison Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDDocumentValidationTest.java:260
   */
  @Test
  public void test15_TestNullComparison() {
    // given
    session.begin();
    var doc1 = ((EntityImpl) session.newEntity()).setPropertyInChain("testField", null);
    var doc2 = ((EntityImpl) session.newEntity()).setPropertyInChain("testField", null);

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var comparator =
        new EntityComparator(
            Collections.singletonList(new Pair<>("testField", "asc")),
            context);

    Assert.assertEquals(0, comparator.compare(doc1, doc2));
    session.commit();
  }
}
