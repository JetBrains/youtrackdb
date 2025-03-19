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
package com.jetbrains.youtrack.db.auto;

import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test(groups = "sql-cluster-alter")
public class SQLAlterClusterCommandTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLAlterClusterCommandTest(boolean remote) {
    super(remote);
  }

  @Test
  public void testCreateCluster() {
    var expectedClusters = session.getClusters();
    try {
      session.execute("create cluster europe");
      Assert.assertEquals(session.getClusters(), expectedClusters + 1);
    } finally {
      session.execute("drop cluster europe");
    }
    Assert.assertEquals(session.getClusters(), expectedClusters);
  }

  @Test
  public void testAlterClusterName() {
    try {
      session.execute("create cluster europe");
      session.execute("ALTER CLUSTER europe NAME \"my_orient\"");

      var clusterId = session.getClusterIdByName("my_orient");
      Assert.assertEquals(clusterId, 18);
    } finally {
      session.execute("drop cluster my_orient");
    }
  }
}
