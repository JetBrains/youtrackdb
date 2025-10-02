package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.Collections;
import java.util.List;

public class IndexCandidateImpl implements IndexCandidate {

  private final String name;
  private Operation operation;
  private final ImmutableSchemaProperty property;

  public IndexCandidateImpl(String name, Operation operation, ImmutableSchemaProperty prop) {
    this.name = name;
    this.operation = operation;
    this.property = prop;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public IndexCandidate invert() {
    if (this.operation == Operation.Ge) {
      this.operation = Operation.Lt;
    } else if (this.operation == Operation.Gt) {
      this.operation = Operation.Le;
    } else if (this.operation == Operation.Le) {
      this.operation = Operation.Gt;
    } else if (this.operation == Operation.Lt) {
      this.operation = Operation.Ge;
    }
    return this;
  }

  @Override
  public Operation getOperation() {
    return operation;
  }

  @Override
  public IndexCandidate normalize(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getFastImmutableSchema();
    var index = schema.getIndex(name);
    if (property.getName().equals(index.getDefinition().getProperties().getFirst())) {
      return this;
    } else {
      return null;
    }
  }

  @Override
  public List<ImmutableSchemaProperty> properties() {
    return Collections.singletonList(this.property);
  }
}
