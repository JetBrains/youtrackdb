package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.ArrayList;
import java.util.List;

public class RequiredIndexCanditate implements IndexCandidate {

  public final List<IndexCandidate> canditates = new ArrayList<IndexCandidate>();

  public void addCanditate(IndexCandidate canditate) {
    this.canditates.add(canditate);
  }

  public List<IndexCandidate> getCanditates() {
    return canditates;
  }

  @Override
  public String getName() {
    var name = "";
    for (var indexCandidate : canditates) {
      name = indexCandidate.getName() + "|";
    }
    return name;
  }

  @Override
  public IndexCandidate invert() {
    // TODO: when handling operator invert it
    return this;
  }

  @Override
  public Operation getOperation() {
    throw new UnsupportedOperationException();
  }

  @Override
  public IndexCandidate normalize(CommandContext ctx) {
    var newCanditates = new RequiredIndexCanditate();
    for (var candidate : canditates) {
      var result = candidate.normalize(ctx);
      if (result != null) {
        newCanditates.addCanditate(result);
      } else {
        return null;
      }
    }
    return newCanditates;
  }

  @Override
  public List<ImmutableSchemaProperty> properties() {
    List<SchemaProperty> props = new ArrayList<>();
    for (var cand : this.canditates) {
      props.addAll(cand.properties());
    }
    return props;
  }
}
