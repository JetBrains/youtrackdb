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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHookAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class HookTxTest extends BaseDBTest {

  private Entity profile;
  private RecordHook recordHook;

  private final class RecordHook extends RecordHookAbstract {

    private int beforeCreateCount;
    private int afterCreateCount;

    private int beforeUpdateCount;
    private int afterUpdateCount;

    private int beforeDeleteCount;
    private int afterDeleteCount;

    private int callbackCount;

    private int readCount;


    @Override
    @Test(enabled = false)
    public void onRecordRead(DBRecord record) {
      if (record instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        readCount++;
        callbackCount++;
      }
    }

    @Override
    @Test(enabled = false)
    public void onBeforeRecordCreate(DBRecord record) {
      if (record instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        beforeCreateCount++;
        callbackCount++;
      }
    }

    @Override
    @Test(enabled = false)
    public void onAfterRecordCreate(DBRecord record) {
      if (record instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        afterCreateCount++;
        callbackCount++;
      }
    }

    @Override
    @Test(enabled = false)
    public void onBeforeRecordUpdate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        beforeUpdateCount++;
        callbackCount++;
      }
    }

    @Override
    public void onAfterRecordUpdate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        afterUpdateCount++;
        callbackCount++;
      }
    }

    @Override
    @Test(enabled = false)
    public void onBeforeRecordDelete(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        beforeDeleteCount++;
        callbackCount++;
      }
    }

    @Override
    public void onAfterRecordDelete(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        afterDeleteCount++;
        callbackCount++;
      }
    }
  }

  @Override
  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();
    recordHook = new RecordHook();
    session.registerHook(recordHook);
  }

  @Test
  public void testHookCallsCreate() {
    session.begin();
    profile = session.newInstance("Profile");
    profile.setProperty("nick", "HookTxTest");
    profile.setProperty("value", 0);

    // TEST HOOKS ON CREATE
    Assert.assertEquals(recordHook.callbackCount, 0);
    session.commit();

    Assert.assertEquals(recordHook.beforeCreateCount, 1);
    Assert.assertEquals(recordHook.afterCreateCount, 1);
//    Assert.assertEquals(recordHook.readCount, 1);

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

  @Test(dependsOnMethods = "testHookCallsCreate")
  public void testHookCallsUpdate() {
    session.begin();
    profile = session.load(profile.getIdentity());
    // TEST HOOKS ON UPDATE
    profile.setProperty("value", profile.<Integer>getProperty("value") + 1000);
    session.commit();

    Assert.assertEquals(recordHook.readCount, 1);
    Assert.assertEquals(recordHook.beforeUpdateCount, 1);
    Assert.assertEquals(recordHook.afterUpdateCount, 1);
    Assert.assertEquals(recordHook.callbackCount, 3);
  }

  @Test(dependsOnMethods = "testHookCallsUpdate")
  public void testHookCallsDelete() {
    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(profile));
    session.commit();

    Assert.assertEquals(recordHook.readCount, 1);
    Assert.assertEquals(recordHook.beforeDeleteCount, 1);
    Assert.assertEquals(recordHook.afterDeleteCount, 1);

    Assert.assertEquals(recordHook.callbackCount, 3);

    session.unregisterHook(new RecordHook());
  }
}
