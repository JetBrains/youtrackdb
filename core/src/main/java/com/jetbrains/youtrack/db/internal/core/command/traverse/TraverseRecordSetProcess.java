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
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Iterator;

public class TraverseRecordSetProcess extends TraverseAbstractProcess<Iterator<Identifiable>> {

  private final TraversePath path;
  protected Identifiable record;
  protected int index = -1;

  public TraverseRecordSetProcess(
      final Traverse iCommand, final Iterator<Identifiable> iTarget, TraversePath parentPath,
      DatabaseSessionInternal db) {
    super(iCommand, iTarget, db);
    this.path = parentPath.appendRecordSet();
    command.getContext().push(this);
  }

  @SuppressWarnings("unchecked")
  public Identifiable process() {
    while (target.hasNext()) {
      record = target.next();
      index++;

      var rec = record.getRecord(session);
      if (rec instanceof EntityImpl entity) {
        if (!entity.getIdentity().isPersistent() && entity.getPropertiesCount() == 1) {
          // EXTRACT THE FIELD CONTEXT
          var fieldvalue = entity.getProperty(entity.propertyNames()[0]);
          if (fieldvalue instanceof Collection<?>) {
            command
                .getContext()
                .push(
                    new TraverseRecordSetProcess(
                        command, ((Collection<Identifiable>) fieldvalue).iterator(), path,
                        session));

          } else if (fieldvalue instanceof EntityImpl) {
            command.getContext().push(new TraverseRecordProcess(command, rec, path, session));
          }
        } else {
          command.getContext().push(new TraverseRecordProcess(command, rec, path, session));
        }

        return null;
      }
    }

    return pop();
  }

  @Override
  public TraversePath getPath() {
    return path;
  }

  @Override
  public String toString() {
    return target != null ? target.toString() : "-";
  }
}
