/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the interface default implementation of {@link ExecutionStep#toResult}
 * using anonymous stubs, without requiring a database session.
 *
 * <p>Why standalone (no DbTestBase): {@link ResultInternal#ResultInternal}
 * accepts a nullable session, and {@code setProperty} guards session access
 * through {@code assert checkSession()} which returns true when
 * {@code session == null}. This means the default {@code toResult}
 * implementation is fully reachable without wiring up a database, exposing
 * its shape contract (property keys, {@code getSubSteps} invocation pattern,
 * recursive {@code toResult} dispatch) to cheap unit tests.
 */
public class ExecutionStepToResultTest {

  /**
   * Stub {@link ExecutionStep} that counts how many times
   * {@link #getSubSteps} is invoked. This lets us pin the duplicate call
   * in the default {@code toResult} implementation (line 41): the method
   * invokes {@code getSubSteps()} once on line 41 with the return value
   * discarded, then invokes it again on line 44 to stream and recurse.
   * The discard is harmless for test stubs but causes wasteful recomputation
   * when {@code getSubSteps} is expensive.
   */
  private static final class CountingStub implements ExecutionStep {

    private final String name;
    private final String type;
    @Nullable private final String description;
    private final long cost;
    private final List<ExecutionStep> subSteps;
    private final AtomicInteger subStepCalls = new AtomicInteger();

    CountingStub(
        String name,
        String type,
        @Nullable String description,
        long cost,
        List<ExecutionStep> subSteps) {
      this.name = name;
      this.type = type;
      this.description = description;
      this.cost = cost;
      this.subSteps = subSteps;
    }

    int subStepCalls() {
      return subStepCalls.get();
    }

    @Nonnull
    @Override
    public String getName() {
      return name;
    }

    @Nonnull
    @Override
    public String getType() {
      return type;
    }

    @Nullable @Override
    public String getDescription() {
      return description;
    }

    @Nonnull
    @Override
    public List<ExecutionStep> getSubSteps() {
      subStepCalls.incrementAndGet();
      return subSteps;
    }

    @Override
    public long getCost() {
      return cost;
    }
  }

  @Test
  public void testToResultPopulatesAllPropertyKeysWithNullSession() {
    var step = new CountingStub("stepName", "stepType", "desc", 42L, List.of());
    Result result = step.toResult(null);
    Assert.assertNotNull(result);
    // Required property keys populated by the default toResult.
    Assert.assertEquals("stepName", result.getProperty("name"));
    Assert.assertEquals("stepType", result.getProperty("type"));
    Assert.assertEquals(step.getClass().getName(), result.getProperty(
        InternalExecutionPlan.JAVA_TYPE));
    Assert.assertEquals(Long.valueOf(42L), result.getProperty("cost"));
    Assert.assertEquals("desc", result.getProperty("description"));
    // subSteps present, empty for a leaf step.
    List<?> subSteps = result.getProperty("subSteps");
    Assert.assertNotNull(subSteps);
    Assert.assertTrue(subSteps.isEmpty());
  }

  @Test
  public void testToResultWithNullDescriptionSetsNullProperty() {
    // description is @Nullable — the default toResult does NOT guard it.
    // It must still round-trip as null in the result.
    var step = new CountingStub("n", "t", null, -1L, List.of());
    Result result = step.toResult(null);
    Assert.assertNull(result.getProperty("description"));
  }

  @Test
  public void testToResultDefaultCostIsNegativeOne() {
    // ExecutionStep.getCost() default returns -1L. Use an anonymous subclass
    // that does NOT override getCost to confirm the default flows into the
    // result.
    var step = new ExecutionStep() {
      @Nonnull
      @Override
      public String getName() {
        return "n";
      }

      @Nonnull
      @Override
      public String getType() {
        return "t";
      }

      @Nullable @Override
      public String getDescription() {
        return null;
      }

      @Nonnull
      @Override
      public List<ExecutionStep> getSubSteps() {
        return List.of();
      }
    };
    Result result = step.toResult(null);
    Assert.assertEquals(Long.valueOf(-1L), result.getProperty("cost"));
  }

  @Test
  public void testToResultWithSubStepsRecurses() {
    var child1 = new CountingStub("child1", "t", null, 1L, List.of());
    var child2 = new CountingStub("child2", "t", null, 2L, List.of());
    var parent = new CountingStub("parent", "t", null, 0L,
        List.<ExecutionStep>of(child1, child2));
    Result result = parent.toResult(null);
    List<?> subStepResults = result.getProperty("subSteps");
    Assert.assertNotNull(subStepResults);
    Assert.assertEquals(2, subStepResults.size());
    Assert.assertTrue("each element must itself be a Result",
        subStepResults.get(0) instanceof Result);
    Result child1Result = (Result) subStepResults.get(0);
    Result child2Result = (Result) subStepResults.get(1);
    Assert.assertEquals("child1", child1Result.getProperty("name"));
    Assert.assertEquals("child2", child2Result.getProperty("name"));
    // Children's subSteps are empty lists of Results.
    Assert.assertEquals(0, ((List<?>) child1Result.getProperty("subSteps")).size());
    Assert.assertEquals(0, ((List<?>) child2Result.getProperty("subSteps")).size());
  }

  /**
   * Pins the duplicate {@code getSubSteps()} invocation in the default
   * {@code toResult} body: line 41 calls it with the return value discarded,
   * line 44 calls it again to iterate. The parent stub therefore observes
   * exactly 2 invocations.
   * WHEN-FIXED: if the duplicate call on line 41 of ExecutionStep.java is
   * removed, the assertion below flips to {@code assertEquals(1, …)}. That
   * is a coordinated fix: delete the discard call and update this assertion
   * together.
   */
  @Test
  public void testToResultInvokesGetSubStepsTwicePerStep() {
    var step = new CountingStub("only", "t", null, 0L, List.of());
    step.toResult(null);
    Assert.assertEquals(
        "ExecutionStep.toResult default invokes getSubSteps() twice: once"
            + " with the return value discarded, once to iterate. Pin-test"
            + " intentionally documents this wasted call.",
        2,
        step.subStepCalls());
  }

  @Test
  public void testToResultRecursesThroughMultipleLevels() {
    // Depth > 1: a bug that flattened sub-step iteration (losing recursion)
    // would keep passing the depth-1 test above, so pin grandchildren here.
    var leaf = new CountingStub("leaf", "t", null, 0L, List.of());
    var mid = new CountingStub("mid", "t", null, 0L, List.<ExecutionStep>of(leaf));
    var root = new CountingStub("root", "t", null, 0L, List.<ExecutionStep>of(mid));
    Result result = root.toResult(null);
    List<?> level1 = result.getProperty("subSteps");
    Assert.assertNotNull(level1);
    Assert.assertEquals(1, level1.size());
    Result midResult = (Result) level1.get(0);
    List<?> level2 = midResult.getProperty("subSteps");
    Assert.assertNotNull(level2);
    Assert.assertEquals(1, level2.size());
    Result leafResult = (Result) level2.get(0);
    Assert.assertEquals("leaf", leafResult.getProperty("name"));
    Assert.assertEquals(0, ((List<?>) leafResult.getProperty("subSteps")).size());
  }
}
