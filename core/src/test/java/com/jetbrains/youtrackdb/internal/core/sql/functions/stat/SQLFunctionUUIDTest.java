/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionUUID;
import org.junit.Before;
import org.junit.Test;

public class SQLFunctionUUIDTest {

  private SQLFunctionUUID uuid;

  @Before
  public void setup() {
    uuid = new SQLFunctionUUID();
  }

  @Test
  public void testEmpty() {
    var result = uuid.getResult();
    assertNull(result);
  }

  @Test
  public void testResult() {
    var result = (String) uuid.execute(null, null, null, null, null);
    assertNotNull(result);
  }

  @Test
  public void testQuery() {
    try (var ctx = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      if (ctx.exists("test")) {
        ctx.drop("test");
      }
      ctx.create("test", DatabaseType.MEMORY,
          new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
      try (var db = ctx.open("test", "admin", "adminpwd")) {
        db.executeInTx(transaction -> {
          try (final var result = transaction.query("select uuid() as uuid")) {
            assertNotNull(result.next().getProperty("uuid"));
            assertFalse(result.hasNext());
          }
        });
      }
      ctx.drop("test");
    }
  }
}
