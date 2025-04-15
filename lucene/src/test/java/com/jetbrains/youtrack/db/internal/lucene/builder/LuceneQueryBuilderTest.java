package com.jetbrains.youtrack.db.internal.lucene.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrack.db.internal.lucene.tests.LuceneBaseTest;
import java.util.Collections;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class LuceneQueryBuilderTest extends LuceneBaseTest {

  private IndexDefinition indexDef;

  @Before
  public void setUp() throws Exception {
    indexDef = Mockito.mock(IndexDefinition.class);
    when(indexDef.getFields()).thenReturn(Collections.emptyList());
    when(indexDef.isAutomatic()).thenReturn(true);
    when(indexDef.getClassName()).thenReturn("Song");
  }

  @Test
  public void testUnmaskedQueryReporting() {
    final var builder = new LuceneQueryBuilder(LuceneQueryBuilder.EMPTY_METADATA);

    final var invalidQuery = "+(song:private{}private)";
    try {
      builder.buildQuery(
          indexDef, invalidQuery, LuceneQueryBuilder.EMPTY_METADATA, new EnglishAnalyzer(),
          session);
    } catch (ParseException e) {
      assertThat(e.getMessage()).contains("Cannot parse", invalidQuery);
      return;
    }
    fail("Expected ParseException");
  }

  @Test
  public void testMaskedQueryReporting() {
    final var builder = new LuceneQueryBuilder(LuceneQueryBuilder.EMPTY_METADATA);

    final var invalidQuery = "+(song:private{}private)";
    try {
      builder.buildQuery(indexDef, invalidQuery,
          Collections.singletonMap("reportQueryAs", "masked"),
          new EnglishAnalyzer(), session);
    } catch (ParseException e) {
      assertThat(e.getMessage()).contains("Cannot parse", "masked").doesNotContain(invalidQuery);
      return;
    }
    fail("Expected ParseException");
  }
}
