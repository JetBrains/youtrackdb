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

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of DBRecordMetadataTest. Original test class:
 * com.jetbrains.youtrackdb.auto.DBRecordMetadataTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBRecordMetadataTest.java
 *
 * @since 11.03.13 12:00
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DBRecordMetadataTest extends BaseDBTest {

  private static void assetORIDEquals(RID actual, RID expected) {
    assertEquals(actual.getCollectionId(), expected.getCollectionId());
    assertEquals(actual.getCollectionPosition(), expected.getCollectionPosition());
  }

  /**
   * Original test method: testGetRecordMetadata Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBRecordMetadataTest.java:19
   */
  @Test
  public void test01_GetRecordMetadata() {

    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    session.commit();
    for (var i = 0; i < 5; i++) {
      session.begin();
      if (!doc.getIdentity().isNew()) {
        var activeTx = session.getActiveTransaction();
        doc = activeTx.load(doc);
      }

      doc.setProperty("field", i);
      session.commit();

      session.begin();
      final var metadata = session.getRecordMetadata(doc.getIdentity());
      assetORIDEquals(doc.getIdentity(), metadata.getRecordId());
      var activeTx = session.getActiveTransaction();
      assertEquals(activeTx.<EntityImpl>load(doc).getVersion(), metadata.getVersion());
      session.commit();
    }
  }
}
