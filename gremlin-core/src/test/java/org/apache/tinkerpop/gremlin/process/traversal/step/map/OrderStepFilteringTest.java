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
package org.apache.tinkerpop.gremlin.process.traversal.step.map;

import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class OrderStepFilteringTest {

    @Test
    public void shouldUseScopeSpecificDefaultsAndOnlyEnableFiltering() throws Exception {
        final OrderGlobalStep<?, ?> global = globalOrder();
        final OrderLocalStep<?, ?> local = localOrder();

        assertFalse(global.isFilteringUnproductiveTraversers());
        assertTrue(local.isFilteringUnproductiveTraversers());
        disableFiltering(global);
        disableFiltering(local);
        assertFalse(global.isFilteringUnproductiveTraversers());
        assertFalse(local.isFilteringUnproductiveTraversers());
        global.enableFilteringUnproductiveTraversers();
        local.enableFilteringUnproductiveTraversers();
        assertTrue(global.isFilteringUnproductiveTraversers());
        assertTrue(local.isFilteringUnproductiveTraversers());
    }

    @Test
    public void shouldRetainMissingGlobalKeyAtComparatorExtremes() {
        final Map<String, Object> missing = map("name", "missing");
        final Map<String, Object> young = map("name", "young", "age", 20);
        final Map<String, Object> old = map("name", "old", "age", 40);
        final Traversal.Admin<?, ?> ascending = __.inject(old, missing, young)
                .order().by("age", Order.asc).asAdmin();
        final Traversal.Admin<?, ?> descending = __.inject(young, missing, old)
                .order().by("age", Order.desc).asAdmin();

        assertEquals(Arrays.asList(missing, young, old), ascending.toList());
        assertEquals(Arrays.asList(old, young, missing), descending.toList());
    }

    @Test
    public void shouldKeepRetainedGlobalProjectionsAlignedWithComparators() {
        final Map<String, Object> missingAge = map("name", "z");
        final Map<String, Object> first = map("name", "a", "age", 20);
        final Map<String, Object> second = map("name", "b", "age", 20);
        final Traversal.Admin<?, ?> traversal = __.inject(second, missingAge, first)
                .order().by("age", Order.asc).by("name", Order.asc).asAdmin();

        assertEquals(Arrays.asList(missingAge, first, second), traversal.toList());
    }

    @Test
    public void shouldRetainMissingKeyInLocalOrder() throws Exception {
        // The local step keeps filtering by default permanently, so this reflection stays.
        final Map<String, Object> missing = map("name", "missing");
        final Map<String, Object> young = map("name", "young", "age", 20);
        final Map<String, Object> old = map("name", "old", "age", 40);
        final Traversal.Admin<?, ?> traversal = __.inject(Arrays.asList(old, missing, young))
                .order(Scope.local).by("age", Order.asc).asAdmin();
        disableFiltering(localOrderStep(traversal));

        assertEquals(Arrays.asList(missing, young, old), traversal.next());
    }

    @Test
    public void shouldIncludeFilteringStateInHashCode() throws Exception {
        final OrderGlobalStep<?, ?> globalWithFiltering = globalOrder();
        final OrderGlobalStep<?, ?> globalWithoutFiltering = globalOrder();
        final OrderLocalStep<?, ?> localWithFiltering = localOrder();
        final OrderLocalStep<?, ?> localWithoutFiltering = localOrder();
        globalWithFiltering.enableFilteringUnproductiveTraversers();
        localWithFiltering.enableFilteringUnproductiveTraversers();
        disableFiltering(localWithoutFiltering);

        assertNotEquals(globalWithFiltering.hashCode(), globalWithoutFiltering.hashCode());
        assertNotEquals(localWithFiltering.hashCode(), localWithoutFiltering.hashCode());
    }

    @Test
    public void shouldPreserveFilteringStateWhenCloned() throws Exception {
        final OrderGlobalStep<?, ?> global = globalOrder();
        final OrderLocalStep<?, ?> local = localOrder();
        global.enableFilteringUnproductiveTraversers();
        disableFiltering(local);

        final OrderGlobalStep<?, ?> globalClone = global.clone();
        final OrderLocalStep<?, ?> localClone = local.clone();

        assertTrue(globalClone.isFilteringUnproductiveTraversers());
        assertFalse(localClone.isFilteringUnproductiveTraversers());
    }

    private static OrderGlobalStep<?, ?> globalOrder() {
        return orderStep(__.order().asAdmin());
    }

    private static OrderLocalStep<?, ?> localOrder() {
        return localOrderStep(__.order(Scope.local).asAdmin());
    }

    private static OrderGlobalStep<?, ?> orderStep(final Traversal.Admin<?, ?> traversal) {
        return TraversalHelper.getFirstStepOfAssignableClass(OrderGlobalStep.class, traversal).get();
    }

    private static OrderLocalStep<?, ?> localOrderStep(final Traversal.Admin<?, ?> traversal) {
        return TraversalHelper.getFirstStepOfAssignableClass(OrderLocalStep.class, traversal).get();
    }

    private static Map<String, Object> map(final Object... entries) {
        final Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private static void disableFiltering(final Object orderStep) throws Exception {
        final Field field = orderStep.getClass().getDeclaredField("filterUnproductiveTraversers");
        field.setAccessible(true);
        field.setBoolean(orderStep, false);
    }
}
