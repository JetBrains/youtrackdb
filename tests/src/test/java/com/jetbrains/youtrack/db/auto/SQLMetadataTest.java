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
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * SQL test against metadata.
 */
@Test
public class SQLMetadataTest extends BaseDBTest {

  @Test
  public void querySchemaClasses() {
    var result =
        session
            .query("select expand(classes) from metadata:schema").toList();

    Assert.assertTrue(result.size() != 0);
  }

  @Test
  public void querySchemaProperties() {
    var result =
        session
            .query(
                "select expand(properties) from (select expand(classes) from metadata:schema)"
                    + " where name = 'OUser'").toList();

    Assert.assertTrue(result.size() != 0);
  }

  @Test
  public void queryIndexes() {
    var result =
        session
            .query(
                "select expand(indexes) from metadata:indexmanager").toList();

    Assert.assertTrue(result.size() != 0);
  }

  @Test
  public void queryMetadataNotSupported() {
    try {
      session
          .query("select expand(indexes) from metadata:blaaa").toList();
      Assert.fail();
    } catch (UnsupportedOperationException e) {
    }
  }
}
