/*
 * JUnit 4 version of BinaryTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/BinaryTest.java
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

import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of BinaryTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/BinaryTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class BinaryTest extends BaseDBTest {

  private RID rid;
  private static BinaryTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new BinaryTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BinaryTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testMixedCreateEmbedded (line 29) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BinaryTest.java
   */
  @Test
  public void test01_MixedCreateEmbedded() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("binary", "Binary data".getBytes());

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    Assert.assertEquals(new String(doc.getBinary("binary")),
        "Binary data");
    session.rollback();
  }

  /**
   * Original: testBasicCreateExternal (line 45) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BinaryTest.java
   */
  @Test
  public void test02_BasicCreateExternal() {
    session.begin();
    Blob record = session.newBlob("This is a test".getBytes());
    session.commit();

    rid = record.getIdentity();
  }

  /**
   * Original: testBasicReadExternal (line 54) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BinaryTest.java
   */
  @Test
  @Ignore
  public void test03_BasicReadExternal() {
    session.executeInTx(tx -> {
      RecordAbstract record = session.load(rid);

      Assert.assertEquals("This is a test", new String(record.toStream()));
    });
  }

  /**
   * Original: testMixedCreateExternal (line 63) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BinaryTest.java
   */
  @Test
  public void test04_MixedCreateExternal() {
    session.begin();

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("binary", session.newBlob("Binary data".getBytes()));

    session.commit();

    rid = doc.getIdentity();
  }

  /**
   * Original: testMixedReadExternal (line 75) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/BinaryTest.java
   */
  @Test
  @Ignore
  public void test05_MixedReadExternal() {
    session.executeInTx(tx -> {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(rid);
      Assert.assertEquals("Binary data",
          new String(((RecordAbstract) doc.getProperty("binary")).toStream()));
    });
  }

}
