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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.RestrictedOperation;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityShared;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class RestrictedTest extends BaseDBTest {

  private RID adminRecordId;
  private RID writerRecordId;

  private Role readerRole = null;

  @Parameters(value = "remote")
  public RestrictedTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Override
  protected YouTrackDBConfig createConfig(YouTrackDBConfigBuilderImpl builder) {
    builder.addGlobalConfigurationParameter(GlobalConfiguration.NON_TX_READS_WARNING_MODE,
        "EXCEPTION");
    return builder.build();
  }

  @Test
  public void testCreateRestrictedClass() {
    session = createSessionInstance();
    session
        .getMetadata()
        .getSchema()
        .createClass("CMSDocument", session.getMetadata().getSchema().getClass("ORestricted"));

    session.begin();
    var adminRecord = ((EntityImpl) session.newEntity("CMSDocument")).field("user", "admin");

    this.adminRecordId = adminRecord.getIdentity();
    session.commit();

    session.begin();
    readerRole = session.getMetadata().getSecurity().getRole("reader");
    session.commit();

    Assert.assertTrue(adminRecord.isUnloaded());
  }

  @Test(dependsOnMethods = "testCreateRestrictedClass")
  public void testFilteredQuery() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    var result = session.query("select from CMSDocument");
    Assert.assertEquals(result.stream().count(), 0);
    session.commit();
  }

  @Test(dependsOnMethods = "testFilteredQuery")
  public void testCreateAsWriter() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    var writerRecord = ((EntityImpl) session.newEntity("CMSDocument")).field("user", "writer");

    this.writerRecordId = writerRecord.getIdentity();
    session.commit();
  }

  @Test(dependsOnMethods = "testCreateAsWriter")
  public void testFilteredQueryAsReader() throws IOException {
    session = createSessionInstance("reader", "reader");

    session.begin();
    var result = session.query("select from CMSDocument");
    Assert.assertEquals(result.stream().count(), 0);
    session.commit();
  }

  @Test(dependsOnMethods = "testFilteredQueryAsReader")
  public void testFilteredQueryAsAdmin() throws IOException {
    session = createSessionInstance();

    session.begin();
    var result = session.query("select from CMSDocument where user = 'writer'");
    Assert.assertEquals(result.stream().count(), 1);
    session.commit();
  }

  @Test(dependsOnMethods = "testFilteredQueryAsAdmin")
  public void testFilteredQueryAsWriter() throws IOException {
    session = createSessionInstance("writer", "writer");

    session.begin();
    var result = session.query("select from CMSDocument");
    Assert.assertEquals(result.stream().count(), 1);
    session.commit();
  }

  @Test(dependsOnMethods = "testFilteredQueryAsWriter")
  public void testFilteredDirectReadAsWriter() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    try {
      session.load(adminRecordId.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }

    session.commit();
  }

  @Test(dependsOnMethods = "testFilteredDirectReadAsWriter")
  public void testFilteredDirectUpdateAsWriter() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    try {
      var adminRecord = session.loadEntity(this.adminRecordId);
      adminRecord.setProperty("user", "writer-hacker");
      session.commit();
    } catch (SecurityException | RecordNotFoundException e) {
      // OK AS EXCEPTION
    }
    session.close();

    session = createSessionInstance();
    session.begin();
    var adminRecord = session.<EntityImpl>load(this.adminRecordId);
    Assert.assertEquals(adminRecord.field("user"), "admin");
    session.commit();
  }

  @Test(dependsOnMethods = "testFilteredDirectUpdateAsWriter")
  public void testFilteredDirectDeleteAsWriter() throws IOException {
    session = createSessionInstance("writer", "writer");
    try {
      session.begin();
      session.delete(adminRecordId.getRecord(session));
      session.commit();
    } catch (SecurityException | RecordNotFoundException e) {
      // OK AS EXCEPTION
    }
    session.close();

    session = createSessionInstance();
    session.begin();
    var adminRecord = session.<EntityImpl>load(this.adminRecordId);
    Assert.assertEquals(adminRecord.field("user"), "admin");
    session.commit();
  }

  @Test(dependsOnMethods = "testFilteredDirectDeleteAsWriter")
  public void testFilteredHackingAllowFieldAsWriter() throws IOException {
    session = createSessionInstance("writer", "writer");
    try {
      session.begin();
      // FORCE LOADING
      EntityImpl adminRecord = session.load(this.adminRecordId);
      Set<Identifiable> allows = adminRecord.field(SecurityShared.ALLOW_ALL_FIELD);
      allows.add(
          session.getMetadata().getSecurity().getUser(session.geCurrentUser().getName(session))
              .getIdentity());

      session.commit();
    } catch (SecurityException | RecordNotFoundException e) {
      // OK AS EXCEPTION
    }
    session.close();

    session = createSessionInstance();
  }

  @Test(dependsOnMethods = "testFilteredHackingAllowFieldAsWriter")
  public void testAddReaderAsRole() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    var writerRecord = session.<EntityImpl>load(this.writerRecordId);
    Set<Identifiable> allows = writerRecord.field(SecurityShared.ALLOW_ALL_FIELD);
    allows.add(readerRole.getIdentity());

    session.commit();
  }

  @Test(dependsOnMethods = "testAddReaderAsRole")
  public void testReaderCanSeeWriterDocumentAfterPermission() throws IOException {
    session = createSessionInstance("reader", "reader");
    session.begin();
    Assert.assertNotNull(session.load(writerRecordId.getIdentity()));
    session.commit();
  }

  @Test(dependsOnMethods = "testReaderCanSeeWriterDocumentAfterPermission")
  public void testWriterRoleCanRemoveReader() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    EntityImpl writerRecord = session.load(this.writerRecordId);
    Assert.assertEquals(
        ((Collection<?>) writerRecord.field(RestrictedOperation.ALLOW_ALL.getFieldName())).size(),
        2);
    session
        .getMetadata()
        .getSecurity()
        .denyRole(writerRecord, RestrictedOperation.ALLOW_ALL, "reader");
    Assert.assertEquals(
        ((Collection<?>) writerRecord.field(RestrictedOperation.ALLOW_ALL.getFieldName())).size(),
        1);

    session.commit();
  }

  @Test(dependsOnMethods = "testWriterRoleCanRemoveReader")
  public void testReaderCannotSeeWriterDocument() throws IOException {
    session = createSessionInstance("reader", "reader");
    session.begin();
    try {
      session.load(writerRecordId.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    session.commit();
  }

  @Test(dependsOnMethods = "testReaderCannotSeeWriterDocument")
  public void testWriterAddReaderUserOnlyForRead() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    EntityImpl writerRecord = session.load(this.writerRecordId);
    session
        .getMetadata()
        .getSecurity()
        .allowUser(writerRecord, RestrictedOperation.ALLOW_READ, "reader");

    session.commit();
  }

  @Test(dependsOnMethods = "testWriterAddReaderUserOnlyForRead")
  public void testReaderCanSeeWriterDocument() throws IOException {
    session = createSessionInstance("reader", "reader");
    session.begin();
    Assert.assertNotNull(session.load(writerRecordId.getIdentity()));
    session.commit();
  }

  /**
   * *** TESTS FOR #1980: Record Level Security: permissions don't follow role's inheritance ****
   */
  @Test(dependsOnMethods = "testReaderCanSeeWriterDocument")
  public void testWriterRemoveReaderUserOnlyForRead() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    EntityImpl writerRecord = session.load(this.writerRecordId);
    session
        .getMetadata()
        .getSecurity()
        .denyUser(writerRecord, RestrictedOperation.ALLOW_READ, "reader");

    session.commit();
  }

  @Test(dependsOnMethods = "testWriterRemoveReaderUserOnlyForRead")
  public void testReaderCannotSeeWriterDocumentAgain() throws IOException {
    session = createSessionInstance("reader", "reader");
    session.begin();
    try {
      session.load(writerRecordId.getIdentity());
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    session.commit();
  }

  @Test(dependsOnMethods = "testReaderCannotSeeWriterDocumentAgain")
  public void testReaderRoleInheritsFromWriterRole() throws IOException {
    session = createSessionInstance();
    session.begin();
    var reader = session.getMetadata().getSecurity().getRole("reader");
    reader.setParentRole(session, session.getMetadata().getSecurity().getRole("writer"));

    reader.save(session);
    session.commit();
  }

  @Test(dependsOnMethods = "testReaderRoleInheritsFromWriterRole")
  public void testWriterRoleCanSeeWriterDocument() throws IOException {
    session = createSessionInstance("writer", "writer");
    session.begin();
    EntityImpl writerRecord = session.load(this.writerRecordId);
    session
        .getMetadata()
        .getSecurity()
        .allowRole(writerRecord, RestrictedOperation.ALLOW_READ, "writer");

    session.commit();
  }

  @Test(dependsOnMethods = "testWriterRoleCanSeeWriterDocument")
  public void testReaderRoleCanSeeInheritedDocument() {
    session = createSessionInstance("reader", "reader");

    session.begin();
    Assert.assertNotNull(session.load(writerRecordId.getIdentity()));
    session.commit();
  }

  @Test(dependsOnMethods = "testReaderRoleCanSeeInheritedDocument")
  public void testReaderRoleDesntInheritsFromWriterRole() throws IOException {
    session = createSessionInstance();
    session.begin();
    var reader = session.getMetadata().getSecurity().getRole("reader");
    reader.setParentRole(session, null);
    reader.save(session);
    session.commit();
  }

  /**
   * ** END TEST FOR #1980: Record Level Security: permissions don't follow role's inheritance ***
   */
  @Test(dependsOnMethods = "testReaderRoleDesntInheritsFromWriterRole")
  public void testTruncateClass() {
    session = createSessionInstance();
    try {
      session.command("truncate class CMSDocument").close();
      Assert.fail();
    } catch (SecurityException e) {
      Assert.assertTrue(true);
    }
  }

  @Test(dependsOnMethods = "testTruncateClass")
  public void testTruncateUnderlyingCluster() {
    session = createSessionInstance();
    try {
      session.command("truncate cluster CMSDocument").close();
    } catch (SecurityException e) {

    }
  }

  @Test(dependsOnMethods = "testTruncateUnderlyingCluster")
  public void testUpdateRestricted() {
    session = createSessionInstance();
    session
        .getMetadata()
        .getSchema()
        .createClass(
            "TestUpdateRestricted", session.getMetadata().getSchema().getClass("ORestricted"));

    session.begin();
    var adminRecord = ((EntityImpl) session.newEntity("TestUpdateRestricted")).field("user",
        "admin");

    this.adminRecordId = adminRecord.getIdentity();
    session.commit();

    session.close();

    session = createSessionInstance("writer", "writer");
    session.begin();
    var result = session.query("select from TestUpdateRestricted");
    Assert.assertEquals(result.stream().count(), 0);
    session.commit();

    session.close();

    session = createSessionInstance();
    session.begin();
    session
        .command("update TestUpdateRestricted content {\"data\":\"My Test\"}").close();
    session.commit();

    session.begin();
    result = session.query("select from TestUpdateRestricted");
    var res = result.next();
    Assert.assertFalse(result.hasNext());

    final var doc = res.asEntity();
    Assert.assertEquals(doc.getProperty("data"), "My Test");
    doc.setProperty("user", "admin");

    session.commit();

    session.close();

    session = createSessionInstance("writer", "writer");
    session.begin();
    result = session.query("select from TestUpdateRestricted");
    Assert.assertEquals(result.stream().count(), 0);
    session.commit();
  }

  @BeforeMethod
  protected void closeDb() {
    session.close();
  }
}
