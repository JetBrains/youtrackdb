/*
 * JUnit 4 version of EmbeddedLinkBagTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/EmbeddedLinkBagTest.java
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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of EmbeddedLinkBagTest. This test configures GlobalConfiguration thresholds
 * to force embedded mode for LinkBag and verifies isEmbedded() returns true.
 *
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/EmbeddedLinkBagTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EmbeddedLinkBagTest extends LinkBagTest {

  private static EmbeddedLinkBagTest instance;
  private int topThreshold;
  private int bottomThreshold;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new EmbeddedLinkBagTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeMethod (line 17)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/EmbeddedLinkBagTest.java
   */
  @Before
  public void setUp() throws Exception {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(Integer.MAX_VALUE);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(Integer.MAX_VALUE);

    beforeMethod();
  }

  /**
   * Original: afterMethod (line 30)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/EmbeddedLinkBagTest.java
   */
  @After
  public void tearDown() throws Exception {
    afterMethod();
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);
  }

  /**
   * Original: assertEmbedded (line 37)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/EmbeddedLinkBagTest.java
   */
  @Override
  protected void assertEmbedded(boolean isEmbedded) {
    Assert.assertTrue(isEmbedded);
  }
}
