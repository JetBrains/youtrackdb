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
package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class CheckIndexTool extends DatabaseTool {

  //  class Error {
  //
  //    RID    rid;
  //    String  indexName;
  //    boolean presentInIndex;
  //    boolean presentOnCluster;
  //
  //    Error(RID rid, String indexName, boolean presentInIndex, boolean presentOnCluster) {
  //      this.rid = rid;
  //      this.indexName = indexName;
  //      this.presentInIndex = presentInIndex;
  //      this.presentOnCluster = presentOnCluster;
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
    for (var index : session.getMetadata().getIndexManagerInternal().getIndexes(session)) {
      if (!canCheck(index)) {
        continue;
      }
      checkIndex(session, index);
    }
    message("Total errors found on indexes: " + totalErrors);
  }

  private boolean canCheck(Index index) {
    var indexDef = index.getDefinition();
    var className = indexDef.getClassName();
    if (className == null) {
      return false; // manual index, not supported yet
    }
    var fields = indexDef.getFields();
    var fieldDefs = indexDef.getFieldsToIndex();

    // check if there are fields defined on maps (by key/value). Not supported yet
    for (var i = 0; i < fieldDefs.size(); i++) {
      if (!fields.get(i).equals(fieldDefs.get(i))) {
        return false;
      }
    }
    return true;
  }

  private void checkIndex(DatabaseSessionInternal session, Index index) {
    var fields = index.getDefinition().getFields();
    var className = index.getDefinition().getClassName();
    var clazz = this.session.getMetadata().getImmutableSchemaSnapshot().getClass(className);
    var clusterIds = clazz.getPolymorphicClusterIds();
    for (var clusterId : clusterIds) {
      checkCluster(session, clusterId, index, fields);
    }
  }

  private void checkCluster(
      DatabaseSessionInternal session, int clusterId, Index index, List<String> fields) {
    var totRecordsForCluster = this.session.countClusterElements(clusterId);
    var clusterName = this.session.getClusterNameById(clusterId);

    var totSteps = 5;
    message("Checking cluster " + clusterName + "  for index " + index.getName() + "\n");
    var iter = this.session.browseCluster(clusterName);
    long count = 0;
    long step = -1;
    while (iter.hasNext()) {
      var currentStep = count * totSteps / totRecordsForCluster;
      if (currentStep > step) {
        printProgress(clusterName, clusterId, (int) currentStep, totSteps);
        step = currentStep;
      }
      var record = iter.next();
      if (record instanceof EntityImpl entity) {
        checkThatRecordIsIndexed(session, entity, index, fields);
      }
      count++;
    }
    printProgress(clusterName, clusterId, totSteps, totSteps);
    message("\n");
  }

  void printProgress(String clusterName, int clusterId, int step, int totSteps) {
    var msg = new StringBuilder();
    msg.append("\rcluster " + clusterName + " (" + clusterId + ") |");
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
      DatabaseSessionInternal session, EntityImpl entity, Index index, List<String> fields) {
    var vals = new Object[fields.size()];
    RID entityId = entity.getIdentity();
    for (var i = 0; i < vals.length; i++) {
      vals[i] = entity.field(fields.get(i));
    }

    var indexKey = index.getDefinition().createValue(session, vals);
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
