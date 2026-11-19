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

package org.apache.tinkerpop.gremlin.process.computer.clustering.peerpressure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.apache.tinkerpop.gremlin.process.computer.MessageScope;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.PageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.lambda.HaltedTraversersCountTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.util.PureTraversal;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.junit.Test;

public class PeerPressureVertexProgramTest {

  /** Each worker owns a distinct initial-vote traversal while configuration and scope identity survive. */
  @Test
  public void shouldCloneInitialVoteTraversalAndPreserveConfiguration() throws Exception {
    final PeerPressureVertexProgram original = PeerPressureVertexProgram.build()
        .initialVoteStrength(new HaltedTraversersCountTraversal())
        .edges(__.outE("knows").asAdmin())
        .maxIterations(7).distributeVote(true).property("community")
        .create(EmptyGraph.instance());
    final var first = (PeerPressureVertexProgram) original.clone();
    final var second = (PeerPressureVertexProgram) original.clone();

    assertNotSame(original, first);
    assertNotSame(original, second);
    assertNotSame(first, second);
    final var originalTraversal = initialVoteTraversal(original);
    final var firstTraversal = initialVoteTraversal(first);
    final var secondTraversal = initialVoteTraversal(second);
    assertNotSame(originalTraversal, firstTraversal);
    assertNotSame(firstTraversal, secondTraversal);
    assertNotSame(originalTraversal.get(), firstTraversal.get());
    assertNotSame(originalTraversal.get(), secondTraversal.get());
    assertNotSame(firstTraversal.get(), secondTraversal.get());
    for (final var copy : new PeerPressureVertexProgram[] {first, second}) {
      assertEquals(7, field(copy, "maxIterations"));
      assertEquals(true, field(copy, "distributeVote"));
      assertEquals("community", field(copy, "property"));
      assertSame(field(original, "edgeTraversal"), field(copy, "edgeTraversal"));
      assertSame(field(original, "voteScope"), field(copy, "voteScope"));
      assertSame(field(original, "countScope"), field(copy, "countScope"));
    }
  }

  /** Programs without an initial-vote traversal still produce independent worker objects. */
  @Test
  public void shouldCloneDefaultProgramWithoutInitialVoteTraversal() throws Exception {
    final PeerPressureVertexProgram original = PeerPressureVertexProgram.build().create(EmptyGraph.instance());
    final var copy = (PeerPressureVertexProgram) original.clone();
    assertNotSame(original, copy);
    assertNull(initialVoteTraversal(copy));
    assertEquals(field(original, "maxIterations"), field(copy, "maxIterations"));
    assertEquals(field(original, "distributeVote"), field(copy, "distributeVote"));
    assertEquals(field(original, "property"), field(copy, "property"));
    assertSame(field(original, "voteScope"), field(copy, "voteScope"));
    assertSame(field(original, "countScope"), field(copy, "countScope"));
  }

  /** PeerPressure message traversals are fresh clones and never fill the shared lazy cache. */
  @Test
  public void shouldLeavePeerPressureEdgeCacheEmpty() throws Exception {
    final var program = PeerPressureVertexProgram.build()
        .edges(__.outE("knows").asAdmin()).create(EmptyGraph.instance());
    assertUncachedEdgeSupplier(program, "voteScope");
  }

  /** PageRank message traversals also clone the read-only source without shared cache writes. */
  @Test
  public void shouldLeavePageRankEdgeCacheEmpty() throws Exception {
    final var program = PageRankVertexProgram.build()
        .edges(__.outE("knows").asAdmin()).create(EmptyGraph.instance());
    assertUncachedEdgeSupplier(program, "incidentMessageScope");
  }

  private static void assertUncachedEdgeSupplier(final Object program, final String scopeField)
      throws Exception {
    final var edgeTraversal = (PureTraversal<?, ?>) field(program, "edgeTraversal");
    final var scope = (MessageScope.Local<?>) field(program, scopeField);
    assertNull(field(edgeTraversal, "cachedTraversal"));
    final var first = scope.getIncidentTraversal().get();
    final var second = scope.getIncidentTraversal().get();
    assertNotSame(first, second);
    assertEquals(first, second);
    assertEquals(edgeTraversal.getPure(), first);
    assertNull(field(edgeTraversal, "cachedTraversal"));
  }

  private static PureTraversal<?, ?> initialVoteTraversal(final PeerPressureVertexProgram program)
      throws Exception {
    return (PureTraversal<?, ?>) field(program, "initialVoteStrengthTraversal");
  }

  private static Object field(final Object object, final String name) throws Exception {
    final var field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }
}
