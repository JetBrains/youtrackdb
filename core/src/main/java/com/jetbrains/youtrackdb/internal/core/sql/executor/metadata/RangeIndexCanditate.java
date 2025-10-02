package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.Collections;
import java.util.List;

public class RangeIndexCanditate implements IndexCandidate {

  private final String name;
  private final ImmutableSchemaProperty property;

  public RangeIndexCanditate(String name, ImmutableSchemaProperty property) {
    this.name = name;
    this.property = property;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public IndexCandidate invert() {
    return this;
  }

  @Override
  public Operation getOperation() {
    return Operation.Range;
  }

  @Override
  public IndexCandidate normalize(CommandContext ctx) {
    return this;
  }

  @Override
  public List<ImmutableSchemaProperty> properties() {
    return Collections.singletonList(this.property);
  }
}
