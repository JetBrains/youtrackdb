package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.api.query.Result;

public class ProduceExecutionStream implements ExecutionStream {

  private final ProduceResult producer;

  public ProduceExecutionStream(ProduceResult producer) {
    if (producer == null) {
      throw new NullPointerException();
    }
    this.producer = producer;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    return true;
  }

  @Override
  public Result next(CommandContext ctx) {
    return producer.produce(ctx);
  }

  @Override
  public void close(CommandContext ctx) {
  }
}
