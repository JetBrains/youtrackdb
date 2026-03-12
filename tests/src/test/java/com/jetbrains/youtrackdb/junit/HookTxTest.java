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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHookAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class HookTxTest extends BaseDBJUnit5Test {

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
    public void onRecordRead(DBRecord record) {
      if (record instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        readCount++;
        callbackCount++;
      }
    }

    @Override
    public void onBeforeRecordCreate(DBRecord record) {
      if (record instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        beforeCreateCount++;
        callbackCount++;
      }
    }

    @Override
    public void onAfterRecordCreate(DBRecord record) {
      if (record instanceof EntityImpl entity
          && entity.getSchemaClassName() != null
          && entity.getSchemaClassName().equals("Profile")) {
        afterCreateCount++;
        callbackCount++;
      }
    }

    @Override
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
  @BeforeEach
  void beforeEach() throws Exception {
    super.beforeEach();
    recordHook = new RecordHook();
    session.registerHook(recordHook);
  }

  @Test
  @Order(1)
  void testHookCallsCreate() {
    session.begin();
    profile = session.newInstance("Profile");
    profile.setProperty("nick", "HookTxTest");
    profile.setProperty("value", 0);

    // TEST HOOKS ON CREATE
    assertEquals(0, recordHook.callbackCount);
    session.commit();

    assertEquals(1, recordHook.beforeCreateCount);
    assertEquals(1, recordHook.afterCreateCount);
    //    assertEquals(1, recordHook.readCount);

    assertEquals(2, recordHook.callbackCount);
  }

  // Original had no dependsOnMethods, but depends on profile created in testHookCallsCreate
  @Test
  @Order(2)
  void testHookCallsRead() {
    // TEST HOOKS ON READ
    session.begin();

    this.profile = session.load(profile.getIdentity());
    session.commit();

    assertEquals(1, recordHook.readCount);
    assertEquals(1, recordHook.callbackCount);
  }

  @Test
  @Order(3)
  void testHookCallsUpdate() {
    session.begin();
    profile = session.load(profile.getIdentity());
    // TEST HOOKS ON UPDATE
    profile.setProperty("value", profile.<Integer>getProperty("value") + 1000);
    session.commit();

    assertEquals(1, recordHook.readCount);
    assertEquals(1, recordHook.beforeUpdateCount);
    assertEquals(1, recordHook.afterUpdateCount);
    assertEquals(3, recordHook.callbackCount);
  }

  @Test
  @Order(4)
  void testHookCallsDelete() {
    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(profile));
    session.commit();

    assertEquals(1, recordHook.readCount);
    assertEquals(1, recordHook.beforeDeleteCount);
    assertEquals(1, recordHook.afterDeleteCount);

    assertEquals(3, recordHook.callbackCount);

    session.unregisterHook(new RecordHook());
  }
}
