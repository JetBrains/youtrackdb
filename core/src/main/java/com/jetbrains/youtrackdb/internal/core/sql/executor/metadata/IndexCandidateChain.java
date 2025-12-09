package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IndexCandidateChain implements IndexCandidate {

  private final List<String> indexes = new ArrayList<>();
  private Operation operation;

  public IndexCandidateChain(String name) {
    indexes.add(name);
  }

  @Override
  public String getName() {
    var name = new StringBuilder();
    for (var index : indexes) {
      name.append(index).append("->");
    }

    return name.toString();
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

  public void add(String name) {
    indexes.add(name);
  }

  public void setOperation(Operation operation) {
    this.operation = operation;
  }

  @Override
  public Operation getOperation() {
    return operation;
  }

  @Override
  public IndexCandidate normalize(CommandContext ctx) {
    return null;
  }

  @Override
  public List<SchemaProperty> properties() {
    return Collections.emptyList();
  }
}
