/*
 *
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
package com.jetbrains.youtrack.db.internal.core.command.traverse;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Iterator;
import javax.annotation.Nullable;

public class TraverseMultiValueProcess extends TraverseAbstractProcess<Iterator<Object>> {

  private final TraversePath parentPath;
  protected Object value;
  protected int index = -1;

  public TraverseMultiValueProcess(
      final Traverse iCommand, final Iterator<Object> iTarget, TraversePath parentPath,
      DatabaseSessionInternal db) {
    super(iCommand, iTarget, db);
    this.parentPath = parentPath;
  }

  @Nullable
  public Identifiable process() {
    while (target.hasNext()) {
      value = target.next();
      index++;

      if (value instanceof Identifiable) {

        if (value instanceof RID) {
          var transaction = session.getActiveTransaction();
          value = transaction.load(((Identifiable) value));
        }
        final TraverseAbstractProcess<Identifiable> subProcess =
            new TraverseRecordProcess(command, (Identifiable) value, getPath(), session);
        command.getContext().push(subProcess);

        return null;
      }
    }

    return pop();
  }

  @Override
  public TraversePath getPath() {
    return parentPath.appendIndex(index);
  }

  @Override
  public String toString() {
    return "[idx:" + index + "]";
  }
}
