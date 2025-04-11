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

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test(groups = "query")
public class WrongQueryTest extends BaseDBTest {

  @Parameters(value = "remote")
  public WrongQueryTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  public void queryFieldOperatorNotSupported() {
    try (var result = session.execute(
        "select * from Account where name.not() like 'G%'")) {

      Assert.fail();
    } catch (CommandSQLParsingException e) {
    }
  }
}
