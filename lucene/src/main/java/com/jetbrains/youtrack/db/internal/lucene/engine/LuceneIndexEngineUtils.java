package com.jetbrains.youtrack.db.internal.lucene.engine;

import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;

/**
 *
 */
public class LuceneIndexEngineUtils {

  public static void sendTotalHits(String indexName, CommandContext context, long totalHits) {
    if (context != null) {

      if (context.getVariable("totalHits") == null) {
        context.setVariable("totalHits", totalHits);
      } else {
        context.setVariable("totalHits", null);
      }
      context.setVariable((indexName + ".totalHits").replace(".", "_"), totalHits);
    }
  }

  public static void sendLookupTime(
      String indexName,
      CommandContext context,
      final TopDocs docs,
      final Integer limit,
      long startFetching) {
    if (context != null) {

      final var finalTime = System.currentTimeMillis() - startFetching;
      context.setVariable(
          (indexName + ".lookupTime").replace(".", "_"),
          new HashMap<String, Object>() {
            {
              put("limit", limit);
              put("totalTime", finalTime);
              put("totalHits", docs.totalHits);
              put("returnedHits", docs.scoreDocs.length);
              if (!Float.isNaN(docs.getMaxScore())) {
                put("maxScore", docs.getMaxScore());
              }
            }
          });
    }
  }

  public static List<SortField> buildSortFields(Map<String, ?> metadata) {
    @SuppressWarnings("unchecked")
    var sortConf =
        Optional.ofNullable((List<Map<String, Object>>) metadata.get("sort"))
            .orElse(Collections.emptyList());

    return sortConf.stream().map(LuceneIndexEngineUtils::buildSortField)
        .collect(Collectors.toList());
  }

  /**
   * Builds {@link SortField} from a configuration {@link EntityImpl}
   *
   * @param conf
   * @return
   */
  public static SortField buildSortField(EntityImpl conf) {

    return buildSortField(conf.toMap());
  }

  /**
   * Builds a {@link SortField} from a configuration map. The map can contains up to three fields:
   * field (name), reverse (true/false) and type {@link SortField.Type}.
   *
   * @param conf
   * @return
   */
  public static SortField buildSortField(Map<String, Object> conf) {

    final var field = Optional.ofNullable((String) conf.get("field")).orElse(null);
    final var type =
        Optional.ofNullable(((String) conf.get("type")).toUpperCase())
            .orElse(SortField.Type.STRING.name());
    final var reverse = Optional.ofNullable((Boolean) conf.get("reverse")).orElse(false);

    var sortField = new SortField(field, SortField.Type.valueOf(type), reverse);

    return sortField;
  }

}
