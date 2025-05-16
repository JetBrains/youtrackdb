package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.Collections;
import java.util.List;

public class RangeIndexCanditate implements IndexCandidate {

  private final String name;
  private final SchemaProperty property;

  public RangeIndexCanditate(String name, SchemaProperty property) {
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
  public List<SchemaProperty> properties() {
    return Collections.singletonList(this.property);
  }
}
