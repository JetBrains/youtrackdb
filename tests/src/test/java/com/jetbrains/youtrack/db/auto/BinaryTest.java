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

import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.RecordBytes;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

public class BinaryTest extends BaseDBTest {

  private RID rid;

  @Parameters(value = "remote")
  public BinaryTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void testMixedCreateEmbedded() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("binary", "Binary data".getBytes());

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    Assert.assertEquals(new String(doc.getBinary("binary")),
        "Binary data");
    session.rollback();
  }

  @Test
  public void testBasicCreateExternal() {
    session.begin();
    Blob record = session.newBlob("This is a test".getBytes());
    session.commit();

    rid = record.getIdentity();
  }

  @Test(dependsOnMethods = "testBasicCreateExternal")
  public void testBasicReadExternal() {
    session.executeInTx(tx -> {
      RecordAbstract record = session.load(rid);

      Assert.assertEquals("This is a test", new String(record.toStream()));
    });
  }

  @Test(dependsOnMethods = "testBasicReadExternal")
  public void testMixedCreateExternal() {
    session.begin();

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("binary", session.newBlob("Binary data".getBytes()));

    session.commit();

    rid = doc.getIdentity();
  }

  @Test(dependsOnMethods = "testMixedCreateExternal")
  public void testMixedReadExternal() {
    session.executeInTx(tx -> {
      EntityImpl doc = rid.getRecord(session);
      Assert.assertEquals("Binary data",
          new String(((RecordAbstract) doc.getProperty("binary")).toStream()));
    });
  }
}
