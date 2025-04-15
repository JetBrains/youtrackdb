package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class IndexCandidateChain implements IndexCandidate {

  private final List<String> indexes = new ArrayList<>();
  private Operation operation;

  public IndexCandidateChain(String name) {
    indexes.add(name);
  }

  @Override
  public String getName() {
    var name = "";
    for (var index : indexes) {
      name += index + "->";
    }
    return name;
  }

  @Override
  public Optional<IndexCandidate> invert() {
    if (this.operation == Operation.Ge) {
      this.operation = Operation.Lt;
    } else if (this.operation == Operation.Gt) {
      this.operation = Operation.Le;
    } else if (this.operation == Operation.Le) {
      this.operation = Operation.Gt;
    } else if (this.operation == Operation.Lt) {
      this.operation = Operation.Ge;
    }
    return Optional.of(this);
  }

  public void add(String name) {
    indexes.add(name);
  }

  public void setOperation(Operation operation) {
    this.operation = operation;
  }

  public Operation getOperation() {
    return operation;
  }

  @Override
  public Optional<IndexCandidate> normalize(CommandContext ctx) {
    return Optional.empty();
  }

  @Override
  public List<SchemaProperty> properties() {
    return Collections.emptyList();
  }
}
