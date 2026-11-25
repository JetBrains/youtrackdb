package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import org.junit.Test;

/** Captured cache permission is separate from immutable physical-plan ownership provenance. */
public class GremlinCacheScopeTest {

  /** Scope generation and permission remain fixed independently of a private fixture holder. */
  @Test
  public void scopeRetainsCapturedGenerationAndPermission() {
    var scope = new GremlinToMatchStrategy.CacheScope(7, true);
    var owned = GremlinPlanCache.Entry.owned(mock(InternalExecutionPlan.class), scope.generation);
    assertThat(scope.generation).isEqualTo(7);
    assertThat(scope.eligible).isTrue();
    assertThat(owned.generation).isEqualTo(7);
    assertThat(owned.sharedPlanTemplate).isFalse();
  }

  /** Value-equal private copies never inherit a publication's provenance, even after eviction. */
  @Test
  public void publishedHolderStaysSharedAndEqualPrivateCopyStaysOwned() {
    var db = mock(DatabaseSessionEmbedded.class);
    var cache = new GremlinPlanCache(8);
    var template = new ValueEqualPlan(new BasicCommandContext(), "template");
    var generation = cache.getGeneration();
    cache.putInternal("key", template, db, generation);
    var shared = cache.peekEntry("key", generation);
    assertThat(shared).isNotNull();
    assertThat(shared.sharedPlanTemplate).isTrue();
    var privateCopy = shared.value.copy(new BasicCommandContext());
    assertThat(privateCopy).isNotSameAs(shared.value).isEqualTo(shared.value);
    assertThat(shared.value).isEqualTo(privateCopy);
    assertThat(privateCopy.hashCode()).isEqualTo(shared.value.hashCode());
    var owned = GremlinPlanCache.Entry.owned(privateCopy, generation);
    assertThat(owned.sharedPlanTemplate).isFalse();
    cache.invalidate();
    assertThat(cache.peekEntry("key", generation)).isNull();
    assertThat(shared.sharedPlanTemplate).isTrue();
    assertThat(owned.sharedPlanTemplate).isFalse();
  }

  /** Denying cache access does not change ownership of a separately returned private fixture. */
  @Test
  public void ineligibleScopeKeepsPermissionSeparateFromOwnership() {
    var scope = new GremlinToMatchStrategy.CacheScope(7, false);
    var plan = mock(InternalExecutionPlan.class);
    var owned = GremlinPlanCache.Entry.owned(plan, scope.generation);
    assertThat(scope.eligible).isFalse();
    assertThat(scope.generation).isEqualTo(7);
    assertThat(owned.value).isSameAs(plan);
    assertThat(owned.sharedPlanTemplate).isFalse();
  }

  /** Real plans with value equality make accidental equality-based sharing observable. */
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
    public void close() {
      // This equality-only fixture has no execution steps or resources to release.
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
