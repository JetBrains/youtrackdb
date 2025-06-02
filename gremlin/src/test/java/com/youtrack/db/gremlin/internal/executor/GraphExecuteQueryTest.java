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

package com.youtrack.db.gremlin.internal.executor;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.youtrack.db.gremlin.internal.GraphBaseTest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

public class GraphExecuteQueryTest extends GraphBaseTest {

  @Test
  public void testFailingQueryGremlinSimple() {
    graph.addVertex(T.label, "Person", "name", "John");
    graph.tx().commit();

    session.executeInTx(transaction -> {
      try (var result = transaction.computeScript("gremlin",
          "g.V().hasLabel('Person','vl3').count()")) {
        Assert.assertEquals(1, result.stream().count());
      }
    });
  }

  @Test
  public void testExecuteGremlinSimpleQueryTest() {
    graph.addVertex(T.label, "Person", "name", "John");
    graph.addVertex(T.label, "Person", "name", "Luke");
    graph.tx().commit();

    session.executeInTx(transaction -> {
      try (var gremlin = transaction.computeScript("gremlin", "g.V()")) {
        Assert.assertEquals(2, gremlin.stream().count());
      }
    });
  }

  @Test
  public void testExecuteGremlinCountQueryTest() {
    graph.addVertex(T.label, "Person", "name", "John");
    graph.addVertex(T.label, "Person", "name", "Luke");
    graph.tx().commit();

    session.executeInTx(transaction -> {
      try (var gremlin = session.computeScript("gremlin", "g.V().count()")) {
        var iterator = gremlin.stream().iterator();
        Assert.assertTrue(iterator.hasNext());

        var result = iterator.next();
        var count = result.getLong("value");
        Assert.assertNotNull(count);
        Assert.assertEquals(2L, count.longValue());
      }
    });
  }

  @Test
  public void testExecuteGremlinVertexQueryTest() {
    graph.addVertex(T.label, "Person", "name", "John");
    graph.addVertex(T.label, "Person", "name", "Luke");
    graph.tx().commit();

    session.executeInTx(transaction -> {
      try (var gremlin = transaction.computeScript("gremlin",
          "g.V().hasLabel('Person').has('name','Luke')")) {
        var collected = gremlin.stream().toList();
        Assert.assertEquals(1, collected.size());

        var result = collected.getFirst();
        var vertex = result.asVertex();
        Assert.assertEquals("Luke", vertex.getString("name"));
      }
    });
  }

  @Test
  public void testExecuteGremlinEdgeQueryTest() {
    var v1 = graph.addVertex(T.label, "Person", "name", "John");
    var v2 = graph.addVertex(T.label, "Person", "name", "Luke");

    v1.addEdge("HasFriend", v2, "since", new Date());
    graph.tx().commit();

    session.executeInTx(transaction -> {
      try (var gremlin = transaction.computeScript("gremlin", "g.E().hasLabel('HasFriend')")) {
        var collected = gremlin.stream().toList();
        Assert.assertEquals(1, collected.size());

        var result = collected.getFirst();
        var edge = result.asStatefulEdge();
        Assert.assertNotNull(edge.getProperty("since"));
      }
    });
  }

  @Test
  public void testExecuteGremlinPathQueryTest() {
    var v1 = graph.addVertex(T.label, "Person", "name", "John");
    var v2 =
        graph.addVertex(
            T.label,
            "Person",
            "name",
            "Luke",
            "values",
            new ArrayList<String>() {
              {
                add("first");
                add("second");
              }
            });

    v1.addEdge("HasFriend", v2, "since", new Date());
    graph.tx().commit();

    session.executeInTx(transaction -> {
      try (var gremlin =
          transaction.computeScript("gremlin",
              "g.V().has('name','John').out().values('values').path()")) {

        var collected = gremlin.stream().toList();
        Assert.assertEquals(1, collected.size());

        var result = collected.getFirst();

        var results = result.getEmbeddedList("value");
        Assert.assertNotNull(results);
        Assert.assertEquals(3, results.size());

        Assert.assertTrue(results.getFirst() instanceof Identifiable);

        var identifiable = (Identifiable) results.get(0);
        Assert.assertNotNull(transaction.loadVertex(identifiable));

        Assert.assertTrue(results.get(1) instanceof Identifiable);

        identifiable = (Identifiable) results.get(1);
        Assert.assertNotNull(transaction.loadVertex(identifiable));

        Assert.assertTrue(results.get(2) instanceof Collection);

        var coll = (List<?>) results.get(2);
        Assert.assertEquals(2, coll.size());
      }
    });
  }
}
