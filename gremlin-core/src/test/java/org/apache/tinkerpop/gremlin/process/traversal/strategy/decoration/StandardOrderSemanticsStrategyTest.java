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
package org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration;

import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.remote.traversal.strategy.decoration.RemoteStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class StandardOrderSemanticsStrategyTest {

    @Test
    public void shouldEnableFilteringForGlobalAndLocalOrderSteps() throws Exception {
        final Traversal.Admin<?, ?> traversal = __.order().order(Scope.local).asAdmin();
        final OrderGlobalStep<?, ?> global = TraversalHelper.getFirstStepOfAssignableClass(
                OrderGlobalStep.class, traversal).get();
        final OrderLocalStep<?, ?> local = TraversalHelper.getFirstStepOfAssignableClass(
                OrderLocalStep.class, traversal).get();
        disableFiltering(global);
        disableFiltering(local);

        StandardOrderSemanticsStrategy.instance().apply(traversal);

        assertTrue(global.isFilteringUnproductiveTraversers());
        assertTrue(local.isFilteringUnproductiveTraversers());
    }

    @Test
    public void shouldEnableFilteringInsideChildTraversals() throws Exception {
        final Traversal.Admin<?, ?> traversal = __.map(__.order()).asAdmin();
        final OrderGlobalStep<?, ?> childOrder = TraversalHelper.getStepsOfAssignableClassRecursively(
                OrderGlobalStep.class, traversal).get(0);
        disableFiltering(childOrder);
        final TraversalStrategies strategies = new DefaultTraversalStrategies();
        strategies.addStrategies(StandardOrderSemanticsStrategy.instance());
        traversal.setStrategies(strategies);

        traversal.applyStrategies();

        assertTrue(childOrder.isFilteringUnproductiveTraversers());
    }

    @Test
    public void shouldBeIdempotent() throws Exception {
        final Traversal.Admin<?, ?> traversal = __.order().asAdmin();
        final OrderGlobalStep<?, ?> order = TraversalHelper.getFirstStepOfAssignableClass(
                OrderGlobalStep.class, traversal).get();
        disableFiltering(order);

        StandardOrderSemanticsStrategy.instance().apply(traversal);
        StandardOrderSemanticsStrategy.instance().apply(traversal);

        assertTrue(order.isFilteringUnproductiveTraversers());
    }

    @Test
    public void shouldRunAfterRemoteStrategy() {
        final RemoteStrategy remoteStrategy = new RemoteStrategy(mock(RemoteConnection.class));

        assertTrue(remoteStrategy.applyPost().contains(StandardOrderSemanticsStrategy.class));
    }

    @Test
    public void shouldNotBelongToAnyRegisteredDefaultStrategySet() {
        assertFalse(TraversalStrategies.GlobalCache.getStrategies(Graph.class)
                .getStrategy(StandardOrderSemanticsStrategy.class).isPresent());
        assertFalse(TraversalStrategies.GlobalCache.getStrategies(EmptyGraph.class)
                .getStrategy(StandardOrderSemanticsStrategy.class).isPresent());
        assertFalse(TraversalStrategies.GlobalCache.getStrategies(GraphComputer.class)
                .getStrategy(StandardOrderSemanticsStrategy.class).isPresent());
    }

    private static void disableFiltering(final Object orderStep) throws Exception {
        final Field field = orderStep.getClass().getDeclaredField("filterUnproductiveTraversers");
        field.setAccessible(true);
        field.setBoolean(orderStep, false);
    }
}
