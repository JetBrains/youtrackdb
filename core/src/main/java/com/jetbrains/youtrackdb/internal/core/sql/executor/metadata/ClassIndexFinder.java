package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import javax.annotation.Nullable;

public class ClassIndexFinder implements IndexFinder {

  public ClassIndexFinder(String clazz) {
    super();
    this.clazz = clazz;
  }

  private final String clazz;

  private static class PrePath {

    SchemaClassInternal cl;
    @Nullable
    IndexCandidate chain;
    boolean valid;
    String last;
  }

  private PrePath findPrePath(IndexMetadataPath path, CommandContext ctx) {
    var rawPath = path.getPath();
    var lastP = rawPath.removeLast();
    var cand =
        new PrePath() {
          {
            this.cl = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot()
                .getClassInternal(ClassIndexFinder.this.clazz);
            valid = true;
            last = lastP;
          }
        };
    for (var ele : rawPath) {
      var prop = (SchemaPropertyInternal) cand.cl.getProperty(ele);
      if (prop != null) {
        var linkedClass = (SchemaClassInternal) prop.getLinkedClass();
        var indexes = prop.getAllIndexesInternal();
        if (PropertyTypeInternal.convertFromPublicType(prop.getType()).isLink()
            && linkedClass != null) {
          var found = false;
          for (var index : indexes) {
            if (index.canBeUsedInEqualityOperators()) {
              if (cand.chain != null) {
                ((IndexCandidateChain) cand.chain).add(index.getName());
              } else {
                cand.chain = new IndexCandidateChain(index.getName());
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
  public IndexCandidate findExactIndex(IndexMetadataPath path, Object value,
      CommandContext ctx) {
    var pre = findPrePath(path, ctx);

    if (!pre.valid) {
      return null;
    }
    var cl = pre.cl;
    var cand = pre.chain;
    var last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(last);
    if (prop != null) {
      var indexes = prop.getAllIndexesInternal();
      for (var index : indexes) {
        if (index.canBeUsedInEqualityOperators()) {
          if (cand != null) {
            ((IndexCandidateChain) cand).add(index.getName());
            ((IndexCandidateChain) cand).setOperation(Operation.Eq);
            return cand;
          } else {
            return new IndexCandidateImpl(index.getName(), Operation.Eq, prop);
          }
        }
      }
    }

    return null;
  }

  @Override
  public IndexCandidate findByKeyIndex(IndexMetadataPath path, Object value,
      CommandContext ctx) {
    var pre = findPrePath(path, ctx);

    if (!pre.valid) {
      return null;
    }

    var cl = pre.cl;
    var cand = pre.chain;
    var last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(last);
    if (prop != null) {
      if (prop.getType() == PropertyType.EMBEDDEDMAP) {
        var indexes = prop.getAllIndexesInternal();
        for (var index : indexes) {
          if (index.canBeUsedInEqualityOperators()) {
            var def = index.getDefinition();
            for (var o : def.getFieldsToIndex()) {
              if (o.equalsIgnoreCase(last + " by key")) {
                if (cand != null) {
                  ((IndexCandidateChain) cand).add(index.getName());
                  ((IndexCandidateChain) cand).setOperation(Operation.Eq);
                  return cand;
                } else {
                  return new IndexCandidateImpl(index.getName(), Operation.Eq, prop);
                }
              }
            }
          }
        }
      }
    }

    return null;
  }

  @Override
  public IndexCandidate findAllowRangeIndex(
      IndexMetadataPath path, Operation op, Object value, CommandContext ctx) {
    var pre = findPrePath(path, ctx);

    if (!pre.valid) {
      return null;
    }
    var cl = pre.cl;
    var cand = pre.chain;
    var last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(last);
    if (prop != null) {
      var indexes = prop.getAllIndexesInternal();
      for (var index : indexes) {
        if (index.canBeUsedInEqualityOperators()
            && index.supportsOrderedIterations()) {
          if (cand != null) {
            ((IndexCandidateChain) cand).add(index.getName());
            ((IndexCandidateChain) cand).setOperation(op);
            return cand;
          } else {
            return new IndexCandidateImpl(index.getName(), op, prop);
          }
        }
      }
    }
    return null;
  }

  @Override
  public IndexCandidate findByValueIndex(IndexMetadataPath path, Object value,
      CommandContext ctx) {
    var pre = findPrePath(path, ctx);

    if (!pre.valid) {
      return null;
    }

    var cl = pre.cl;
    var cand = pre.chain;
    var last = pre.last;

    var prop = (SchemaPropertyInternal) cl.getProperty(last);
    if (prop != null) {
      if (prop.getType() == PropertyType.EMBEDDEDMAP) {
        var indexes = prop.getAllIndexesInternal();
        for (var index : indexes) {
          var def = index.getDefinition();
          if (index.canBeUsedInEqualityOperators()) {
            for (var o : def.getFieldsToIndex()) {
              if (o.equalsIgnoreCase(last + " by value")) {
                if (cand != null) {
                  ((IndexCandidateChain) cand).add(index.getName());
                  ((IndexCandidateChain) cand).setOperation(Operation.Eq);
                  return cand;
                } else {
                  return new IndexCandidateImpl(index.getName(), Operation.Eq, prop);
                }
              }
            }
          }
        }
      }
    }

    return null;
  }
}
