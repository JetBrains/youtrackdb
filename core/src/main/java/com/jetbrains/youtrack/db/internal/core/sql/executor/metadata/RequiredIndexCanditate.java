package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
  public Optional<IndexCandidate> invert() {
    // TODO: when handling operator invert it
    return Optional.of(this);
  }

  @Override
  public Operation getOperation() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<IndexCandidate> normalize(CommandContext ctx) {
    var newCanditates = new RequiredIndexCanditate();
    for (var candidate : canditates) {
      var result = candidate.normalize(ctx);
      if (result.isPresent()) {
        newCanditates.addCanditate(result.get());
      } else {
        return Optional.empty();
      }
    }
    return Optional.of(newCanditates);
  }

  @Override
  public List<SchemaProperty> properties() {
    List<SchemaProperty> props = new ArrayList<>();
    for (var cand : this.canditates) {
      props.addAll(cand.properties());
    }
    return props;
  }
}
