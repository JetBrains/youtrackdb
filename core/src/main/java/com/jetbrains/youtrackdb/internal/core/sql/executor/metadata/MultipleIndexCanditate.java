package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultipleIndexCanditate implements IndexCandidate {

  public final List<IndexCandidate> canditates = new ArrayList<IndexCandidate>();

  public MultipleIndexCanditate() {
  }

  private MultipleIndexCanditate(Collection<IndexCandidate> canditates) {
    this.canditates.addAll(canditates);
  }

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
    var newCanditates = normalizeBetween(this.canditates);
    newCanditates = normalizeComposite(newCanditates, ctx);
    if (newCanditates.isEmpty()) {
      return null;
    } else if (newCanditates.size() == 1) {
      return newCanditates.iterator().next();
    } else {
      return new MultipleIndexCanditate(newCanditates);
    }
  }

  private static Collection<IndexCandidate> normalizeBetween(List<IndexCandidate> canditates) {
    List<IndexCandidate> newCanditates = new ArrayList<>();
    for (var i = 0; i < canditates.size(); i++) {
      var matched = false;
      var canditate = canditates.get(i);
      var properties = canditate.properties();
      for (var z = canditates.size() - 1; z > i; z--) {
        var lastCandidate = canditates.get(z);
        var lastProperties = lastCandidate.properties();
        if (properties.size() == 1
            && lastProperties.size() == 1
            && properties.getFirst().getName().equals(lastProperties.getFirst().getName())) {
          if (canditate.getOperation().isRange() || lastCandidate.getOperation().isRange()) {
            newCanditates.add(new RangeIndexCanditate(canditate.getName(), properties.getFirst()));
            canditates.remove(z);
            if (z != canditates.size()) {
              z++; // Increase so it does not decrease next iteration
            }
            matched = true;
          }
        }
      }
      if (!matched) {
        newCanditates.add(canditate);
      }
    }
    return newCanditates;
  }

  private Collection<IndexCandidate> normalizeComposite(
      Collection<IndexCandidate> canditates, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var propeties = properties();
    Map<String, IndexCandidate> newCanditates = new HashMap<>();
    for (var cand : canditates) {
      if (!newCanditates.containsKey(cand.getName())) {
        var index = session.getSharedContext().getIndexManager()
            .getIndex(cand.getName());
        List<SchemaProperty> foundProps = new ArrayList<>();
        for (var field : index.getDefinition().getProperties()) {
          var found = false;
          for (var property : propeties) {
            if (property.getName().equals(field)) {
              found = true;
              foundProps.add(property);
              break;
            }
          }
          if (!found) {
            break;
          }
        }
        if (foundProps.size() == 1) {
          newCanditates.put(index.getName(), cand);
        } else if (!foundProps.isEmpty()) {
          newCanditates.put(
              index.getName(),
              new IndexCandidateComposite(index.getName(), cand.getOperation(), foundProps));
        }
      }
    }
    return newCanditates.values();
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
