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
package com.jetbrains.youtrackdb.internal.core.command.traverse;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Iterator;
import javax.annotation.Nullable;

public class TraverseRecordSetProcess extends TraverseAbstractProcess<Iterator<Identifiable>> {

  private final TraversePath path;
  protected Identifiable record;
  protected int index = -1;

  public TraverseRecordSetProcess(
      final Traverse iCommand, final Iterator<Identifiable> iTarget, TraversePath parentPath,
      DatabaseSessionEmbedded db) {
    super(iCommand, iTarget, db);
    this.path = parentPath.appendRecordSet();
    command.getContext().push(this);
  }

  @Override
  @Nullable
  @SuppressWarnings("unchecked")
  public Identifiable process() {
    while (target.hasNext()) {
      record = target.next();
      index++;

      var transaction = session.getActiveTransaction();
      var rec = transaction.load(record);
      if (rec instanceof EntityImpl entity) {
        if (!entity.getIdentity().isPersistent() && entity.getPropertiesCount() == 1) {
          // EXTRACT THE FIELD CONTEXT
          var fieldvalue = entity.getProperty(entity.getPropertyNames().getFirst());
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
