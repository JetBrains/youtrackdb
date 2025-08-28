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
 */

package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.exception.InvalidDatabaseNameException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.junit.Assert;
import org.junit.Test;

public class StorageNamingTests {

  @Test
  public void testSpecialLettersOne() {
    try (var youTrackDB = YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()))) {
      try {
        youTrackDB.create("name%", DatabaseType.MEMORY);
        Assert.fail();
      } catch (InvalidDatabaseNameException e) {
        // skip
      }
    }
  }

  @Test
  public void testSpecialLettersTwo() {
    try (var youTrackDB = YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()))) {
      try {
        youTrackDB.create("na.me", DatabaseType.MEMORY);
        Assert.fail();
      } catch (InvalidDatabaseNameException e) {
        // skip
      }
    }
  }

  @Test
  public void testSpecialLettersThree() {
    try (var youTrackDB = YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()))) {
      youTrackDB.create("na_me$", DatabaseType.MEMORY);
      youTrackDB.drop("na_me$");
    }
  }

  @Test
  public void commaInPathShouldBeAllowed() {
    AbstractStorage.checkName("/path/with/,/but/not/in/the/name");
    AbstractStorage.checkName("/,,,/,/,/name");
  }

  @Test(expected = InvalidDatabaseNameException.class)
  public void commaInNameShouldThrow() {
    AbstractStorage.checkName("/path/with/,/name/with,");
  }

  @Test(expected = InvalidDatabaseNameException.class)
  public void name() throws Exception {
    AbstractStorage.checkName("/name/with,");
  }
}
