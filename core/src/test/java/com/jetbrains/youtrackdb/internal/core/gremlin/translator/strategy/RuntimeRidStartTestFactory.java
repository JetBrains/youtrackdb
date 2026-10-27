package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/** Creates runtime RID starts for MATCH tests outside the translator package. */
public final class RuntimeRidStartTestFactory {

  private RuntimeRidStartTestFactory() {
  }

  public static RuntimeRidStart create(String alias, String aliasClass, int slot) {
    return new RuntimeRidStart(alias, aliasClass, slot);
  }
}
