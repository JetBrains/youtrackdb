package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaPropertyInternal;
import java.util.Optional;

public class ClassIndexFinder implements IndexFinder {

  public ClassIndexFinder(String clazz) {
    super();
    this.clazz = clazz;
  }

  private final String clazz;

  private static class PrePath {

    SchemaClass cl;
    Optional<IndexCandidate> chain;
    boolean valid;
    String last;
  }

  private PrePath findPrePath(MetadataPath path, CommandContext ctx) {
    var rawPath = path.getPath();
    var session = ctx.getDatabaseSession();
    var lastP = rawPath.remove(rawPath.size() - 1);
    var cand =
        new PrePath() {
          {
            chain = Optional.empty();
            this.cl = ctx.getDatabaseSession().getClass(ClassIndexFinder.this.clazz);
            valid = true;
            last = lastP;
          }
        };
    for (var ele : rawPath) {
      var prop = (SchemaPropertyInternal) cand.cl.getProperty(ele);
      if (prop != null) {
        var linkedClass = prop.getLinkedClass();
        var indexes = prop.getAllIndexesInternal();
        if (prop.getType().isLink() && linkedClass != null) {
          var found = false;
          for (var index : indexes) {
            if (index.canBeUsedInEqualityOperators()) {
              if (cand.chain.isPresent()) {
                ((IndexCandidateChain) cand.chain.get()).add(index.getName());
              } else {
                cand.chain = Optional.of(new IndexCandidateChain(index.getName()));
              }
              cand.cl = linkedClass;
              found = true;
            } else {
              cand.valid = false;
              return cand;
            }
          }
          if (!found) {
            cand.valid = false;
            return cand;
          }
        } else {
          cand.valid = false;
          return cand;
        }
      } else {
        cand.valid = false;
        return cand;
      }
    }
    return cand;
  }

  @Override
  public Optional<IndexCandidate> findExactIndex(MetadataPath path, Object value,
      CommandContext ctx) {
    var pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    var cl = pre.cl;
    var cand = pre.chain;
    var last = pre.last;

    var db = ctx.getDatabaseSession();
    var prop = (SchemaPropertyInternal) cl.getProperty(last);
    if (prop != null) {
      var indexes = prop.getAllIndexesInternal();
      for (var index : indexes) {
        if (index.canBeUsedInEqualityOperators()) {
          if (cand.isPresent()) {
            ((IndexCandidateChain) cand.get()).add(index.getName());
            ((IndexCandidateChain) cand.get()).setOperation(Operation.Eq);
            return cand;
          } else {
            return Optional.of(new IndexCandidateImpl(index.getName(), Operation.Eq, prop));
          }
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<IndexCandidate> findByKeyIndex(MetadataPath path, Object value,
      CommandContext ctx) {
    var pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    var cl = pre.cl;
    var cand = pre.chain;
    var last = pre.last;

    var session = ctx.getDatabaseSession();
    var prop = (SchemaPropertyInternal) cl.getProperty(last);
    if (prop != null) {
      if (prop.getType() == PropertyType.EMBEDDEDMAP) {
        var indexes = prop.getAllIndexesInternal();
        for (var index : indexes) {
          if (index.canBeUsedInEqualityOperators()) {
            var def = index.getDefinition();
            for (var o : def.getFieldsToIndex()) {
              if (o.equalsIgnoreCase(last + " by key")) {
                if (cand.isPresent()) {
                  ((IndexCandidateChain) cand.get()).add(index.getName());
                  ((IndexCandidateChain) cand.get()).setOperation(Operation.Eq);
                  return cand;
                } else {
                  return Optional.of(new IndexCandidateImpl(index.getName(), Operation.Eq, prop));
                }
              }
            }
          }
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<IndexCandidate> findAllowRangeIndex(
      MetadataPath path, Operation op, Object value, CommandContext ctx) {
    var pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    var cl = pre.cl;
    var cand = pre.chain;
    var last = pre.last;

    var db = ctx.getDatabaseSession();
    var prop = (SchemaPropertyInternal) cl.getProperty(last);
    if (prop != null) {
      var indexes = prop.getAllIndexesInternal();
      for (var index : indexes) {
        if (index.canBeUsedInEqualityOperators()
            && index.supportsOrderedIterations()) {
          if (cand.isPresent()) {
            ((IndexCandidateChain) cand.get()).add(index.getName());
            ((IndexCandidateChain) cand.get()).setOperation(op);
            return cand;
          } else {
            return Optional.of(new IndexCandidateImpl(index.getName(), op, prop));
          }
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<IndexCandidate> findByValueIndex(MetadataPath path, Object value,
      CommandContext ctx) {
    var pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    var cl = pre.cl;
    var cand = pre.chain;
    var last = pre.last;

    var session = ctx.getDatabaseSession();
    var prop = (SchemaPropertyInternal) cl.getProperty(last);
    if (prop != null) {
      if (prop.getType() == PropertyType.EMBEDDEDMAP) {
        var indexes = prop.getAllIndexesInternal();
        for (var index : indexes) {
          var def = index.getDefinition();
          if (index.canBeUsedInEqualityOperators()) {
            for (var o : def.getFieldsToIndex()) {
              if (o.equalsIgnoreCase(last + " by value")) {
                if (cand.isPresent()) {
                  ((IndexCandidateChain) cand.get()).add(index.getName());
                  ((IndexCandidateChain) cand.get()).setOperation(Operation.Eq);
                  return cand;
                } else {
                  return Optional.of(new IndexCandidateImpl(index.getName(), Operation.Eq, prop));
                }
              }
            }
          }
        }
      }
    }
    return Optional.empty();
  }
}
