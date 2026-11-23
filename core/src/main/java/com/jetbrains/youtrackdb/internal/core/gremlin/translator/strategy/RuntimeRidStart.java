package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.Objects;

/**
 * Value-free description of a Gremlin MATCH start whose RID is bound at execution time.
 * The translator owns creation. Other packages can carry and inspect the descriptor.
 */
public final class RuntimeRidStart {

  private final String alias;
  private final String aliasClass;
  private final int parameterSlot;

  RuntimeRidStart(String alias, String aliasClass, int parameterSlot) {
    this.alias = Objects.requireNonNull(alias, "runtime RID start alias must not be null");
    this.aliasClass = Objects.requireNonNull(aliasClass,
        "runtime RID start alias class must not be null");
    if (alias.isEmpty() || aliasClass.isEmpty()) {
      throw new IllegalArgumentException("runtime RID start alias and class must not be empty");
    }
    if (parameterSlot < 0) {
      throw new IllegalArgumentException("runtime RID start parameter slot must not be negative");
    }
    this.parameterSlot = parameterSlot;
  }

  public String alias() {
    return alias;
  }

  public String aliasClass() {
    return aliasClass;
  }

  public int parameterSlot() {
    return parameterSlot;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RuntimeRidStart start
        && alias.equals(start.alias)
        && aliasClass.equals(start.aliasClass)
        && parameterSlot == start.parameterSlot;
  }

  @Override
  public int hashCode() {
    return Objects.hash(alias, aliasClass, parameterSlot);
  }
}
