/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrack.db.internal.lucene.operator;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.IndexSearchResult;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.operator.IndexReuseType;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryTargetOperator;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrack.db.internal.lucene.collections.LuceneCompositeKey;
import com.jetbrains.youtrack.db.internal.lucene.index.LuceneFullTextIndex;
import com.jetbrains.youtrack.db.internal.lucene.query.LuceneKeyAndMetadata;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.memory.MemoryIndex;

public class LuceneTextOperator extends QueryTargetOperator {

  public static final String MEMORY_INDEX = "_memoryIndex";

  public LuceneTextOperator() {
    this("LUCENE", 5, false);
  }

  public LuceneTextOperator(String iKeyword, int iPrecedence, boolean iLogical) {
    super(iKeyword, iPrecedence, iLogical);
  }

  protected static DatabaseSessionInternal getDatabase() {
    return DatabaseRecordThreadLocal.instance().get();
  }

  @Override
  public IndexReuseType getIndexReuseType(Object iLeft, Object iRight) {
    return IndexReuseType.INDEX_OPERATOR;
  }

  @Override
  public IndexSearchResult getOIndexSearchResult(
      SchemaClass iSchemaClass,
      SQLFilterCondition iCondition,
      List<IndexSearchResult> iIndexSearchResults,
      CommandContext context) {

    // FIXME questo non trova l'indice se l'ordine e' errato
    return LuceneOperatorUtil.buildOIndexSearchResult(
        iSchemaClass, iCondition, iIndexSearchResults, context);
  }

  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    if (!index.getType().toLowerCase().contains("fulltext")) {
      return null;
    }
    if (index.getAlgorithm() == null || !index.getAlgorithm().toLowerCase().contains("lucene")) {
      return null;
    }

    return index
        .getInternal()
        .getRids(iContext.getDatabase(),
            new LuceneKeyAndMetadata(
                new LuceneCompositeKey(keyParams).setContext(iContext), Collections.emptyMap()))
        .map((rid) -> new RawPair<>(new LuceneCompositeKey(keyParams).setContext(iContext), rid));
  }

  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Override
  public boolean canBeMerged() {
    return false;
  }

  @Override
  public Object evaluateRecord(
      Identifiable iRecord,
      EntityImpl iCurrentResult,
      SQLFilterCondition iCondition,
      Object iLeft,
      Object iRight,
      CommandContext iContext,
      final EntitySerializer serializer) {

    LuceneFullTextIndex index = involvedIndex(iContext.getDatabase(), iRecord, iCurrentResult,
        iCondition, iLeft,
        iRight);
    if (index == null) {
      return false;
    }

    MemoryIndex memoryIndex = (MemoryIndex) iContext.getVariable(MEMORY_INDEX);
    if (memoryIndex == null) {
      memoryIndex = new MemoryIndex();
      iContext.setVariable(MEMORY_INDEX, memoryIndex);
    }
    memoryIndex.reset();

    try {
      // In case of collection field evaluate the query with every item until matched

      if (iLeft instanceof List && index.isCollectionIndex()) {
        return matchCollectionIndex(iContext.getDatabase(), (List) iLeft, iRight, index,
            memoryIndex);
      } else {
        return matchField(iContext.getDatabase(), iLeft, iRight, index, memoryIndex);
      }

    } catch (ParseException e) {
      LogManager.instance().error(this, "error occurred while building query", e);

    } catch (IOException e) {
      LogManager.instance().error(this, "error occurred while building memory index", e);
    }
    return null;
  }

  private boolean matchField(
      DatabaseSessionInternal session, Object iLeft, Object iRight, LuceneFullTextIndex index,
      MemoryIndex memoryIndex)
      throws IOException, ParseException {
    for (IndexableField field : index.buildDocument(session, iLeft).getFields()) {
      memoryIndex.addField(field, index.indexAnalyzer());
    }
    return memoryIndex.search(index.buildQuery(iRight)) > 0.0f;
  }

  private boolean matchCollectionIndex(
      DatabaseSessionInternal session, List iLeft, Object iRight, LuceneFullTextIndex index,
      MemoryIndex memoryIndex)
      throws IOException, ParseException {
    boolean match = false;
    List<Object> collections = transformInput(iLeft, iRight, index, memoryIndex);
    for (Object collection : collections) {
      memoryIndex.reset();
      match = match || matchField(session, collection, iRight, index, memoryIndex);
      if (match) {
        break;
      }
    }
    return match;
  }

  private List<Object> transformInput(
      List iLeft, Object iRight, LuceneFullTextIndex index, MemoryIndex memoryIndex) {

    Integer collectionIndex = getCollectionIndex(iLeft);
    if (collectionIndex == -1) {
      // collection not found;
      return iLeft;
    }
    if (collectionIndex > 1) {
      throw new UnsupportedOperationException("Index of collection cannot be > 1");
    }
    // otherwise the input is [val,[]] or [[],val]
    Collection collection = (Collection) iLeft.get(collectionIndex);
    if (iLeft.size() == 1) {
      return new ArrayList<Object>(collection);
    }
    List<Object> transformed = new ArrayList<Object>(collection.size());
    for (Object o : collection) {
      List<Object> objects = new ArrayList<Object>();
      //  [[],val]
      if (collectionIndex == 0) {
        objects.add(o);
        objects.add(iLeft.get(1));
        //  [val,[]]
      } else {
        objects.add(iLeft.get(0));
        objects.add(o);
      }
      transformed.add(objects);
    }
    return transformed;
  }

  private Integer getCollectionIndex(List iLeft) {
    int i = 0;
    for (Object o : iLeft) {
      if (o instanceof Collection) {
        return i;
      }
      i++;
    }
    return -1;
  }

  protected LuceneFullTextIndex involvedIndex(
      DatabaseSessionInternal session, Identifiable iRecord,
      EntityImpl iCurrentResult,
      SQLFilterCondition iCondition,
      Object iLeft,
      Object iRight) {

    try {
      EntityImpl doc = iRecord.getRecord();
      if (doc.getClassName() != null) {
        var cls = getDatabase().getMetadata().getSchemaInternal()
            .getClassInternal(doc.getClassName());

        if (isChained(iCondition.getLeft())) {

          SQLFilterItemField chained = (SQLFilterItemField) iCondition.getLeft();

          SQLFilterItemField.FieldChain fieldChain = chained.getFieldChain();
          SchemaClassInternal oClass = cls;
          for (int i = 0; i < fieldChain.getItemCount() - 1; i++) {
            oClass = (SchemaClassInternal) oClass.getProperty(session, fieldChain.getItemName(i))
                .getLinkedClass(session);
          }
          if (oClass != null) {
            cls = oClass;
          }
        }
        Set<Index> classInvolvedIndexes = cls.getInvolvedIndexesInternal(session,
            fields(iCondition));
        LuceneFullTextIndex idx = null;
        for (Index classInvolvedIndex : classInvolvedIndexes) {

          if (classInvolvedIndex.getInternal() instanceof LuceneFullTextIndex) {
            idx = (LuceneFullTextIndex) classInvolvedIndex.getInternal();
            break;
          }
        }
        return idx;
      } else {
        return null;
      }
    } catch (RecordNotFoundException rnf) {
      return null;
    }
  }

  private boolean isChained(Object left) {
    if (left instanceof SQLFilterItemField field) {
      return field.isFieldChain();
    }
    return false;
  }

  // returns a list of field names
  protected Collection<String> fields(SQLFilterCondition iCondition) {

    Object left = iCondition.getLeft();

    if (left instanceof String fName) {
      return List.of(fName);
    }
    if (left instanceof Collection) {
      Collection<SQLFilterItemField> f = (Collection<SQLFilterItemField>) left;

      List<String> fields = new ArrayList<String>();
      for (SQLFilterItemField field : f) {
        fields.add(field.toString());
      }
      return fields;
    }
    if (left instanceof SQLFilterItemField fName) {

      if (fName.isFieldChain()) {
        int itemCount = fName.getFieldChain().getItemCount();
        return Collections.singletonList(fName.getFieldChain().getItemName(itemCount - 1));
      } else {
        return Collections.singletonList(fName.toString());
      }
    }
    return Collections.emptyList();
  }
}
