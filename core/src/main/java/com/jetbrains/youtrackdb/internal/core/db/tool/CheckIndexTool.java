/*
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.db.tool;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class CheckIndexTool extends DatabaseTool<DatabaseSessionEmbedded> {

  //  class Error {
  //
  //    RID    rid;
  //    String  indexName;
  //    boolean presentInIndex;
  //    boolean presentOnCollection;
  //
  //    Error(RID rid, String indexName, boolean presentInIndex, boolean presentOnCollection) {
  //      this.rid = rid;
  //      this.indexName = indexName;
  //      this.presentInIndex = presentInIndex;
  //      this.presentOnCollection = presentOnCollection;
  //    }
  //  }
  //
  //  List<Error> errors = new ArrayList<Error>();
  private long totalErrors = 0;

  @Override
  protected void parseSetting(String option, List<String> items) {
  }

  @Override
  public void run() {
    for (var index : session.getSharedContext().getIndexManager().getIndexes()) {
      if (!canCheck(index)) {
        continue;
      }
      checkIndex(session, index);
    }
    message("Total errors found on indexes: " + totalErrors);
  }

  private static boolean canCheck(Index index) {
    var indexDef = index.getDefinition();
    var className = indexDef.getClassName();
    if (className == null) {
      return false; // manual index, not supported yet
    }
    var fields = indexDef.getProperties();
    var fieldDefs = indexDef.getFieldsToIndex();

    // check if there are fields defined on maps (by key/value). Not supported yet
    for (var i = 0; i < fieldDefs.size(); i++) {
      if (!fields.get(i).equals(fieldDefs.get(i))) {
        return false;
      }
    }
    return true;
  }

  private void checkIndex(DatabaseSessionEmbedded session, Index index) {
    var fields = index.getDefinition().getProperties();
    var className = index.getDefinition().getClassName();
    var clazz = this.session.getMetadata().getFastImmutableSchema(session).getClass(className);
    var collectionIds = clazz.getPolymorphicCollectionIds();
    for (var collectionId : collectionIds) {
      checkCollection(session, collectionId, index, fields);
    }
  }

  private void checkCollection(
      DatabaseSessionEmbedded session, int collectionId, Index index, List<String> fields) {
    var totRecordsForCollection = this.session.countCollectionElements(collectionId);
    var collectionName = this.session.getCollectionNameById(collectionId);

    var totSteps = 5;
    message("Checking collection " + collectionName + "  for index " + index.getName() + "\n");
    var iter = this.session.browseCollection(collectionName);
    long count = 0;
    long step = -1;
    while (iter.hasNext()) {
      var currentStep = count * totSteps / totRecordsForCollection;
      if (currentStep > step) {
        printProgress(collectionName, collectionId, (int) currentStep, totSteps);
        step = currentStep;
      }
      var record = iter.next();
      if (record instanceof EntityImpl entity) {
        checkThatRecordIsIndexed(session, entity, index, fields);
      }
      count++;
    }
    printProgress(collectionName, collectionId, totSteps, totSteps);
    message("\n");
  }

  void printProgress(String collectionName, int collectionId, int step, int totSteps) {
    var msg = new StringBuilder();
    msg.append("\rcollection " + collectionName + " (" + collectionId + ") |");
    for (var i = 0; i < totSteps; i++) {
      if (i < step) {
        msg.append("*");
      } else {
        msg.append(" ");
      }
    }
    msg.append("| ");
    msg.append(step * 100 / totSteps);
    msg.append("%%");
    message(msg.toString());
  }

  private void checkThatRecordIsIndexed(
      DatabaseSessionEmbedded session, EntityImpl entity, Index index, List<String> fields) {
    var vals = new Object[fields.size()];
    RID entityId = entity.getIdentity();
    for (var i = 0; i < vals.length; i++) {
      vals[i] = entity.getProperty(fields.get(i));
    }

    var indexKey = index.getDefinition().createValue(session.getActiveTransaction(), vals);
    if (indexKey == null) {
      return;
    }

    final Collection<Object> indexKeys;
    if (!(indexKey instanceof Collection)) {
      indexKeys = Collections.singletonList(indexKey);
    } else {
      //noinspection unchecked
      indexKeys = (Collection<Object>) indexKey;
    }

    for (final var key : indexKeys) {
      try (final var stream = index.getRids(session, key)) {
        if (stream.noneMatch((rid) -> rid.equals(entityId))) {
          totalErrors++;
          message(
              "\rERROR: Index "
                  + index.getName()
                  + " - record not found: "
                  + entity.getIdentity()
                  + "\n");
        }
      }
    }
  }

  public long getTotalErrors() {
    return totalErrors;
  }
}
