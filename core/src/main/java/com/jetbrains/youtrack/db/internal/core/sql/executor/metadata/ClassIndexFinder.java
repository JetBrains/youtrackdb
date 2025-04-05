package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaPropertyInternal;
import java.util.Collection;
import java.util.List;
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
    List<String> rawPath = path.getPath();
    var db = ctx.getDatabase();
    String lastP = rawPath.remove(rawPath.size() - 1);
    PrePath cand =
        new PrePath() {
          {
            chain = Optional.empty();
            this.cl = ctx.getDatabase().getClass(ClassIndexFinder.this.clazz);
            valid = true;
            last = lastP;
          }
        };
    for (String ele : rawPath) {
      SchemaPropertyInternal prop = (SchemaPropertyInternal) cand.cl.getProperty(db, ele);
      if (prop != null) {
        SchemaClass linkedClass = prop.getLinkedClass(db);
        Collection<Index> indexes = prop.getAllIndexesInternal(db);
        if (prop.getType().isLink() && linkedClass != null) {
          boolean found = false;
          for (Index index : indexes) {
            if (index.getInternal().canBeUsedInEqualityOperators()) {
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
    PrePath pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    SchemaClass cl = pre.cl;
    Optional<IndexCandidate> cand = pre.chain;
    String last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(ctx.getDatabase(), last);
    if (prop != null) {
      Collection<Index> indexes = prop.getAllIndexesInternal(ctx.getDatabase());
      for (Index index : indexes) {
        if (index.getInternal().canBeUsedInEqualityOperators()) {
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
    PrePath pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    SchemaClass cl = pre.cl;
    Optional<IndexCandidate> cand = pre.chain;
    String last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(ctx.getDatabase(), last);
    if (prop != null) {
      if (prop.getType() == PropertyType.EMBEDDEDMAP) {
        Collection<Index> indexes = prop.getAllIndexesInternal(ctx.getDatabase());
        for (Index index : indexes) {
          if (index.getInternal().canBeUsedInEqualityOperators()) {
            IndexDefinition def = index.getDefinition();
            for (String o : def.getFieldsToIndex()) {
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
    PrePath pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    SchemaClass cl = pre.cl;
    Optional<IndexCandidate> cand = pre.chain;
    String last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(ctx.getDatabase(), last);
    if (prop != null) {
      Collection<Index> indexes = prop.getAllIndexesInternal(ctx.getDatabase());
      for (Index index : indexes) {
        if (index.getInternal().canBeUsedInEqualityOperators()
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
    PrePath pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    SchemaClass cl = pre.cl;
    Optional<IndexCandidate> cand = pre.chain;
    String last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(ctx.getDatabase(), last);
    if (prop != null) {
      if (prop.getType() == PropertyType.EMBEDDEDMAP) {
        Collection<Index> indexes = prop.getAllIndexesInternal(ctx.getDatabase());
        for (Index index : indexes) {
          IndexDefinition def = index.getDefinition();
          if (index.getInternal().canBeUsedInEqualityOperators()) {
            for (String o : def.getFieldsToIndex()) {
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

  @Override
  public Optional<IndexCandidate> findFullTextIndex(
      MetadataPath path, Object value, CommandContext ctx) {
    PrePath pre = findPrePath(path, ctx);
    if (!pre.valid) {
      return Optional.empty();
    }
    SchemaClass cl = pre.cl;
    Optional<IndexCandidate> cand = pre.chain;
    String last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(ctx.getDatabase(), last);
    if (prop != null) {
      Collection<Index> indexes = prop.getAllIndexesInternal(ctx.getDatabase());
      for (Index index : indexes) {
        if (SchemaClass.INDEX_TYPE.FULLTEXT.name().equalsIgnoreCase(index.getType())
            && !index.getAlgorithm().equalsIgnoreCase("LUCENE")) {
          if (cand.isPresent()) {
            ((IndexCandidateChain) cand.get()).add(index.getName());
            ((IndexCandidateChain) cand.get()).setOperation(Operation.FuzzyEq);
            return cand;
          } else {
            return Optional.of(new IndexCandidateImpl(index.getName(), Operation.FuzzyEq, prop));
          }
        }
      }
    }
    return Optional.empty();
  }
}
