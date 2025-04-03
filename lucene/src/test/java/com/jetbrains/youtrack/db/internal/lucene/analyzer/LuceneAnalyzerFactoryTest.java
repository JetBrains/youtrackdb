package com.jetbrains.youtrack.db.internal.lucene.analyzer;

import static com.jetbrains.youtrack.db.internal.lucene.analyzer.LuceneAnalyzerFactory.AnalyzerKind.INDEX;
import static com.jetbrains.youtrack.db.internal.lucene.analyzer.LuceneAnalyzerFactory.AnalyzerKind.QUERY;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.lucene.tests.LuceneBaseTest;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 *
 */
public class LuceneAnalyzerFactoryTest extends LuceneBaseTest {

  private LuceneAnalyzerFactory analyzerFactory;
  private Map<String, ?> metadata;
  private IndexDefinition indexDef;

  @Before
  public void before() throws IOException {
    analyzerFactory = new LuceneAnalyzerFactory();
    // default analyzer is Standard
    // default analyzer for indexing is keyword
    // default analyzer for query is standard

    var metajson =
        IOUtils.readFileAsString(new File("./src/test/resources/index_metadata_new.json"));
    metadata = JSONSerializerJackson.mapFromJson(metajson);

    indexDef = Mockito.mock(IndexDefinition.class);
    when(indexDef.getFields())
        .thenReturn(asList("name", "title", "author", "lyrics", "genre", "description"));
    when(indexDef.getClassName()).thenReturn("Song");
  }

  @Test(expected = IllegalArgumentException.class)
  public void createAnalyzerNullIndexDefinition() {
    analyzerFactory.createAnalyzer(null, INDEX, metadata);
  }

  @Test(expected = IllegalArgumentException.class)
  public void createAnalyzerNullIndex() {
    analyzerFactory.createAnalyzer(indexDef, null, metadata);
  }

  @Test(expected = IllegalArgumentException.class)
  public void createAnalyzerNullMetadata() {
    analyzerFactory.createAnalyzer(indexDef, INDEX, null);
  }

  @Test
  public void shouldAssignStandardAnalyzerForIndexingUndefined() throws Exception {
    var analyzer =
        (LucenePerFieldAnalyzerWrapper) analyzerFactory.createAnalyzer(indexDef, INDEX, metadata);
    // default analyzer for indexing
    assertThat(analyzer.getWrappedAnalyzer("undefined")).isInstanceOf(StandardAnalyzer.class);
  }

  @Test
  public void shouldAssignKeywordAnalyzerForIndexing() throws Exception {
    var analyzer =
        (LucenePerFieldAnalyzerWrapper) analyzerFactory.createAnalyzer(indexDef, INDEX, metadata);
    // default analyzer for indexing
    assertThat(analyzer.getWrappedAnalyzer("genre")).isInstanceOf(KeywordAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.genre")).isInstanceOf(KeywordAnalyzer.class);
  }

  @Test
  public void shouldAssignConfiguredAnalyzerForIndexing() throws Exception {
    var analyzer =
        (LucenePerFieldAnalyzerWrapper) analyzerFactory.createAnalyzer(indexDef, INDEX, metadata);
    assertThat(analyzer.getWrappedAnalyzer("title")).isInstanceOf(EnglishAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.title")).isInstanceOf(EnglishAnalyzer.class);

    assertThat(analyzer.getWrappedAnalyzer("author")).isInstanceOf(KeywordAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.author")).isInstanceOf(KeywordAnalyzer.class);

    assertThat(analyzer.getWrappedAnalyzer("lyrics")).isInstanceOf(EnglishAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.lyrics")).isInstanceOf(EnglishAnalyzer.class);

    assertThat(analyzer.getWrappedAnalyzer("description")).isInstanceOf(StandardAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.description"))
        .isInstanceOf(StandardAnalyzer.class);

    var description =
        (StopwordAnalyzerBase) analyzer.getWrappedAnalyzer("description");

    assertThat(description.getStopwordSet()).isNotEmpty();
    assertThat(description.getStopwordSet()).hasSize(2);
    assertThat(description.getStopwordSet().contains("the")).isTrue();
    assertThat(description.getStopwordSet().contains("is")).isTrue();
  }

  @Test
  public void shouldAssignConfiguredAnalyzerForQuery() throws Exception {
    var analyzer =
        (LucenePerFieldAnalyzerWrapper) analyzerFactory.createAnalyzer(indexDef, QUERY, metadata);
    assertThat(analyzer.getWrappedAnalyzer("title")).isInstanceOf(EnglishAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.title")).isInstanceOf(EnglishAnalyzer.class);

    assertThat(analyzer.getWrappedAnalyzer("author")).isInstanceOf(KeywordAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.author")).isInstanceOf(KeywordAnalyzer.class);

    assertThat(analyzer.getWrappedAnalyzer("genre")).isInstanceOf(StandardAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.genre")).isInstanceOf(StandardAnalyzer.class);
  }

  @Test
  public void shouldUseClassNameToPrefixFieldName() {
    final var analyzer =
        (LucenePerFieldAnalyzerWrapper) analyzerFactory.createAnalyzer(indexDef, QUERY, metadata);
    assertThat(analyzer.getWrappedAnalyzer("Song.title")).isInstanceOf(EnglishAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.author")).isInstanceOf(KeywordAnalyzer.class);
    assertThat(analyzer.getWrappedAnalyzer("Song.genre")).isInstanceOf(StandardAnalyzer.class);
  }
}
