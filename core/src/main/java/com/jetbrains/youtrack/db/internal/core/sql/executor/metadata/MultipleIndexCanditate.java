package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    var newCanditates = normalizeBetween(this.canditates, ctx);
    newCanditates = normalizeComposite(newCanditates, ctx);
    if (newCanditates.isEmpty()) {
      return Optional.empty();
    } else if (newCanditates.size() == 1) {
      return Optional.of(newCanditates.iterator().next());
    } else {
      return Optional.of(new MultipleIndexCanditate(newCanditates));
    }
  }

  private Collection<IndexCandidate> normalizeBetween(
      List<IndexCandidate> canditates, CommandContext ctx) {
    List<IndexCandidate> newCanditates = new ArrayList<>();
    var sesssion = ctx.getDatabaseSession();
    for (var i = 0; i < canditates.size(); i++) {
      var matched = false;
      var canditate = canditates.get(i);
      var properties = canditate.properties();
      for (var z = canditates.size() - 1; z > i; z--) {
        var lastCandidate = canditates.get(z);
        var lastProperties = lastCandidate.properties();
        if (properties.size() == 1
            && lastProperties.size() == 1
            && properties.get(0).getName() == lastProperties.get(0).getName()) {
          if (canditate.getOperation().isRange() || lastCandidate.getOperation().isRange()) {
            newCanditates.add(new RangeIndexCanditate(canditate.getName(), properties.get(0)));
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
    var session = (DatabaseSessionEmbedded) ctx.getDatabaseSession();
    var propeties = properties();
    Map<String, IndexCandidate> newCanditates = new HashMap<>();
    for (var cand : canditates) {
      if (!newCanditates.containsKey(cand.getName())) {
        var index = session.getSharedContext().getIndexManager()
            .getIndex(session, cand.getName());
        List<SchemaProperty> foundProps = new ArrayList<>();
        for (var field : index.getDefinition().getFields()) {
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
  public List<SchemaProperty> properties() {
    List<SchemaProperty> props = new ArrayList<>();
    for (var cand : this.canditates) {
      props.addAll(cand.properties());
    }
    return props;
  }
}
