package com.jetbrains.youtrack.db.internal.lucene.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.internal.lucene.analyzer.LucenePerFieldAnalyzerWrapper;
import java.util.HashMap;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.Test;

/**
 *
 */
public class LucenePerFieldAnalyzerWrapperTest {

  @Test
  public void shouldReturnDefaultAnalyzerForEachField() {
    final var analyzer =
        new LucenePerFieldAnalyzerWrapper(new StandardAnalyzer());

    assertThat(analyzer.getWrappedAnalyzer("a_field")).isNotNull();
    assertThat(analyzer.getWrappedAnalyzer("a_field")).isInstanceOf(StandardAnalyzer.class);
  }

  @Test
  public void shouldReturnCustomAnalyzerForEachField() {
    final var analyzer =
        new LucenePerFieldAnalyzerWrapper(new StandardAnalyzer());

    analyzer.add("text_en", new EnglishAnalyzer());
    analyzer.add("text_it", new ItalianAnalyzer());

    assertThat(analyzer.getWrappedAnalyzer("text_en")).isNotNull();
    assertThat(analyzer.getWrappedAnalyzer("text_en")).isInstanceOf(EnglishAnalyzer.class);

    assertThat(analyzer.getWrappedAnalyzer("text_it")).isNotNull();
    assertThat(analyzer.getWrappedAnalyzer("text_it")).isInstanceOf(ItalianAnalyzer.class);
  }

  @Test
  public void shouldReturnCustomAnalyzerForEachFieldInitializedByConstructor() {
    final var analyzer =
        new LucenePerFieldAnalyzerWrapper(
            new StandardAnalyzer(),
            new HashMap<String, Analyzer>() {
              {
                put("text_en", new EnglishAnalyzer());
                put("text_it", new ItalianAnalyzer());
              }
            });
    assertThat(analyzer.getWrappedAnalyzer("text_en")).isNotNull();
    assertThat(analyzer.getWrappedAnalyzer("text_en")).isInstanceOf(EnglishAnalyzer.class);

    assertThat(analyzer.getWrappedAnalyzer("text_it")).isNotNull();
    assertThat(analyzer.getWrappedAnalyzer("text_it")).isInstanceOf(ItalianAnalyzer.class);
  }
}
