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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class DbClosedTest extends BaseDBJUnit5Test {

  @Test
  @Order(1)
  void testDoubleDb() {
    var db = acquireSession();

    // now I am getting another db instance
    var dbAnother = acquireSession();
    dbAnother.close();

    db.close();
  }

  @Test
  @Order(2)
  void testDoubleDbWindowsPath() {
    var db = acquireSession();

    // now I am getting another db instance
    var dbAnother = acquireSession();
    dbAnother.close();

    db.close();
  }

  @Test
  @Order(3)
  void testRemoteConns() {
    final var max =
        GlobalConfiguration.NETWORK_MAX_CONCURRENT_SESSIONS.getValueAsInteger();
    for (var i = 0; i < max * 2; ++i) {
      final var db = acquireSession();
      db.close();
    }
  }
}
