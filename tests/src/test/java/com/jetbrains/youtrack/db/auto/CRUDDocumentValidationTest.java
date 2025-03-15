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

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal.ATTRIBUTES_INTERNAL;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityComparator;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class CRUDDocumentValidationTest extends BaseDBTest {

  private EntityImpl record;
  private EntityImpl account;

  @Parameters(value = "remote")
  public CRUDDocumentValidationTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void openDb() {
    session.begin();
    account = ((EntityImpl) session.newEntity("Account"));

    account.setProperty("id", "1234567890");
    session.commit();
  }

  @Test(dependsOnMethods = "validationMandatoryNullableNoCloseDb")
  public void validationDisabledAdDatabaseLevel() {
    session.getMetadata().reload();
    try {
      var entity = ((EntityImpl) session.newEntity("MyTestClass"));
      session.begin();

      session.commit();
      Assert.fail();
    } catch (ValidationException ignored) {
    }

    session
        .command("ALTER DATABASE " + ATTRIBUTES_INTERNAL.VALIDATION.name() + " FALSE")
        .close();
    try {
      var doc = ((EntityImpl) session.newEntity("MyTestClass"));
      session.begin();

      session.commit();

      session.begin();
      session.bindToSession(doc).delete();
      session.commit();
    } finally {
      session.setValidationEnabled(true);
      session
          .command("ALTER DATABASE " + DatabaseSessionInternal.ATTRIBUTES_INTERNAL.VALIDATION.name()
              + " TRUE")
          .close();
    }
  }

  @Test(dependsOnMethods = "openDb", expectedExceptions = ValidationException.class)
  public void validationMandatory() {
    session.begin();
    record = session.newInstance("Whiz");
    record.clear();

    session.commit();
  }

  @Test(dependsOnMethods = "validationMandatory", expectedExceptions = ValidationException.class)
  public void validationMinString() {
    session.begin();
    record = session.newInstance("Whiz");
    account = session.bindToSession(account);
    record.setProperty("account", account);
    record.setProperty("id", 23723);
    record.setProperty("text", "");

    session.commit();
  }

  @Test(
      dependsOnMethods = "validationMinString",
      expectedExceptions = ValidationException.class,
      expectedExceptionsMessageRegExp = "(?s).*more.*than.*")
  public void validationMaxString() {
    session.begin();
    record = session.newInstance("Whiz");
    account = session.bindToSession(account);
    record.setProperty("account", account);
    record.setProperty("id", 23723);
    record.setProperty(
        "text",
        "clfdkkjsd hfsdkjhf fjdkghjkfdhgjdfh gfdgjfdkhgfd skdjaksdjf skdjf sdkjfsd jfkldjfkjsdf"
            + " kljdk fsdjf kldjgjdhjg khfdjgk hfjdg hjdfhgjkfhdgj kfhdjghrjg");

    session.commit();
  }

  @Test(
      dependsOnMethods = "validationMaxString",
      expectedExceptions = ValidationException.class,
      expectedExceptionsMessageRegExp = "(?s).*precedes.*")
  public void validationMinDate() throws ParseException {
    session.begin();
    record = session.newInstance("Whiz");
    account = session.bindToSession(account);
    record.setProperty("account", account);
    record.setPropertyInChain("date", new SimpleDateFormat("dd/MM/yyyy").parse("01/33/1976"));
    record.setProperty("text", "test");

    session.commit();
  }

  @Test(dependsOnMethods = "validationMinDate", expectedExceptions = DatabaseException.class)
  public void validationEmbeddedType() {
    session.begin();
    record = session.newInstance("Whiz");
    record.setPropertyInChain("account", session.geCurrentUser());

    session.commit();
  }

  @Test(
      dependsOnMethods = "validationEmbeddedType",
      expectedExceptions = ValidationException.class)
  public void validationStrictClass() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("StrictTest"));
    doc.setProperty("id", 122112);
    doc.setProperty("antani", "122112");

    session.commit();
  }

  @Test(dependsOnMethods = "validationStrictClass")
  public void closeDb() {
    session.close();
  }

  @Test(dependsOnMethods = "closeDb")
  public void createSchemaForMandatoryNullableTest() {
    if (session.getMetadata().getSchema().existsClass("MyTestClass")) {
      session.getMetadata().getSchema().dropClass("MyTestClass");
    }

    session.command("CREATE CLASS MyTestClass").close();
    session.command("CREATE PROPERTY MyTestClass.keyField STRING").close();
    session.command("ALTER PROPERTY MyTestClass.keyField MANDATORY true").close();
    session.command("ALTER PROPERTY MyTestClass.keyField NOTNULL true").close();
    session.command("CREATE PROPERTY MyTestClass.dateTimeField DATETIME").close();
    session.command("ALTER PROPERTY MyTestClass.dateTimeField MANDATORY true").close();
    session.command("ALTER PROPERTY MyTestClass.dateTimeField NOTNULL false").close();
    session.command("CREATE PROPERTY MyTestClass.stringField STRING").close();
    session.command("ALTER PROPERTY MyTestClass.stringField MANDATORY true").close();
    session.command("ALTER PROPERTY MyTestClass.stringField NOTNULL false").close();

    session.begin();
    session
        .command(
            "INSERT INTO MyTestClass (keyField,dateTimeField,stringField) VALUES"
                + " (\"K1\",null,null)")
        .close();
    session.commit();
    session.reload();
    session.getMetadata().reload();
    session.close();
    session = acquireSession();
    var result =
        session.query("SELECT FROM MyTestClass WHERE keyField = ?", "K1").stream().toList();
    Assert.assertEquals(result.size(), 1);
    var doc = result.getFirst();
    Assert.assertTrue(doc.hasProperty("keyField"));
    Assert.assertTrue(doc.hasProperty("dateTimeField"));
    Assert.assertTrue(doc.hasProperty("stringField"));
  }

  @Test(dependsOnMethods = "createSchemaForMandatoryNullableTest")
  public void testUpdateDocDefined() {
    var result =
        session.query("SELECT FROM MyTestClass WHERE keyField = ?", "K1").stream().toList();
    session.begin();
    Assert.assertEquals(result.size(), 1);
    var readDoc = result.getFirst().asEntityOrNull();
    assert readDoc != null;
    readDoc.setProperty("keyField", "K1N");
    session.commit();
  }

  @Test(dependsOnMethods = "testUpdateDocDefined")
  public void validationMandatoryNullableCloseDb() {
    var doc = ((EntityImpl) session.newEntity("MyTestClass"));
    doc.setProperty("keyField", "K2");
    doc.setProperty("dateTimeField", null);
    doc.setProperty("stringField", null);
    session.begin();

    session.commit();

    session.close();
    session = acquireSession();

    var result =
        session.query("SELECT FROM MyTestClass WHERE keyField = ?", "K2").stream().toList();
    session.begin();
    Assert.assertEquals(result.size(), 1);
    var readDoc = result.getFirst().asEntityOrNull();
    assert readDoc != null;
    readDoc.setProperty("keyField", "K2N");
    session.commit();
  }

  @Test(dependsOnMethods = "validationMandatoryNullableCloseDb")
  public void validationMandatoryNullableNoCloseDb() {
    var doc = ((EntityImpl) session.newEntity("MyTestClass"));
    doc.setProperty("keyField", "K3");
    doc.setProperty("dateTimeField", null);
    doc.setProperty("stringField", null);

    session.begin();

    session.commit();

    session.begin();
    var result =
        session.query("SELECT FROM MyTestClass WHERE keyField = ?", "K3").stream().toList();
    Assert.assertEquals(result.size(), 1);
    var readDoc = result.getFirst().asEntityOrNull();
    assert readDoc != null;
    readDoc.setProperty("keyField", "K3N");
    session.commit();
  }

  @Test(dependsOnMethods = "validationDisabledAdDatabaseLevel")
  public void dropSchemaForMandatoryNullableTest() {
    session.command("DROP CLASS MyTestClass").close();
    session.getMetadata().reload();
  }

  @Test
  public void testNullComparison() {
    // given
    var doc1 = ((EntityImpl) session.newEntity()).setPropertyInChain("testField", null);
    var doc2 = ((EntityImpl) session.newEntity()).setPropertyInChain("testField", null);

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var comparator =
        new EntityComparator(
            Collections.singletonList(new Pair<>("testField", "asc")),
            context);

    Assert.assertEquals(comparator.compare(doc1, doc2), 0);
  }
}
