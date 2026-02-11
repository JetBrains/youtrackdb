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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.testng.annotations.Test;

public class DbClosedTest extends BaseDBTest {

  public void testDoubleDb() {
    var db = acquireSession();

    // now I am getting another db instance
    var dbAnother = acquireSession();
    dbAnother.close();

    db.close();
  }

  public void testDoubleDbWindowsPath() {
    var db = acquireSession();

    // now I am getting another db instance
    var dbAnother = acquireSession();
    dbAnother.close();

    db.close();
  }

  @Test
  public void testRemoteConns() {
    final var max = GlobalConfiguration.NETWORK_MAX_CONCURRENT_SESSIONS.getValueAsInteger();
    for (var i = 0; i < max * 2; ++i) {
      final var db = acquireSession();
      db.close();
    }
  }
}
