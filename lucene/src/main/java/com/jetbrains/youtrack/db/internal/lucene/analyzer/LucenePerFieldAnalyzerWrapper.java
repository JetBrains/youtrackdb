package com.jetbrains.youtrack.db.internal.lucene.analyzer;

import static com.jetbrains.youtrack.db.internal.lucene.engine.LuceneIndexEngineAbstract.RID;

import com.jetbrains.youtrack.db.internal.lucene.builder.LuceneIndexType;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.apache.lucene.analysis.core.KeywordAnalyzer;

/**
 * <p>Doesn't allow to wrap components or readers. Thread local resources can be delegated to the
 * delegate analyzer, but not allocated on this analyzer (limit memory consumption). Uses a per
 * field reuse strategy.
 */
public class LucenePerFieldAnalyzerWrapper extends DelegatingAnalyzerWrapper {

  private final Analyzer defaultDelegateAnalyzer;
  private final Map<String, Analyzer> fieldAnalyzers;

  /**
   * Constructs with default analyzer.
   *
   * @param defaultAnalyzer Any fields not specifically defined to use a different analyzer will use
   *                        the one provided here.
   */
  public LucenePerFieldAnalyzerWrapper(final Analyzer defaultAnalyzer) {
    this(defaultAnalyzer, new HashMap<>());
  }

  /**
   * Constructs with default analyzer and a map of analyzers to use for specific fields.
   *
   * @param defaultAnalyzer Any fields not specifically defined to use a different analyzer will use
   *                        the one provided here.
   * @param fieldAnalyzers  a Map (String field name to the Analyzer) to be used for those fields
   */
  public LucenePerFieldAnalyzerWrapper(
      final Analyzer defaultAnalyzer, final Map<String, Analyzer> fieldAnalyzers) {
    super(PER_FIELD_REUSE_STRATEGY);
    this.defaultDelegateAnalyzer = defaultAnalyzer;
    this.fieldAnalyzers = new HashMap<>();

    this.fieldAnalyzers.putAll(fieldAnalyzers);

    this.fieldAnalyzers.put(RID, new KeywordAnalyzer());
    this.fieldAnalyzers.put(LuceneIndexType.RID_HASH, new KeywordAnalyzer());
    this.fieldAnalyzers.put("_CLASS", new KeywordAnalyzer());
    this.fieldAnalyzers.put("_COLLECTION", new KeywordAnalyzer());
    this.fieldAnalyzers.put("_JSON", new KeywordAnalyzer());
  }

  @Override
  protected Analyzer getWrappedAnalyzer(final String fieldName) {
    final var analyzer = fieldAnalyzers.get(fieldName);
    return (analyzer != null) ? analyzer : defaultDelegateAnalyzer;
  }

  @Override
  public String toString() {
    return "PerFieldAnalyzerWrapper("
        + fieldAnalyzers
        + ", default="
        + defaultDelegateAnalyzer
        + ")";
  }

  public LucenePerFieldAnalyzerWrapper add(final String field, final Analyzer analyzer) {
    fieldAnalyzers.put(field, analyzer);
    return this;
  }

  public LucenePerFieldAnalyzerWrapper add(final LucenePerFieldAnalyzerWrapper analyzer) {
    fieldAnalyzers.putAll(analyzer.fieldAnalyzers);
    return this;
  }

  public LucenePerFieldAnalyzerWrapper remove(final String field) {
    fieldAnalyzers.remove(field);
    return this;
  }

  protected Map<String, Analyzer> getAnalyzers() {
    return fieldAnalyzers;
  }
}
