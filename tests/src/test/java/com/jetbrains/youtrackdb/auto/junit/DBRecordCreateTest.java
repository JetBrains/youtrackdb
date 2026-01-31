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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests for record creation and existence checking.
 *
 * <p><b>Suite Dependency:</b> This test is part of {@link DatabaseTestSuite}. Can be run
 * individually as the {@code @BeforeClass} method initializes the required schema.</p>
 *
 * <p>Original test class: {@code com.jetbrains.youtrackdb.auto.DBRecordCreateTest}</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DBRecordCreateTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    DBRecordCreateTest instance = new DBRecordCreateTest();
    instance.beforeClass();
  }

  /**
   * Original test method: testNewRecord Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBRecordCreateTest.java:11
   */
  @Test
  public void test01_NewRecord() {
    final var entityId = session.computeInTx(tx -> {
      final var element = tx.newEntity();
      assertTrue(tx.exists(element.getIdentity()));
      return element.getIdentity();
    });

    session.executeInTx(tx -> {
      assertTrue(tx.exists(entityId));
    });
  }

  /**
   * Original test method: testNewRecordRollbackTx Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBRecordCreateTest.java:24
   */
  @Test
  public void test02_NewRecordRollbackTx() {
    final var entityId = session.computeInTx(tx -> {
      final var element = tx.newEntity();
      assertTrue(tx.exists(element.getIdentity()));
      tx.rollback();
      return element.getIdentity();
    });

    session.executeInTx(tx -> {
      assertFalse(tx.exists(entityId));
    });
  }

  /**
   * Original test method: testDeleteRecord Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBRecordCreateTest.java:38
   */
  @Test
  public void test03_DeleteRecord() {
    session.executeInTx(tx -> {
      var element = tx.newEntity();
      assertTrue(tx.exists(element.getIdentity()));
      element.delete();
      assertFalse(tx.exists(element.getIdentity()));
    });
  }

  /**
   * Original test method: testLoadDeleteSameTx Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBRecordCreateTest.java:48
   */
  @Test
  public void test04_LoadDeleteSameTx() {
    session.executeInTx(tx -> {
      var element = tx.newEntity();
      var loaded = tx.load(element.getIdentity());
      assertTrue(tx.exists(loaded.getIdentity()));
      element.delete();
      assertFalse(tx.exists(loaded.getIdentity()));
    });
  }

  /**
   * Original test method: testLoadDeleteDifferentTx Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBRecordCreateTest.java:59
   */
  @Test
  public void test05_LoadDeleteDifferentTx() {
    final var entityId =
        session.computeInTx(tx -> tx.newEntity().getIdentity());

    session.executeInTx(tx -> {
      var loaded = tx.load(entityId);
      assertTrue(tx.exists(loaded.getIdentity()));
      loaded.delete();
      assertFalse(tx.exists(loaded.getIdentity()));
    });

    session.executeInTx(tx -> assertFalse(tx.exists(entityId)));
  }
}
