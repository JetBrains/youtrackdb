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
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHookAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of HookTxTest. Original test class: com.jetbrains.youtrackdb.auto.HookTxTest
 * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/HookTxTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class HookTxTest extends BaseDBTest {

  private static Entity profile;
  private static RecordHook recordHook;

  /**
   * Original inner class: RecordHook Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/HookTxTest.java:32
   */
  private static final class RecordHook extends RecordHookAbstract {

    private int beforeCreateCount;
    private int afterCreateCount;

    private int beforeUpdateCount;
    private int afterUpdateCount;

    private int beforeDeleteCount;
    private int afterDeleteCount;

    private int callbackCount;

    private int readCount;

    @Override
    public void onRecordRead(DBRecord record) {
      if (record instanceof EntityImpl
          && ((EntityImpl) record).getSchemaClassName() != null
          && ((EntityImpl) record).getSchemaClassName().equals("Profile")) {
        readCount++;
        callbackCount++;
      }
    }

    @Override
    public void onBeforeRecordCreate(DBRecord record) {
      if (record instanceof EntityImpl
          && ((EntityImpl) record).getSchemaClassName() != null
          && ((EntityImpl) record).getSchemaClassName().equals("Profile")) {
        beforeCreateCount++;
        callbackCount++;
      }
    }

    @Override
    public void onAfterRecordCreate(DBRecord record) {
      if (record instanceof EntityImpl
          && ((EntityImpl) record).getSchemaClassName() != null
          && ((EntityImpl) record).getSchemaClassName().equals("Profile")) {
        afterCreateCount++;
        callbackCount++;
      }
    }

    @Override
    public void onBeforeRecordUpdate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        beforeUpdateCount++;
        callbackCount++;
      }
    }

    @Override
    public void onAfterRecordUpdate(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        afterUpdateCount++;
        callbackCount++;
      }
    }

    @Override
    public void onBeforeRecordDelete(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        beforeDeleteCount++;
        callbackCount++;
      }
    }

    @Override
    public void onAfterRecordDelete(DBRecord iRecord) {
      if (iRecord instanceof EntityImpl
          && ((EntityImpl) iRecord).getSchemaClassName() != null
          && ((EntityImpl) iRecord).getSchemaClassName().equals("Profile")) {
        afterDeleteCount++;
        callbackCount++;
      }
    }
  }

  @BeforeClass
  public static void setUpClass() throws Exception {
    HookTxTest instance = new HookTxTest();
    instance.beforeClass();
  }

  /**
   * Original method: beforeMethod Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/HookTxTest.java:124
   */
  @Override
  @Before
  public void beforeMethod() throws Exception {
    super.beforeMethod();
    recordHook = new RecordHook();
    session.registerHook(recordHook);
  }

  /**
   * Original test method: testHookCallsCreate Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/HookTxTest.java:132
   */
  @Test
  public void test01_HookCallsCreate() {
    session.begin();
    profile = session.newInstance("Profile");
    profile.setProperty("nick", "HookTxTest");
    profile.setProperty("value", 0);

    // TEST HOOKS ON CREATE
    Assert.assertEquals(0, recordHook.callbackCount);
    session.commit();

    Assert.assertEquals(1, recordHook.beforeCreateCount);
    Assert.assertEquals(1, recordHook.afterCreateCount);
    // Assert.assertEquals(1, recordHook.readCount);

    Assert.assertEquals(2, recordHook.callbackCount);
  }

  /**
   * Original test method: testHookCallsRead Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/HookTxTest.java:150
   */
  @Test
  public void test02_HookCallsRead() {
    // TEST HOOKS ON READ
    session.begin();

    profile = session.load(profile.getIdentity());
    session.commit();

    Assert.assertEquals(1, recordHook.readCount);
    Assert.assertEquals(1, recordHook.callbackCount);
  }

  /**
   * Original test method: testHookCallsUpdate Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/HookTxTest.java:162 Depends on:
   * testHookCallsCreate
   */
  @Test
  public void test03_HookCallsUpdate() {
    session.begin();
    profile = session.load(profile.getIdentity());
    // TEST HOOKS ON UPDATE
    profile.setProperty("value", profile.<Integer>getProperty("value") + 1000);
    session.commit();

    Assert.assertEquals(1, recordHook.readCount);
    Assert.assertEquals(1, recordHook.beforeUpdateCount);
    Assert.assertEquals(1, recordHook.afterUpdateCount);
    Assert.assertEquals(3, recordHook.callbackCount);
  }

  /**
   * Original test method: testHookCallsDelete Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/HookTxTest.java:176 Depends on:
   * testHookCallsUpdate
   */
  @Test
  public void test04_HookCallsDelete() {
    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(profile));
    session.commit();

    Assert.assertEquals(1, recordHook.readCount);
    Assert.assertEquals(1, recordHook.beforeDeleteCount);
    Assert.assertEquals(1, recordHook.afterDeleteCount);

    Assert.assertEquals(3, recordHook.callbackCount);

    session.unregisterHook(new RecordHook());
  }
}
