package com.jetbrains.youtrack.db.internal.lucene.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.lucene.test.BaseLuceneTest;
import java.util.Collections;
import java.util.HashMap;
import org.apache.lucene.search.SortField;
import org.junit.Test;

public class LuceneIndexEngineUtilsTest extends BaseLuceneTest {

  @Test
  public void buildSortFields() {
    var metadata = new HashMap<String, Object>();
    session.begin();
    metadata.put(
        "sort",
        Collections.singletonList(
            ((EntityImpl) session.newEntity())
                .setPropertyInChain("field", "score")
                .setPropertyInChain("reverse", false)
                .setPropertyInChain("type", "INT")
                .toMap()));
    session.commit();

    final var fields = LuceneIndexEngineUtils.buildSortFields(metadata);

    assertThat(fields).hasSize(1);
    final var sortField = fields.get(0);

    assertThat(sortField.getField()).isEqualTo("score");
    assertThat(sortField.getType()).isEqualTo(SortField.Type.INT);
    assertThat(sortField.getReverse()).isFalse();
  }

  @Test
  public void buildIntSortField() throws Exception {

    session.begin();
    final var sortConf =
        ((EntityImpl) session.newEntity()).setPropertyInChain("field", "score")
            .setPropertyInChain("reverse", true)
            .setPropertyInChain("type", "INT");

    final var sortField = LuceneIndexEngineUtils.buildSortField(sortConf);

    assertThat(sortField.getField()).isEqualTo("score");
    assertThat(sortField.getType()).isEqualTo(SortField.Type.INT);
    assertThat(sortField.getReverse()).isTrue();
    session.commit();
  }

  @Test
  public void buildDocSortField() throws Exception {

    session.begin();
    final var sortConf = ((EntityImpl) session.newEntity()).setPropertyInChain("type", "DOC");

    final var sortField = LuceneIndexEngineUtils.buildSortField(sortConf);

    assertThat(sortField.getField()).isNull();
    assertThat(sortField.getType()).isEqualTo(SortField.Type.DOC);
    assertThat(sortField.getReverse()).isFalse();
    session.commit();
  }
}
