/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.jetbrains.youtrackdb.internal.core.gremlin.executor;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.StreamUtils;
import java.util.Collection;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

public class GraphExecuteFunctionTest extends GraphBaseTest {

  @Test
  public void testExecuteGremlinSimpleFunctionTest() {
    graph.addVertex(T.label, "Person", "name", "John");
    graph.addVertex(T.label, "Person", "name", "Luke");
    graph.tx().commit();

    var tx = session.begin();
    var functionLibrary = session.getMetadata().getFunctionLibrary();

    var testGremlin = functionLibrary.createFunction("testGremlin");
    testGremlin.setLanguage("gremlin-groovy");
    testGremlin.setCode("g.V()");

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var gremlin = (Iterator<?>) testGremlin.executeInContext(context);

    Assert.assertEquals(2, StreamUtils.asStream(gremlin).count());
    tx.commit();
  }

  @Test
  public void testExecuteGremlinFunctionCountQueryTest() {
    graph.addVertex(T.label, "Person", "name", "John");
    graph.addVertex(T.label, "Person", "name", "Luke");
    graph.tx().commit();

    var tx = session.begin();

    var functionLibrary = session.getMetadata().getFunctionLibrary();

    var testGremlin = functionLibrary.createFunction("testGremlin");
    testGremlin.setLanguage("gremlin-groovy");
    testGremlin.setCode("g.V().count()");

    @SuppressWarnings("deprecation")
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    @SuppressWarnings("unchecked") var gremlin = (Iterator<Result>) testGremlin.executeInContext(
        context);

    Assert.assertTrue(gremlin.hasNext());
    var result = gremlin.next();
    Assert.assertEquals(2L, result.getLong("value").longValue());
    tx.commit();
  }

  @Test
  public void testExecuteGremlinSqlFunctionInvokeTest() {
    graph.addVertex(T.label, "Person", "name", "John");
    graph.addVertex(T.label, "Person", "name", "Luke");

    graph.tx().commit();

    var tx = session.begin();

    var functionLibrary = session.getMetadata().getFunctionLibrary();

    var testGremlin = functionLibrary.createFunction("testGremlin");
    testGremlin.setLanguage("gremlin-groovy");
    testGremlin.setCode("g.V()");

    try (var resultSet = session.execute("select testGremlin() as gremlin")) {
      var iterator = resultSet.stream().iterator();
      Assert.assertTrue(iterator.hasNext());
      var result = iterator.next();
      var collection = (Collection<?>) result.getLinkList("gremlin");
      Assert.assertNotNull(collection);
      Assert.assertEquals(2, collection.size());
    }
    tx.commit();
  }


  @Test
  public void testExecuteGremlinSqlExpandFunctionInvokeTest() {
    graph.addVertex(T.label, "Person", "name", "John");
    graph.addVertex(T.label, "Person", "name", "Luke");

    graph.tx().commit();

    session.executeInTx(transaction -> {
      var functionLibrary = session.getMetadata().getFunctionLibrary();
      var testGremlin = functionLibrary.createFunction("testGremlin");

      testGremlin.setLanguage("gremlin-groovy");
      testGremlin.setCode("g.V()");

      try (var gremlin = transaction.query("select expand(testGremlin())")) {
        var collect = gremlin.stream().toList();
        Assert.assertEquals(2, collect.size());

        collect.forEach(
            (res) -> {
              Assert.assertTrue(res.isVertex());
              var oVertex = res.asVertex();
              Assert.assertEquals(
                  "Person", oVertex.getSchemaClassName());
            });
      }
    });
  }
}
