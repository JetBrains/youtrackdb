/*
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
package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CommandExecutorSQLCreateClassTest extends DbTestBase {

  public void beforeTest() throws Exception {
    super.beforeTest();
    final Schema schema = session.getMetadata().getSchema();
    schema.createClass("User", schema.getClass("V"));
  }

  @Test
  public void testCreateWithSuperclasses() throws Exception {

    session.execute("create class `UserVertex` extends `V` , `User`").close();

    var userVertex = session.getMetadata().getSchema().getClass("UserVertex");

    Assert.assertNotNull(userVertex);

    var superClassesNames = userVertex.getSuperClassesNames();

    Assert.assertEquals(2, superClassesNames.size());

    Assert.assertTrue(superClassesNames.contains("User"));
    Assert.assertTrue(superClassesNames.contains("V"));
  }
}
