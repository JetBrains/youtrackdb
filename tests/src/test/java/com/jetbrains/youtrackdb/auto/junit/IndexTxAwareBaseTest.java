/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;

/**
 * JUnit 4 migration of IndexTxAwareBaseTest. Original test class:
 * com.jetbrains.youtrackdb.auto.IndexTxAwareBaseTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexTxAwareBaseTest.java
 */
public abstract class IndexTxAwareBaseTest extends BaseDBTest {

  protected final String className;
  protected final String fieldName;
  protected final String indexName;
  private final boolean unique;
  protected Index index;

  public IndexTxAwareBaseTest(boolean unique) {
    this.unique = unique;
    this.className = this.getClass().getSimpleName();
    this.fieldName = "value";
    this.indexName = this.getClass().getSimpleName() + "_index";
  }

  /**
   * Sets up the class schema and index. Should be called from @BeforeClass in subclasses. Original:
   * beforeClass (line 39)
   */
  protected void setupClassSchema() {
    if (!session.getMetadata().getSchema().existsClass(className)) {
      final var cls = session.getMetadata().getSchema().createClass(className);
      cls.createProperty(fieldName, PropertyType.INTEGER);
      cls.createIndex(
          indexName,
          unique ? INDEX_TYPE.UNIQUE : INDEX_TYPE.NOTUNIQUE,
          fieldName
      );
    }
  }

  /**
   * Original: afterMethod (line 53)
   */
  @Override
  @After
  public void afterMethod() throws Exception {
    session.getMetadata().getSchema().getClassInternal(className).truncate();
    super.afterMethod();
  }

  /**
   * Original: beforeMethod (line 60)
   */
  @Override
  @Before
  public void beforeMethod() throws Exception {
    super.beforeMethod();
    index = session.getSharedContext().getIndexManager().getIndex(indexName);
  }

  protected EntityImpl newDoc(int fieldValue) {
    return ((EntityImpl) session.newEntity(className)).setPropertyInChain(fieldName, fieldValue);
  }

  protected void verifyTxIndexPut(Map<Integer, Set<RID>> expectedPut) {
    verifyTxIndexChanges(expectedPut, null);
  }

  protected void verifyTxIndexRemove(Map<Integer, Set<RID>> expectedRemove) {
    verifyTxIndexChanges(null, expectedRemove);
  }

  protected void verifyTxIndexChanges(
      Map<Integer, Set<RID>> expectedPut,
      Map<Integer, Set<RID>> expectedRemove
  ) {
    session.getTransactionInternal().preProcessRecordsAndExecuteCallCallbacks();
    final var indexChanges = session.getTransactionInternal().getIndexChanges(indexName);

    final var putChanges = getChangesMap(indexChanges, OPERATION.PUT);
    final var removeChanges = getChangesMap(indexChanges, OPERATION.REMOVE);

    if (expectedPut == null) {
      assertTrue(putChanges.isEmpty());
    } else {
      assertEquals(expectedPut, putChanges);
    }

    if (expectedRemove == null) {
      assertTrue(removeChanges.isEmpty());
    } else {
      assertEquals(expectedRemove, removeChanges);
    }
  }

  protected static Map<Object, Set<Identifiable>> getChangesMap(
      FrontendTransactionIndexChanges indexChanges,
      FrontendTransactionIndexChanges.OPERATION operation) {
    return indexChanges == null ?
        Map.of() :
        indexChanges.changesPerKey.entrySet().stream()
            .filter(e -> e.getValue().getEntriesAsList().stream()
                .anyMatch(txEntry -> txEntry.getOperation().equals(operation)))
            .collect(
                Collectors.toMap(
                    Entry::getKey,
                    e -> e.getValue().getEntriesAsList().stream()
                        .filter(txEntry -> txEntry.getOperation().equals(operation))
                        .map(TransactionIndexEntry::getValue)
                        .collect(Collectors.toSet())
                )
            );
  }
}
