package com.jetbrains.youtrack.db.internal.lucene.functions;

import static com.jetbrains.youtrack.db.internal.lucene.functions.LuceneFunctionsUtils.getOrCreateMemoryIndex;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrack.db.internal.lucene.builder.LuceneQueryBuilder;
import com.jetbrains.youtrack.db.internal.lucene.collections.LuceneCompositeKey;
import com.jetbrains.youtrack.db.internal.lucene.index.LuceneFullTextIndex;
import com.jetbrains.youtrack.db.internal.lucene.query.LuceneKeyAndMetadata;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 */
public class LuceneSearchOnFieldsFunction extends LuceneSearchFunctionTemplate {

  public static final String NAME = "search_fields";

  public LuceneSearchOnFieldsFunction() {
    super(NAME, 2, 3);
  }

  @Override
  public String getName(DatabaseSession session) {
    return NAME;
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] params,
      CommandContext ctx) {

    var session = ctx.getDatabaseSession();
    if (iThis instanceof RID) {
      try {
        iThis = ((RID) iThis).getRecord(session);
      } catch (RecordNotFoundException rnf) {
        return false;
      }
    }
    if (iThis instanceof Identifiable) {
      iThis = new ResultInternal(ctx.getDatabaseSession(), (Identifiable) iThis);
    }
    var result = (Result) iThis;

    var entity = result.asEntity();
    if (entity.getSchemaClassName() == null) {
      return false;
    }

    var className = entity.getSchemaClassName();
    var fieldNames = (List<String>) params[0];

    var index = searchForIndex(className, ctx, fieldNames);

    if (index == null) {
      return false;
    }

    var query = (String) params[1];

    var memoryIndex = getOrCreateMemoryIndex(ctx);

    var key =
        index.getDefinition().getFields().stream()
            .map(s -> entity.getProperty(s))
            .collect(Collectors.toList());

    for (var field : index.buildDocument(ctx.getDatabaseSession(), key).getFields()) {
      memoryIndex.addField(field, index.indexAnalyzer());
    }

    var metadata = getMetadata(params);
    var keyAndMetadata =
        new LuceneKeyAndMetadata(
            new LuceneCompositeKey(Collections.singletonList(query)).setContext(ctx), metadata);

    return memoryIndex.search(index.buildQuery(keyAndMetadata, session)) > 0.0f;
  }

  private Map<String, ?> getMetadata(Object[] params) {

    if (params.length == 3) {
      return (Map<String, ?>) params[2];
    }

    return LuceneQueryBuilder.EMPTY_METADATA;
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "SEARCH_INDEX( indexName, [ metdatada {} ] )";
  }

  @Override
  public Iterable<Identifiable> searchFromTarget(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {

    var index = searchForIndex(target, ctx, args);

    var expression = args[1];
    var query = expression.execute((Identifiable) null, ctx);
    if (index != null) {

      var meta = getMetadata(args, ctx);
      Set<Identifiable> luceneResultSet;
      try (var rids =
          index
              .getRids(ctx.getDatabaseSession(),
                  new LuceneKeyAndMetadata(
                      new LuceneCompositeKey(Collections.singletonList(query)).setContext(ctx),
                      meta))) {
        luceneResultSet = rids.collect(Collectors.toSet());
      }

      return luceneResultSet;
    }
    throw new RuntimeException();
  }

  private static Map<String, ?> getMetadata(SQLExpression[] args, CommandContext ctx) {
    if (args.length == 3) {
      return getMetadata(args[2], ctx);
    }
    return LuceneQueryBuilder.EMPTY_METADATA;
  }

  @Override
  protected LuceneFullTextIndex searchForIndex(
      SQLFromClause target, CommandContext ctx, SQLExpression... args) {
    var fieldNames = (List<String>) args[0].execute((Identifiable) null, ctx);
    var item = target.getItem();
    var className = item.getIdentifier().getStringValue();

    return searchForIndex(className, ctx, fieldNames);
  }

  private static LuceneFullTextIndex searchForIndex(
      String className, CommandContext ctx, List<String> fieldNames) {
    var db = ctx.getDatabaseSession();
    db.activateOnCurrentThread();
    var dbMetadata = db.getMetadata();

    var indices =
        dbMetadata.getImmutableSchemaSnapshot().getClassInternal(className).getIndexesInternal()
            .stream()
            .filter(idx -> idx instanceof LuceneFullTextIndex)
            .map(idx -> (LuceneFullTextIndex) idx)
            .filter(idx -> intersect(idx.getDefinition().getFields(), fieldNames))
            .toList();

    if (indices.size() > 1) {
      throw new IllegalArgumentException(
          "too many indices matching given field name: " + String.join(",", fieldNames));
    }

    return indices.isEmpty() ? null : indices.get(0);
  }

  public static <T> boolean intersect(List<T> list1, List<T> list2) {
    for (var t : list1) {
      if (list2.contains(t)) {
        return true;
      }
    }

    return false;
  }
}
