/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrack.db.internal.lucene.builder;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrack.db.internal.lucene.analyzer.LuceneAnalyzerFactory;
import com.jetbrains.youtrack.db.internal.lucene.parser.LuceneMultiFieldQueryParser;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;

/**
 *
 */
public class LuceneQueryBuilder {

  public static final Map<String, ?> EMPTY_METADATA = Collections.emptyMap();

  private final boolean allowLeadingWildcard;
  // private final boolean                lowercaseExpandedTerms;
  private final boolean splitOnWhitespace;
  private final LuceneAnalyzerFactory analyzerFactory;

  public LuceneQueryBuilder(final Map<String, ?> metadata) {
    this(
        Optional.ofNullable((Boolean) metadata.get("allowLeadingWildcard")).orElse(false),
        Optional.ofNullable((Boolean) metadata.get("lowercaseExpandedTerms")).orElse(true),
        Optional.ofNullable((Boolean) metadata.get("splitOnWhitespace")).orElse(true));
  }

  public LuceneQueryBuilder(
      final boolean allowLeadingWildcard,
      final boolean lowercaseExpandedTerms,
      final boolean splitOnWhitespace) {
    this.allowLeadingWildcard = allowLeadingWildcard;
    // this.lowercaseExpandedTerms = lowercaseExpandedTerms;
    this.splitOnWhitespace = splitOnWhitespace;
    analyzerFactory = new LuceneAnalyzerFactory();
  }

  public Query query(
      final IndexDefinition index,
      final Object key,
      final Map<String, ?> metadata,
      final Analyzer analyzer, DatabaseSessionInternal session)
      throws ParseException {
    final var query = constructQueryString(key);
    if (query.isEmpty()) {
      return new MatchNoDocsQuery();
    }
    return buildQuery(index, query, metadata, analyzer, session);
  }

  private static String constructQueryString(final Object key) {
    if (key instanceof CompositeKey) {
      final var params = ((CompositeKey) key).getKeys().get(0);
      return params.toString();
    } else {
      return key.toString();
    }
  }

  protected Query buildQuery(
      final IndexDefinition index,
      final String query,
      final Map<String, ?> metadata,
      final Analyzer queryAnalyzer, DatabaseSessionInternal session)
      throws ParseException {
    String[] fields;
    if (index.isAutomatic()) {
      fields = index.getProperties().toArray(new String[index.getProperties().size()]);
    } else {
      final var length = index.getTypes().length;
      fields = new String[length];
      for (var i = 0; i < length; i++) {
        fields[i] = "k" + i;
      }
    }
    final Map<String, PropertyTypeInternal> types = new HashMap<>();
    for (var i = 0; i < fields.length; i++) {
      final var field = fields[i];
      types.put(field, index.getTypes()[i]);
    }
    return getQuery(index, query, metadata, queryAnalyzer, fields, types, session);
  }

  private Query getQuery(
      final IndexDefinition index,
      final String query,
      final Map<String, ?> metadata,
      final Analyzer queryAnalyzer,
      final String[] fields,
      final Map<String, PropertyTypeInternal> types, DatabaseSessionInternal session)
      throws ParseException {
    @SuppressWarnings("unchecked") final var boost =
        Optional.ofNullable((Map<String, Number>) metadata.get("boost"))
            .orElse(new HashMap<>())
            .entrySet()
            .stream()
            .collect(Collectors.toMap(Entry::getKey, e -> e.getValue().floatValue()));
    final var analyzer =
        Optional.ofNullable((Boolean) metadata.get("customAnalysis"))
            .filter(b -> b)
            .map(
                b ->
                    analyzerFactory.createAnalyzer(
                        index, LuceneAnalyzerFactory.AnalyzerKind.QUERY, metadata))
            .orElse(queryAnalyzer);
    final var queryParser =
        new LuceneMultiFieldQueryParser(types, fields, analyzer, boost, session);
    queryParser.setAllowLeadingWildcard(
        Optional.ofNullable((Boolean) metadata.get("allowLeadingWildcard"))
            .orElse(allowLeadingWildcard));
    queryParser.setSplitOnWhitespace(
        Optional.ofNullable((Boolean) metadata.get("splitOnWhitespace"))
            .orElse(splitOnWhitespace));
    try {
      var paresedQuery = queryParser.parse(query);
      if (paresedQuery instanceof MatchAllDocsQuery) {
        return queryParser.parse("RID:#*");
      }
      return paresedQuery;
    } catch (final org.apache.lucene.queryparser.classic.ParseException e) {
      final var cause = prepareParseError(e, metadata);
      LogManager.instance().error(this, "Exception is suppressed, original exception is ", cause);
      //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
      throw new ParseException(cause.getMessage());
    }
  }

  /**
   * Produces a Lucene {@link ParseException} that can be reported in logs.
   *
   * <p>If the metadata contains a `reportQueryAs` parameter, that will be reported as the text of
   * the query that failed to parse.
   *
   * <p>This is generally useful when the contents of a Lucene query contain privileged information
   * (e.g.Personally Identifiable Information in privacy sensitive settings) that should not be
   * persisted in logs.
   */
  private static Throwable prepareParseError(
      org.apache.lucene.queryparser.classic.ParseException e, Map<String, ?> metadata) {
    final Throwable cause;
    final var reportAs = (String) metadata.get("reportQueryAs");
    if (reportAs == null) {
      cause = e;
    } else {
      cause =
          new org.apache.lucene.queryparser.classic.ParseException(
              String.format("Cannot parse '%s'", reportAs));
      cause.initCause(e.getCause());
      cause.setStackTrace(e.getStackTrace());
    }
    return cause;
  }
}
