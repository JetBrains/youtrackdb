package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.List;

public class IndexCandidateComposite implements IndexCandidate {

  private final String index;
  private final Operation operation;
  private final List<SchemaProperty> properties;

  public IndexCandidateComposite(String index, Operation operation,
      List<SchemaProperty> properties) {
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
  public List<SchemaProperty> properties() {
    return properties;
  }
}
