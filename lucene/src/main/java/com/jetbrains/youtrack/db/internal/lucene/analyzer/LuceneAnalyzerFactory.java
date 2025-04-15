package com.jetbrains.youtrack.db.internal.lucene.analyzer;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 *
 */
public class LuceneAnalyzerFactory {

  public Analyzer createAnalyzer(
      final IndexDefinition index, final AnalyzerKind kind, final Map<String, ?> metadata) {
    if (index == null) {
      throw new IllegalArgumentException("Index must not be null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("Analyzer kind must not be null");
    }
    if (metadata == null) {
      throw new IllegalArgumentException("Metadata must not be null");
    }
    final var defaultAnalyzerFQN = (String) metadata.get("default");
    final var prefix = index.getClassName() + ".";

    final var analyzer =
        geLucenePerFieldPresetAnalyzerWrapperForAllFields(defaultAnalyzerFQN);
    setDefaultAnalyzerForRequestedKind(index, kind, metadata, prefix, analyzer);
    setSpecializedAnalyzersForEachField(index, kind, metadata, prefix, analyzer);
    return analyzer;
  }

  private LucenePerFieldAnalyzerWrapper geLucenePerFieldPresetAnalyzerWrapperForAllFields(
      final String defaultAnalyzerFQN) {
    if (defaultAnalyzerFQN == null) {
      return new LucenePerFieldAnalyzerWrapper(new StandardAnalyzer());
    } else {
      return new LucenePerFieldAnalyzerWrapper(buildAnalyzer(defaultAnalyzerFQN));
    }
  }

  private void setDefaultAnalyzerForRequestedKind(
      final IndexDefinition index,
      final AnalyzerKind kind,
      final Map<String, ?> metadata,
      final String prefix,
      final LucenePerFieldAnalyzerWrapper analyzer) {
    final var specializedAnalyzerFQN = (String) metadata.get(kind.toString());
    if (specializedAnalyzerFQN != null) {
      for (final var field : index.getFields()) {
        analyzer.add(field, buildAnalyzer(specializedAnalyzerFQN));
        analyzer.add(prefix + field, buildAnalyzer(specializedAnalyzerFQN));
      }
    }
  }

  private void setSpecializedAnalyzersForEachField(
      final IndexDefinition index,
      final AnalyzerKind kind,
      final Map<String, ?> metadata,
      final String prefix,
      final LucenePerFieldAnalyzerWrapper analyzer) {
    for (final var field : index.getFields()) {
      final var analyzerName = field + "_" + kind.toString();
      final var analyzerStopwords = analyzerName + "_stopwords";

      if (metadata.containsKey(analyzerName) && metadata.containsKey(analyzerStopwords)) {
        @SuppressWarnings("unchecked") final var stopWords =
            (Collection<String>) metadata.get(analyzerStopwords);
        analyzer.add(field, buildAnalyzer((String) metadata.get(analyzerName), stopWords));
        analyzer.add(prefix + field, buildAnalyzer((String) metadata.get(analyzerName), stopWords));
      } else if (metadata.containsKey(analyzerName)) {
        analyzer.add(field, buildAnalyzer((String) metadata.get(analyzerName)));
        analyzer.add(prefix + field, buildAnalyzer((String) metadata.get(analyzerName)));
      }
    }
  }

  private Analyzer buildAnalyzer(final String analyzerFQN) {
    try {
      final Class classAnalyzer = Class.forName(analyzerFQN);
      final var constructor = classAnalyzer.getConstructor();
      return (Analyzer) constructor.newInstance();
    } catch (final ClassNotFoundException e) {
      throw BaseException.wrapException(
          new IndexException("Analyzer: " + analyzerFQN + " not found"), e, (String) null);
    } catch (final NoSuchMethodException e) {
      Class classAnalyzer = null;
      try {
        classAnalyzer = Class.forName(analyzerFQN);
        return (Analyzer) classAnalyzer.newInstance();
      } catch (Exception e1) {
        LogManager.instance().error(this, "Exception is suppressed, original exception is ", e);
        //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
        throw BaseException.wrapException(
            new IndexException("Couldn't instantiate analyzer:  public constructor  not found"),
            e1, (String) null);
      }
    } catch (Exception e) {
      LogManager.instance()
          .error(
              this,
              "Error on getting analyzer for Lucene index (continuing with StandardAnalyzer)",
              e);
      return new StandardAnalyzer();
    }
  }

  private Analyzer buildAnalyzer(final String analyzerFQN, final Collection<String> stopwords) {
    try {
      final Class classAnalyzer = Class.forName(analyzerFQN);
      final var constructor = classAnalyzer.getDeclaredConstructor(CharArraySet.class);
      return (Analyzer) constructor.newInstance(new CharArraySet(stopwords, true));
    } catch (final ClassNotFoundException e) {
      throw BaseException.wrapException(
          new IndexException("Analyzer: " + analyzerFQN + " not found"), e, (String) null);
    } catch (final NoSuchMethodException e) {
      throw BaseException.wrapException(
          new IndexException("Couldn't instantiate analyzer: public constructor not found"), e,
          (String) null);
    } catch (final Exception e) {
      LogManager.instance()
          .error(
              this,
              "Error on getting analyzer for Lucene index (continuing with StandardAnalyzer)",
              e);
      return new StandardAnalyzer();
    }
  }

  public enum AnalyzerKind {
    INDEX,
    QUERY;

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ENGLISH);
    }
  }
}
