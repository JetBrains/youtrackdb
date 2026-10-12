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
package org.apache.tinkerpop.gremlin.tinkergraph.process.traversal.step.map;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerFactory;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TinkerGraphOrderStepTest {

    @Test
    public void shouldPlaceKeylessVerticesFirstInAscendingGlobalOrder() {
        final GraphTraversalSource g = TinkerFactory.createModern().traversal();
        final List<Object> names = g.V().order().by("age", Order.asc).values("name").toList();

        // Both vertices have the same null sort key, so their relative order is intentionally unspecified.
        assertEquals(Set.of("lop", "ripple"), new HashSet<>(names.subList(0, 2)));
        assertEquals(List.of("vadas", "marko", "josh", "peter"), names.subList(2, 6));
    }

    @Test
    public void shouldPlaceKeylessVerticesLastInDescendingGlobalOrder() {
        final GraphTraversalSource g = TinkerFactory.createModern().traversal();
        final List<Object> names = g.V().order().by("age", Order.desc).values("name").toList();

        assertEquals(List.of("peter", "josh", "marko", "vadas"), names.subList(0, 4));
        assertEquals(Set.of("lop", "ripple"), new HashSet<>(names.subList(4, 6)));
    }

    @Test
    public void shouldPositionSingleKeylessVertexInBothGlobalDirections() {
        final GraphTraversalSource g = TinkerGraph.open().traversal();
        g.addV("person").property("name", "missing").iterate();
        g.addV("person").property("name", "young").property("age", 20).iterate();
        g.addV("person").property("name", "old").property("age", 40).iterate();

        assertEquals(List.of("missing", "young", "old"),
                g.V().order().by("age", Order.asc).values("name").toList());
        assertEquals(List.of("old", "young", "missing"),
                g.V().order().by("age", Order.desc).values("name").toList());
    }

    @Test
    public void shouldSortExplicitNullWithMissingProperty() {
        final Configuration configuration = new BaseConfiguration();
        configuration.setProperty(TinkerGraph.GREMLIN_TINKERGRAPH_ALLOW_NULL_PROPERTY_VALUES, true);
        final GraphTraversalSource g = TinkerGraph.open(configuration).traversal();
        g.addV("person").property("name", "missing").iterate();
        g.addV("person").property("name", "null").property("age", null).iterate();
        g.addV("person").property("name", "aged").property("age", 20).iterate();

        final List<Object> names = g.V().order().by("age", Order.asc).values("name").toList();

        // Missing and explicit null properties produce equal null keys, so their tie has no defined order.
        assertEquals(Set.of("missing", "null"), new HashSet<>(names.subList(0, 2)));
        assertEquals("aged", names.get(2));
    }

    @Test
    public void shouldApplySecondKeyAfterRetainingMissingFirstKey() {
        final GraphTraversalSource g = TinkerGraph.open().traversal();
        g.addV("person").property("name", "missing").iterate();
        g.addV("person").property("name", "beta").property("age", 20).iterate();
        g.addV("person").property("name", "alpha").property("age", 20).iterate();

        assertEquals(List.of("missing", "alpha", "beta"), g.V().order()
                .by("age", Order.asc).by("name", Order.asc).values("name").toList());
    }

    @Test
    public void shouldRetainVerticesWhenByTraversalYieldsNothing() {
        final GraphTraversalSource g = TinkerGraph.open().traversal();
        g.addV("person").property("name", "alpha").iterate();
        g.addV("person").property("name", "beta").iterate();

        final List<Object> names = g.V().order().by(__.values("absent")).values("name").toList();

        assertEquals(2, names.size());
        assertEquals(Set.of("alpha", "beta"), new HashSet<>(names));
    }

    @Test
    public void shouldRetainVertexRejectedByFilterInByTraversal() {
        final GraphTraversalSource g = TinkerGraph.open().traversal();
        g.addV("person").property("name", "low").property("age", 20).iterate();
        g.addV("person").property("name", "medium").property("age", 30).iterate();
        g.addV("person").property("name", "high").property("age", 40).iterate();

        assertEquals(List.of("low", "medium", "high"), g.V().order()
                .by(__.values("age").filter(__.is(P.gt(20))), Order.asc).values("name").toList());
    }

    @Test
    public void shouldKeepFilteringKeylessVerticesInLocalOrder() {
        final GraphTraversalSource g = TinkerFactory.createModern().traversal();

        assertEquals(List.of("vadas", "marko", "josh", "peter"), g.V().fold()
                .order(Scope.local).by("age", Order.asc).unfold().values("name").toList());
    }

    @Test
    public void shouldRestoreFilteredGlobalOrderWithStandardSemanticsStrategy() {
        final GraphTraversalSource g = TinkerFactory.createModern().traversal()
                .withStrategies(StandardOrderSemanticsStrategy.instance());

        assertEquals(List.of("vadas", "marko", "josh", "peter"),
                g.V().order().by("age", Order.asc).values("name").toList());
    }

    @Test
    public void shouldPassRawNullToCustomComparatorForMissingKey() {
        final GraphTraversalSource g = TinkerGraph.open().traversal();
        g.addV("person").property("name", "missing").iterate();
        g.addV("person").property("name", "aged").property("age", 20).iterate();
        final List<Object> observedValues = new ArrayList<>();
        final Comparator<Object> comparator = (left, right) -> {
            observedValues.add(left);
            observedValues.add(right);
            if (left == right) {
                return 0;
            }
            if (left == null) {
                return -1;
            }
            if (right == null) {
                return 1;
            }
            return ((Integer) left).compareTo((Integer) right);
        };

        assertEquals(List.of("missing", "aged"),
                g.V().order().by("age", comparator).values("name").toList());
        assertTrue(observedValues.contains(null));
    }
}
