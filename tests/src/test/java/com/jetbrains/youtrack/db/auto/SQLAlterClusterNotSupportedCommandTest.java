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

import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test(groups = "sql-cluster-alter")
public class SQLAlterClusterNotSupportedCommandTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLAlterClusterNotSupportedCommandTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testAlterClusterEncryption() {
    try {
      session.execute("create cluster europe");
      session.execute("ALTER CLUSTER europe encryption aes");
    } finally {
      session.execute("drop cluster europe");
    }
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testClusterCompression_lowercase() {
    try {
      session.execute("create cluster europe");
      session.execute("alter cluster europe compression gzip");
    } finally {
      session.execute("drop cluster europe");
    }
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testClusterCompression_uppercase() {
    try {
      session.execute("create cluster europe");
      session.execute("alter cluster europe COMPRESSION gzip");
    } finally {
      session.execute("drop cluster europe");
    }
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testClusterRecordOverflowGrowFactor() {
    try {
      session.execute("create cluster europe");
      session.execute("alter cluster europe RECORD_OVERFLOW_GROW_FACTOR 3");
    } finally {
      session.execute("drop cluster europe");
    }
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testClusterWAL() {
    try {
      session.execute("create cluster europe");
      session.execute("alter cluster europe USE_WAL true");
    } finally {
      session.execute("drop cluster europe");
    }
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testClusterRecordGrowFactor() {
    try {
      session.execute("create cluster europe");
      session.execute("alter cluster europe RECORD_GROW_FACTOR 3");
    } finally {
      session.execute("drop cluster europe");
    }
  }
}
