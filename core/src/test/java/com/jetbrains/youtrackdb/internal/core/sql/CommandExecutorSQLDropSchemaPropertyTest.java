/*
 *
 *  *  Copyright YouTrackDB
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
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import org.junit.Assert;
import org.junit.Test;

public class CommandExecutorSQLDropSchemaPropertyTest extends DbTestBase {

  @Test
  public void test() {
    Schema schema = session.getMetadata().getSchema();
    var foo = schema.createClass("Foo");

    foo.createProperty("name", PropertyType.STRING);
    Assert.assertTrue(schema.getClass("Foo").existsProperty("name"));
    session.execute("DROP PROPERTY Foo.name").close();
    Assert.assertFalse(schema.getClass("Foo").existsProperty("name"));

    foo.createProperty("name", PropertyType.STRING);
    Assert.assertTrue(schema.getClass("Foo").existsProperty("name"));
    session.execute("DROP PROPERTY `Foo`.name").close();
    Assert.assertFalse(schema.getClass("Foo").existsProperty("name"));

    foo.createProperty("name", PropertyType.STRING);
    Assert.assertTrue(schema.getClass("Foo").existsProperty("name"));
    session.execute("DROP PROPERTY Foo.`name`").close();
    Assert.assertFalse(schema.getClass("Foo").existsProperty("name"));

    foo.createProperty("name", PropertyType.STRING);
    Assert.assertTrue(schema.getClass("Foo").existsProperty("name"));
    session.execute("DROP PROPERTY `Foo`.`name`").close();
    Assert.assertFalse(schema.getClass("Foo").existsProperty("name"));
  }

  @Test
  public void testIfExists() {
    Schema schema = session.getMetadata().getSchema();
    var testIfExistsClass = schema.createClass("testIfExists");

    testIfExistsClass.createProperty("name", PropertyType.STRING);
    Assert.assertTrue(schema.getClass("testIfExists").existsProperty("name"));
    session.execute("DROP PROPERTY testIfExists.name if exists").close();
    Assert.assertFalse(schema.getClass("testIfExists").existsProperty("name"));

    session.execute("DROP PROPERTY testIfExists.name if exists").close();
    Assert.assertFalse(schema.getClass("testIfExists").existsProperty("name"));
  }
}
