package com.jetbrains.youtrack.db.internal.lucene.functions;

import static com.jetbrains.youtrack.db.internal.lucene.functions.LuceneFunctionsUtils.getOrCreateMemoryIndex;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
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
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 *
 */
public class LuceneSearchOnClassFunction extends LuceneSearchFunctionTemplate {

  public static final String NAME = "search_class";

  public LuceneSearchOnClassFunction() {
    super(NAME, 1, 2);
  }

  @Override
  public String getName(DatabaseSession session) {
    return NAME;
  }

  @Override
  public boolean canExecuteInline(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {
    return true;
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] params,
      CommandContext ctx) {

    Result result;
    if (iThis instanceof Result) {
      result = (Result) iThis;
    } else {
      result = new ResultInternal(ctx.getDatabaseSession(), (Identifiable) iThis);
    }

    var entity = result.asEntity();

    var session = ctx.getDatabaseSession();
    var className = entity.getSchemaClassName();
    var index = searchForIndex(ctx, className);

    if (index == null) {
      return false;
    }

    var query = (String) params[0];

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

    if (params.length == 2) {
      return (Map<String, ?>) params[1];
    }

    return Collections.emptyMap();
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "SEARCH_INDEX( indexName, [ metdatada {} ] )";
  }

  @Override
  public boolean filterResult() {
    return true;
  }

  @Override
  public Iterable<Identifiable> searchFromTarget(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {

    var index = searchForIndex(target, ctx);

    var expression = args[0];
    var query = (String) expression.execute((Identifiable) null, ctx);

    if (index != null) {

      var metadata = getMetadata(args, ctx);

      List<Identifiable> luceneResultSet;
      try (var rids =
          index
              .getRids(ctx.getDatabaseSession(),
                  new LuceneKeyAndMetadata(
                      new LuceneCompositeKey(Collections.singletonList(query)).setContext(ctx),
                      metadata))) {
        luceneResultSet = rids.collect(Collectors.toList());
      }

      return luceneResultSet;
    }
    return Collections.emptySet();
  }

  private static Map<String, ?> getMetadata(SQLExpression[] args, CommandContext ctx) {
    if (args.length == 2) {
      return getMetadata(args[1], ctx);
    }
    return LuceneQueryBuilder.EMPTY_METADATA;
  }

  @Override
  protected LuceneFullTextIndex searchForIndex(
      SQLFromClause target, CommandContext ctx, SQLExpression... args) {
    var item = target.getItem();

    var className = item.getIdentifier().getStringValue();

    return searchForIndex(ctx, className);
  }

  @Nullable
  private static LuceneFullTextIndex searchForIndex(CommandContext ctx, String className) {
    var db = ctx.getDatabaseSession();
    var dbMetadata = db.getMetadata();

    var indices =
        dbMetadata.getImmutableSchemaSnapshot().getClassInternal(className).getIndexesInternal()
            .stream()
            .filter(idx -> idx instanceof LuceneFullTextIndex)
            .map(idx -> (LuceneFullTextIndex) idx)
            .toList();

    if (indices.size() > 1) {
      throw new IllegalArgumentException("too many full-text indices on given class: " + className);
    }

    return indices.isEmpty() ? null : indices.get(0);
  }
}
