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
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class HookTxTest extends BaseDBTest {

  public static final int RECORD_BEFORE_CREATE = 3;
  public static final int RECORD_AFTER_CREATE = 5;
  public static final int RECORD_BEFORE_READ = 7;
  public static final int RECORD_AFTER_READ = 11;
  public static final int RECORD_BEFORE_UPDATE = 13;
  public static final int RECORD_AFTER_UPDATE = 17;
  public static final int RECORD_BEFORE_DELETE = 19;
  public static final int RECORD_AFTER_DELETE = 23;

  private int callbackCount = 0;
  private Entity profile;
  private int expectedHookState;

  private final class RecordHook extends RecordHookAbstract {

    @Override
    @Test(enabled = false)
    public RESULT onRecordBeforeCreate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        callbackCount += RECORD_BEFORE_CREATE;
      }
      return RESULT.RECORD_NOT_CHANGED;
    }

    @Override
    @Test(enabled = false)
    public void onRecordAfterCreate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        callbackCount += RECORD_AFTER_CREATE;
      }
    }

    @Override
    @Test(enabled = false)
    public void onRecordBeforeRead(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        callbackCount += RECORD_BEFORE_READ;
      }
    }

    @Override
    @Test(enabled = false)
    public void onRecordAfterRead(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        callbackCount += RECORD_AFTER_READ;
      }
    }

    @Override
    @Test(enabled = false)
    public RESULT onRecordBeforeUpdate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        callbackCount += RECORD_BEFORE_UPDATE;
      }
      return RESULT.RECORD_NOT_CHANGED;
    }

    @Override
    @Test(enabled = false)
    public void onRecordAfterUpdate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        callbackCount += RECORD_AFTER_UPDATE;
      }
    }

    @Override
    @Test(enabled = false)
    public RESULT onRecordBeforeDelete(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        callbackCount += RECORD_BEFORE_DELETE;
      }
      return RESULT.RECORD_NOT_CHANGED;
    }

    @Override
    @Test(enabled = false)
    public void onRecordAfterDelete(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        callbackCount += RECORD_AFTER_DELETE;
      }
    }

  }

  @Parameters(value = "remote")
  public HookTxTest(@Optional Boolean remote) {
    super(remote != null ? remote : false);
  }

  @Test
  public void testRegisterHook() {
    session.registerHook(new RecordHook());
  }

  @Test(dependsOnMethods = "testRegisterHook")
  public void testHookCallsCreate() {
    session.registerHook(new RecordHook());
    profile = session.newInstance("Profile");
    profile.setProperty("nick", "HookTxTest");
    profile.setProperty("value", 0);

    expectedHookState = 0;

    // TEST HOOKS ON CREATE
    Assert.assertEquals(callbackCount, 0);
    session.begin();
    session.commit();

    expectedHookState += RECORD_BEFORE_CREATE + RECORD_AFTER_CREATE;
    Assert.assertEquals(callbackCount, expectedHookState);
  }

  @Test(dependsOnMethods = "testHookCallsCreate")
  public void testHookCallsRead() {
    session.registerHook(new RecordHook());
    // TEST HOOKS ON READ
    session.begin();

    expectedHookState += RECORD_BEFORE_READ + RECORD_AFTER_READ;

    this.profile = session.load(profile.getIdentity());
    Assert.assertEquals(callbackCount, expectedHookState);

    Assert.assertEquals(callbackCount, expectedHookState);
    session.commit();
  }

  @Test(dependsOnMethods = "testHookCallsRead")
  public void testHookCallsUpdate() {
    session.registerHook(new RecordHook());
    session.begin();
    profile = session.load(profile.getIdentity());
    // TEST HOOKS ON UPDATE
    profile.setProperty("value", profile.<Integer>getProperty("value") + 1000);
    session.commit();

    expectedHookState +=
        RECORD_BEFORE_UPDATE + RECORD_AFTER_UPDATE + RECORD_BEFORE_READ + RECORD_AFTER_READ;
    Assert.assertEquals(callbackCount, expectedHookState);
  }

  @Test(dependsOnMethods = "testHookCallsUpdate")
  public void testHookCallsDelete() {
    session.registerHook(new RecordHook());
    // TEST HOOKS ON DELETE
    session.begin();
    session.delete(session.bindToSession(profile));
    session.commit();

    expectedHookState +=
        RECORD_BEFORE_DELETE + RECORD_AFTER_DELETE + RECORD_BEFORE_READ + RECORD_AFTER_READ;
    Assert.assertEquals(callbackCount, expectedHookState);

    session.unregisterHook(new RecordHook());
  }
}
