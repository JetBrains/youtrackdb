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
package org.apache.tinkerpop.gremlin.tinkergraph.process.computer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;
import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.MemoryComputeKey;
import org.apache.tinkerpop.gremlin.process.computer.MessageScope;
import org.apache.tinkerpop.gremlin.process.computer.Messenger;
import org.apache.tinkerpop.gremlin.process.computer.util.StaticVertexProgram;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.BulkSet;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

public class TinkerGraphComputerTest {

  @Test(timeout = 30000)
  public void shouldMergeMutableBroadcastValuesOnCoordinatorAfterEveryWorkerFinishes()
      throws Exception {
    // Both workers contribute once per key. No worker-local reduction can mask a shared merge.
    // Two rounds verify publication before terminate and clearing of each worker memory.
    assertSuccessfulRounds(2, 2);
  }

  @Test(timeout = 30000)
  public void shouldWaitForEmptyPartitionBeforeMergingPopulatedPartition() throws Exception {
    // An empty worker still runs its iteration callbacks and participates in the barrier.
    assertSuccessfulRounds(2, 1);
  }

  @Test(timeout = 30000)
  public void shouldCompleteEmptyRoundsWithoutInvokingReducers() throws Exception {
    // All worker maps remain empty. Both rounds must publish the unchanged initial values.
    assertSuccessfulRounds(2, 0);
  }

  @Test(timeout = 30000)
  public void shouldPublishAndClearWorkerMemoryWithOneWorker() throws Exception {
    // This also exercises the merge path on machines that support only one worker.
    assertSuccessfulRounds(1, 1);
  }

  @Test(timeout = 30000)
  public void shouldPropagateWorkerFailureWithoutMergingAnyPartialResults() throws Exception {
    assumeTrue("Two workers require at least two processors",
        Runtime.getRuntime().availableProcessors() >= 2);
    try (TinkerGraph graph = TinkerGraph.open()) {
      graph.addVertex(T.id, 0);
      graph.addVertex(T.id, 1);
      final FailedRoundProgram program = new FailedRoundProgram();
      try {
        graph.compute().workers(2).program(program).submit().get(10, TimeUnit.SECONDS);
        fail("The worker failure must reach the caller");
      } catch (ExecutionException exception) {
        Throwable cause = exception;
        while (cause != null && cause != program.failure) {
          cause = cause.getCause();
        }
        assertSame("The original worker failure must propagate", program.failure, cause);
      }
      assertEquals("No reducer may run for a failed round", 0, program.merges.get());
      assertEquals(0L, program.sharedMemory.<BulkSet<String>>get(RoundProgram.BULK).longSize());
      assertTrue(program.sharedMemory.<Map<String, Integer>>get(RoundProgram.COUNTS).isEmpty());
    }
  }

  private void assertSuccessfulRounds(final int workers, final int vertices) throws Exception {
    assumeTrue("Requested workers must be supported by the machine",
        Runtime.getRuntime().availableProcessors() >= workers);
    try (TinkerGraph graph = TinkerGraph.open()) {
      for (int i = 0; i < vertices; i++) {
        graph.addVertex();
      }
      final RoundProgram program = new RoundProgram(workers, vertices);
      final ComputerResult result = graph.compute().workers(workers).program(program)
          .submit().get(10, TimeUnit.SECONDS);
      program.assertValues(result.memory(), vertices * 6);
      assertEquals(1, result.memory().getIteration());
      assertEquals("Each populated worker merges two keys in each of two rounds",
          vertices * 4, program.merges.get());
    }
  }

  private static class RoundProgram extends StaticVertexProgram<Object> {

    private static final String BULK = "bulk";
    private static final String COUNTS = "counts";
    private final int workers;
    private final int vertices;
    private final CyclicBarrier startBarrier;
    private final AtomicInteger finishedWorkers = new AtomicInteger();
    final AtomicInteger merges = new AtomicInteger();
    private Thread coordinator;
    Memory sharedMemory;

    private RoundProgram(final int workers, final int vertices) {
      this.workers = workers;
      this.vertices = vertices;
      this.startBarrier = new CyclicBarrier(workers);
    }

    @Override
    public void setup(final Memory memory) {
      this.coordinator = Thread.currentThread();
      this.sharedMemory = memory;
      memory.set(BULK, new BulkSet<String>());
      memory.set(COUNTS, new HashMap<String, Integer>());
    }

    @Override
    public void workerIterationStart(final Memory memory) {
      try {
        // Force distinct worker tasks to enter the round together, including empty partitions.
        // No worker waits for a merge, since merges happen only after every task returns.
        this.startBarrier.await(5, TimeUnit.SECONDS);
      } catch (Exception exception) {
        throw new IllegalStateException("Workers did not enter the round together", exception);
      }
      assertValues(memory, this.vertices * 3 * memory.getIteration());
    }

    @Override
    public void execute(final Vertex vertex, final Messenger<Object> messenger,
        final Memory memory) {
      assertValues(memory, this.vertices * 3 * memory.getIteration());
      final BulkSet<String> contribution = new BulkSet<>();
      contribution.add("item", 3);
      memory.add(BULK, contribution);
      memory.add(COUNTS, new HashMap<>(Map.of("item", 3)));
    }

    @Override
    public void workerIterationEnd(final Memory memory) {
      // Broadcast reads must still see the previous round even after this worker has added values.
      assertValues(memory, this.vertices * 3 * memory.getIteration());
      this.finishedWorkers.incrementAndGet();
    }

    @Override
    public boolean terminate(final Memory memory) {
      assertSame(this.coordinator, Thread.currentThread());
      assertEquals(this.workers, this.finishedWorkers.get());
      assertValues(memory, this.vertices * 3 * (memory.getIteration() + 1));
      this.finishedWorkers.set(0);
      return memory.getIteration() == 1;
    }

    @Override
    public Set<MemoryComputeKey> getMemoryComputeKeys() {
      return new HashSet<>(Arrays.asList(
          MemoryComputeKey.of(BULK,
              checkedReducer((BulkSet<String> left, BulkSet<String> right) -> {
                left.addAll(right);
                return left;
              }), true, false),
          MemoryComputeKey.of(COUNTS,
              checkedReducer((Map<String, Integer> left, Map<String, Integer> right) -> {
                right.forEach((key, value) -> left.merge(key, value, Integer::sum));
                return left;
              }), true, false)));
    }

    private <A> BinaryOperator<A> checkedReducer(final BinaryOperator<A> reducer) {
      return (left, right) -> {
        this.merges.incrementAndGet();
        assertMergeAllowed();
        return reducer.apply(left, right);
      };
    }

    void assertMergeAllowed() {
      // A merge must run on the coordinator, after all end callbacks, not on a finishing worker.
      assertSame("Shared reducers must run on the coordinator", this.coordinator,
          Thread.currentThread());
      assertEquals("Every worker must finish before shared memory changes",
          this.workers, this.finishedWorkers.get());
    }

    void assertValues(final Memory memory, final int expected) {
      final BulkSet<String> bulk = memory.get(BULK);
      final Map<String, Integer> counts = memory.get(COUNTS);
      assertEquals(expected, bulk.longSize());
      assertEquals(expected, bulk.get("item"));
      assertEquals(expected, counts.getOrDefault("item", 0).intValue());
    }

    @Override
    public Set<MessageScope> getMessageScopes(final Memory memory) {
      return Collections.emptySet();
    }

    @Override
    public GraphComputer.ResultGraph getPreferredResultGraph() {
      return GraphComputer.ResultGraph.ORIGINAL;
    }

    @Override
    public GraphComputer.Persist getPreferredPersist() {
      return GraphComputer.Persist.NOTHING;
    }
  }

  private static final class FailedRoundProgram extends RoundProgram {

    private final IllegalStateException failure = new IllegalStateException("Worker failed");
    private final CountDownLatch successfulWorkerEnded = new CountDownLatch(1);

    private FailedRoundProgram() {
      super(2, 2);
    }

    @Override
    public void execute(final Vertex vertex, final Messenger<Object> messenger,
        final Memory memory) {
      super.execute(vertex, messenger, memory);
      if (vertex.id().equals(1)) {
        try {
          // Let a successful sibling finish its callbacks before this worker fails.
          assertTrue("The successful worker must finish",
              this.successfulWorkerEnded.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(exception);
        }
        throw this.failure;
      }
    }

    @Override
    public void workerIterationEnd(final Memory memory) {
      super.workerIterationEnd(memory);
      this.successfulWorkerEnded.countDown();
    }

    @Override
    void assertMergeAllowed() {
      fail("A failed round must not merge any worker memory");
    }
  }
}
