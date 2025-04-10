package com.jetbrains.youtrack.db.internal.lucene.functions;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.lucene.index.LuceneFullTextIndex;
import org.apache.lucene.index.memory.MemoryIndex;

/**
 *
 */
public class LuceneFunctionsUtils {

  public static final String MEMORY_INDEX = "_memoryIndex";

  protected static LuceneFullTextIndex searchForIndex(SQLExpression[] args, CommandContext ctx) {
    final var indexName = (String) args[0].execute((Identifiable) null, ctx);
    return getLuceneFullTextIndex(ctx, indexName);
  }

  protected static LuceneFullTextIndex getLuceneFullTextIndex(
      final CommandContext ctx, final String indexName) {
    final var documentDatabase = ctx.getDatabaseSession();
    final var sharedContext = documentDatabase.getSharedContext();

    final var index =
        (LuceneFullTextIndex)
            sharedContext.getIndexManager().getIndex(documentDatabase, indexName);
    if (!(index instanceof LuceneFullTextIndex)) {
      throw new IllegalArgumentException("Not a valid Lucene index:: " + indexName);
    }
    return index;
  }

  public static MemoryIndex getOrCreateMemoryIndex(CommandContext ctx) {
    var memoryIndex = (MemoryIndex) ctx.getVariable(MEMORY_INDEX);
    if (memoryIndex == null) {
      memoryIndex = new MemoryIndex();
      ctx.setVariable(MEMORY_INDEX, memoryIndex);
    }
    memoryIndex.reset();
    return memoryIndex;
  }

  public static String doubleEscape(final String s) {
    final var sb = new StringBuilder();
    for (var i = 0; i < s.length(); ++i) {
      final var c = s.charAt(i);
      if (c == 92 || c == 43 || c == 45 || c == 33 || c == 40 || c == 41 || c == 58 || c == 94
          || c == 91 || c == 93 || c == 34 || c == 123 || c == 125 || c == 126 || c == 42 || c == 63
          || c == 124 || c == 38 || c == 47) {
        sb.append('\\');
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }
}
