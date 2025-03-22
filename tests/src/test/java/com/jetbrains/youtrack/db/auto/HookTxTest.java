/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RecordHookAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class HookTxTest extends BaseDBTest {

  private Entity profile;
  private RecordHook recordHook;

  private final class RecordHook extends RecordHookAbstract {

    private int createCount;
    private int readCount;
    private int updateCount;
    private int deleteCount;
    private int callbackCount;

    @Override
    @Test(enabled = false)
    public void onRecordCreate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        createCount++;
        callbackCount++;
      }
    }

    @Override
    @Test(enabled = false)
    public void onRecordRead(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        readCount++;
        callbackCount++;
      }
    }

    @Override
    @Test(enabled = false)
    public void onRecordUpdate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        updateCount++;
        callbackCount++;
      }
    }

    @Override
    @Test(enabled = false)
    public void onRecordDelete(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        deleteCount++;
        callbackCount++;
      }
    }

  }

  @Parameters(value = "remote")
  public HookTxTest(@Optional Boolean remote) {
    super(remote != null ? remote : false);
  }

  @BeforeMethod
  public void beforeMethod() {
    recordHook = new RecordHook();
    session.registerHook(recordHook);
  }

  @Test
  public void testHookCallsCreate() {
    profile = session.newInstance("Profile");
    profile.setProperty("nick", "HookTxTest");
    profile.setProperty("value", 0);

    // TEST HOOKS ON CREATE
    Assert.assertEquals(recordHook.callbackCount, 0);
    session.begin();
    session.commit();

    Assert.assertEquals(recordHook.createCount, 1);
    Assert.assertEquals(recordHook.readCount, 1);
    Assert.assertEquals(recordHook.callbackCount, 2);
  }

  @Test
  public void testHookCallsRead() {
    // TEST HOOKS ON READ
    session.begin();

    this.profile = session.load(profile.getIdentity());
    session.commit();

    Assert.assertEquals(recordHook.readCount, 1);
    Assert.assertEquals(recordHook.callbackCount, 1);
  }

  @Test
  public void testHookCallsUpdate() {
    session.begin();
    profile = session.load(profile.getIdentity());
    // TEST HOOKS ON UPDATE
    profile.setProperty("value", profile.<Integer>getProperty("value") + 1000);
    session.commit();

    Assert.assertEquals(recordHook.readCount, 1);
    Assert.assertEquals(recordHook.updateCount, 1);
    Assert.assertEquals(recordHook.callbackCount, 2);
  }

  @Test(dependsOnMethods = "testHookCallsUpdate")
  public void testHookCallsDelete() {
    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(profile));
    session.commit();

    Assert.assertEquals(recordHook.readCount, 1);
    Assert.assertEquals(recordHook.deleteCount, 1);
    Assert.assertEquals(recordHook.callbackCount, 2);

    session.unregisterHook(new RecordHook());
  }
}
