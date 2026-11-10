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

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.StepTest;

import java.util.Collections;
import java.util.List;

/**
 * @author Daniel Kuppitz (http://gremlin.guru)
 */
public class FoldStepTest extends StepTest {

    /** Only the built-in supplier and reducer prove a pure default fold. */
    @org.junit.Test
    public void shouldIdentifyOnlyUnmodifiedDefaultFold() {
        FoldStep<?, ?> standard = (FoldStep<?, ?>) __.fold().asAdmin().getEndStep();
        org.junit.Assert.assertTrue(standard.isDefaultFold());
        FoldStep<?, ?> seeded = (FoldStep<?, ?>) __.fold(0, (a, b) -> a).asAdmin().getEndStep();
        org.junit.Assert.assertFalse(seeded.isDefaultFold());
        FoldStep<Object, Object> replaced = new FoldStep<>(__.identity().asAdmin());
        replaced.setReducingBiOperator((a, b) -> a);
        org.junit.Assert.assertFalse(replaced.isDefaultFold());
    }

    @Override
    protected List<Traversal> getTraversals() {
        return Collections.singletonList(__.fold());
    }
}
