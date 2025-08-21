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
package com.jetbrains.youtrackdb.auto;

import java.io.IOException;
import org.testng.annotations.Test;

@Test
public class AlterDatabaseTest extends BaseDBTest {

  public void alterDateFormatOk() throws IOException {
    session.execute("alter database dateformat 'yyyy-MM-dd';").close();
    session.execute("alter database dateformat 'yyyy-MM-dd'").close();
  }
}
