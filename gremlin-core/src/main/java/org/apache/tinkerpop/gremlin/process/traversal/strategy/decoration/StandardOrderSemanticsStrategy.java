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

import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

/**
 * Configures global {@code order()} steps to use standard Apache TinkerPop filtering semantics by
 * filtering traversers with unproductive {@code by()} modulators. By default, global {@code order()}
 * retains traversers with missing or {@code null} sort keys. This strategy is an opt-in compatibility
 * mechanism that restores filtering semantics for global ordering. Local {@code order(local)} already
 * keeps filtering semantics.
 */
public final class StandardOrderSemanticsStrategy
        extends AbstractTraversalStrategy<TraversalStrategy.DecorationStrategy>
        implements TraversalStrategy.DecorationStrategy {

    private static final StandardOrderSemanticsStrategy INSTANCE = new StandardOrderSemanticsStrategy();

    private StandardOrderSemanticsStrategy() {
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        TraversalHelper.getStepsOfClass(OrderGlobalStep.class, traversal)
                .forEach(OrderGlobalStep::enableFilteringUnproductiveTraversers);
        TraversalHelper.getStepsOfClass(OrderLocalStep.class, traversal)
                .forEach(OrderLocalStep::enableFilteringUnproductiveTraversers);
    }

    public static StandardOrderSemanticsStrategy instance() {
        return INSTANCE;
    }

    public static StandardOrderSemanticsStrategy create(final Configuration configuration) {
        return INSTANCE;
    }
}
