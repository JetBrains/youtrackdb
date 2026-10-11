package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import org.junit.Test;

/** Direct tests of lazy shared-plan tracking and identity-based ownership. */
public class GremlinCacheScopeTest {

  /** Creating a scope and checking an unrecorded plan must not allocate the identity set. */
  @Test
  public void ownershipCheckBeforeRecordingDoesNotAllocate() {
    var scope = new GremlinToMatchStrategy.CacheScope(7, true);
    var plan = mock(InternalExecutionPlan.class);
    assertThat(scope.hasSharedPlanSet()).isFalse();
    assertThat(scope.isShared(plan)).isFalse();
    assertThat(scope.hasSharedPlanSet()).isFalse();
  }

  /** Recording creates the set, retains exact templates, and excludes an equal private copy. */
  @Test
  public void recordedTemplatesAreSharedButTheirCopiesArePrivate() {
    var scope = new GremlinToMatchStrategy.CacheScope(7, true);
    var template = new ValueEqualPlan(new BasicCommandContext(), "template");
    assertThat(scope.shared(template)).isSameAs(template);
    assertThat(scope.hasSharedPlanSet()).isTrue();
    assertThat(scope.isShared(template)).isTrue();
    var privateCopy = template.copy(new BasicCommandContext());
    assertThat(privateCopy).isNotSameAs(template).isEqualTo(template);
    assertThat(template).isEqualTo(privateCopy);
    assertThat(privateCopy.hashCode()).isEqualTo(template.hashCode());
    assertThat(scope.isShared(privateCopy)).isFalse();
    var otherTemplate = new ValueEqualPlan(new BasicCommandContext(), "other");
    assertThat(scope.shared(otherTemplate)).isSameAs(otherTemplate);
    assertThat(scope.isShared(template)).isTrue();
    assertThat(scope.isShared(otherTemplate)).isTrue();
  }

  /** An ineligible scope must deny shared ownership both before and after an explicit record. */
  @Test
  public void ineligibleScopeNeverReportsSharedOwnership() {
    var scope = new GremlinToMatchStrategy.CacheScope(7, false);
    var plan = mock(InternalExecutionPlan.class);
    assertThat(scope.isShared(plan)).isFalse();
    assertThat(scope.hasSharedPlanSet()).isFalse();
    assertThat(scope.shared(plan)).isSameAs(plan);
    assertThat(scope.hasSharedPlanSet()).isTrue();
    assertThat(scope.isShared(plan)).isFalse();
  }

  /** Real plans with value equality distinguish identity tracking from equality-based sets. */
  private static final class ValueEqualPlan extends SelectExecutionPlan {

    private final String key;

    private ValueEqualPlan(CommandContext ctx, String key) {
      super(ctx);
      this.key = key;
    }

    @Override
    public InternalExecutionPlan copy(CommandContext ctx) {
      return new ValueEqualPlan(ctx, key);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ValueEqualPlan plan && key.equals(plan.key);
    }

    @Override
    public int hashCode() {
      return key.hashCode();
    }
  }
}
