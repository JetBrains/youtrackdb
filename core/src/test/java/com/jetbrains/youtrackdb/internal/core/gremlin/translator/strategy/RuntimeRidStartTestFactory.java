package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/** Creates runtime RID starts for MATCH tests outside the translator package. */
public final class RuntimeRidStartTestFactory {

  private RuntimeRidStartTestFactory() {
  }

  /** Publish through the real cache without widening its production visibility. */
  public static void cache(String key,
      com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan plan,
      com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db) {
    var cache = GremlinPlanCache.instance(db);
    cache.putInternal(key, plan, db, cache.getGeneration());
  }

  public static RuntimeRidStart create(String alias, String aliasClass, int slot) {
    return new RuntimeRidStart(alias, aliasClass, slot);
  }
}
