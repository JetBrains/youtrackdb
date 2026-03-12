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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class BinaryTest extends BaseDBJUnit5Test {
  private RID rid;

  // Migrated from: com.jetbrains.youtrackdb.auto.BinaryTest#testMixedCreateEmbedded
  @Test
  @Order(1)
  void testMixedCreateEmbedded() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("binary", "Binary data".getBytes());

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    assertEquals("Binary data", new String(doc.getBinary("binary")));
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BinaryTest#testBasicCreateExternal
  @Test
  @Order(2)
  void testBasicCreateExternal() {
    session.begin();
    Blob record = session.newBlob("This is a test".getBytes());
    session.commit();

    rid = record.getIdentity();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BinaryTest#testBasicReadExternal
  @Test
  @Order(3)
  void testBasicReadExternal() {
    session.executeInTx(tx -> {
      RecordAbstract record = session.load(rid);

      assertEquals("This is a test", new String(record.toStream()));
    });
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BinaryTest#testMixedCreateExternal
  @Test
  @Order(4)
  void testMixedCreateExternal() {
    session.begin();

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("binary", session.newBlob("Binary data".getBytes()));

    session.commit();

    rid = doc.getIdentity();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.BinaryTest#testMixedReadExternal
  @Test
  @Order(5)
  void testMixedReadExternal() {
    session.executeInTx(tx -> {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(rid);
      assertEquals(
          "Binary data",
          new String(((RecordAbstract) doc.getProperty("binary")).toStream()));
    });
  }
}
