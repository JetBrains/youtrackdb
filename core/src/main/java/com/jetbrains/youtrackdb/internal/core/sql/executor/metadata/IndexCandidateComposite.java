package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.List;

public class IndexCandidateComposite implements IndexCandidate {

  private final String index;
  private final Operation operation;
  private final List<ImmutableSchemaProperty> properties;

  public IndexCandidateComposite(String index, Operation operation,
      List<ImmutableSchemaProperty> properties) {
    this.index = index;
    this.operation = operation;
    this.properties = properties;
  }

  @Override
  public String getName() {
    return index;
  }

  @Override
  public IndexCandidate invert() {
    return null;
  }

  @Override
  public Operation getOperation() {
    return operation;
  }

  @Override
  public IndexCandidate normalize(CommandContext ctx) {
    return this;
  }

  @Override
  public List<ImmutableSchemaProperty> properties() {
    return properties;
  }
}
