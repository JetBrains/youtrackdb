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

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class CommandExecutorSQLDropSchemaPropertyTest extends DbTestBase {

  @Test
  public void test() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("Foo").addSchemaProperty("name", PropertyType.STRING)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    Assert.assertTrue(schema.getClass("Foo").existsProperty("name"));

    graph.autoExecuteInTx(g ->
        g.schemaClass("Foo").schemaClassProperty("name").drop()
    );

    schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    Assert.assertFalse(schema.getClass("Foo").existsProperty("name"));

    graph.autoExecuteInTx(g -> g.schemaClass("Foo").
        addSchemaProperty("name", PropertyType.STRING)
    );

    schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    Assert.assertTrue(schema.getClass("Foo").existsProperty("name"));

    graph.autoExecuteInTx(g ->
        g.schemaClass("Foo").schemaClassProperty("name").drop()
    );

    schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    Assert.assertFalse(schema.getClass("Foo").existsProperty("name"));
  }

  @Test
  public void testIfExists() {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("testIfExists").addSchemaProperty("name", PropertyType.STRING)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    Assert.assertTrue(schema.getClass("testIfExists").existsProperty("name"));

    graph.autoExecuteInTx(g -> g.schemaClass("testIfExists").
        schemaClassProperty("name").drop());

    schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    Assert.assertFalse(schema.getClass("testIfExists").existsProperty("name"));

    graph.autoExecuteInTx(g -> g.schemaClass("testIfExists").
        schemaClassProperty("name").drop());
    Assert.assertFalse(schema.getClass("testIfExists").existsProperty("name"));
  }
}
