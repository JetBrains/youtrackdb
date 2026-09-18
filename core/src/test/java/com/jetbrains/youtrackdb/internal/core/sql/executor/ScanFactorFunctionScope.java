package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import java.util.UUID;

/** Registers a uniquely named SQL function that changes the ordered-scan factor when executed. */
public final class ScanFactorFunctionScope extends SQLFunctionAbstract implements AutoCloseable {

  private final double factor;
  private final String name;

  public ScanFactorFunctionScope(double factor) {
    this("track10SetScanFactor" + UUID.randomUUID().toString().replace("-", ""), factor);
  }

  private ScanFactorFunctionScope(String name, double factor) {
    super(name, 0, 0);
    this.name = name;
    this.factor = factor;
    SQLEngine.registerFunction(name, this);
  }

  public String name() {
    return name;
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] iParams,
      CommandContext iContext) {
    GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR.setValue(factor);
    return true;
  }

  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    return name + "()";
  }

  @Override
  public void close() {
    SQLEngine.unregisterFunction(name);
  }
}
