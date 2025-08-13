package com.jetbrains.youtrackdb.internal.lucene.functions;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.lucene.builder.LuceneQueryBuilder;
import com.jetbrains.youtrackdb.internal.lucene.collections.LuceneCompositeKey;
import com.jetbrains.youtrackdb.internal.lucene.index.LuceneFullTextIndex;
import com.jetbrains.youtrackdb.internal.lucene.query.LuceneKeyAndMetadata;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.lucene.index.memory.MemoryIndex;

/**
 *
 */
public class LuceneSearchOnIndexFunction extends LuceneSearchFunctionTemplate {

  public static final String MEMORY_INDEX = "_memoryIndex";

  public static final String NAME = "search_index";

  public LuceneSearchOnIndexFunction() {
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
    var result = (Result) iThis;

    var indexName = (String) params[0];

    var index = searchForIndex(ctx, indexName);

    if (index == null) {
      return false;
    }

    var query = (String) params[1];

    var memoryIndex = getOrCreateMemoryIndex(ctx);

    var key =
        index.getDefinition().getProperties().stream()
            .map(result::getProperty)
            .collect(Collectors.toList());

    for (var field : index.buildDocument(ctx.getDatabaseSession(), key).getFields()) {
      memoryIndex.addField(field, index.indexAnalyzer());
    }

    var metadata = getMetadata(params);
    var keyAndMetadata =
        new LuceneKeyAndMetadata(
            new LuceneCompositeKey(Collections.singletonList(query)).setContext(ctx), metadata);

    return memoryIndex.search(index.buildQuery(keyAndMetadata, ctx.getDatabaseSession())) > 0.0f;
  }

  private static Map<String, ?> getMetadata(Object[] params) {

    if (params.length == 3) {
      //noinspection unchecked
      return (Map<String, ?>) params[2];
    }

    return LuceneQueryBuilder.EMPTY_METADATA;
  }

  private static MemoryIndex getOrCreateMemoryIndex(CommandContext ctx) {
    var memoryIndex = (MemoryIndex) ctx.getVariable(MEMORY_INDEX);
    if (memoryIndex == null) {
      memoryIndex = new MemoryIndex();
      ctx.setVariable(MEMORY_INDEX, memoryIndex);
    }

    memoryIndex.reset();
    return memoryIndex;
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

    var index = searchForIndex(target, ctx, args);

    var expression = args[1];
    var query = (String) expression.execute((Identifiable) null, ctx);
    if (index != null && query != null) {

      var meta = getMetadata(args, ctx);

      List<Identifiable> luceneResultSet;
      try (var rids =
          index.getRids(ctx.getDatabaseSession(),
              new LuceneKeyAndMetadata(
                  new LuceneCompositeKey(List.of(query)).setContext(ctx), meta))) {
        luceneResultSet = rids.collect(Collectors.toList());
      }

      return luceneResultSet;
    }
    return Collections.emptyList();
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

    var schemaClass = target.getSchemaClass(ctx.getDatabaseSession());
    if (schemaClass == null) {
      return null;
    }

    return searchForIndex(schemaClass, ctx, args);
  }

  @Nullable
  private static LuceneFullTextIndex searchForIndex(
      SchemaClassInternal schemaClass, CommandContext ctx, SQLExpression... args) {

    var indexName = (String) args[0].execute((Identifiable) null, ctx);

    var index = schemaClass.getClassIndex(ctx.getDatabaseSession(), indexName);

    if (index instanceof LuceneFullTextIndex) {
      return (LuceneFullTextIndex) index;
    }

    return null;
  }

  @Nullable
  private static LuceneFullTextIndex searchForIndex(CommandContext ctx, String indexName) {
    final var database = ctx.getDatabaseSession();
    var index = database.getSharedContext().getIndexManager().getIndex(indexName);

    if (index instanceof LuceneFullTextIndex) {
      return (LuceneFullTextIndex) index;
    }

    return null;
  }

  @Override
  public Object getResult() {
    return super.getResult();
  }
}
