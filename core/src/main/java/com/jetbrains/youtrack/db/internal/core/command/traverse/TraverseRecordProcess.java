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
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemFieldAll;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemFieldAny;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class TraverseRecordProcess extends TraverseAbstractProcess<Identifiable> {

  private final TraversePath path;

  public TraverseRecordProcess(
      final Traverse iCommand, final Identifiable iTarget, TraversePath parentPath,
      DatabaseSessionInternal session) {
    super(iCommand, iTarget, session);
    this.path = parentPath.append(iTarget);
  }

  public Identifiable process() {
    if (target == null) {
      return pop();
    }

    final var depth = path.getDepth();

    if (command.getContext().isAlreadyTraversed(target, depth))
    // ALREADY EVALUATED, DON'T GO IN DEEP
    {
      return drop();
    }

    if (command.getPredicate() != null) {
      var transaction = session.getActiveTransaction();
      final var conditionResult =
          command.getPredicate()
              .evaluate(transaction.loadEntity(target), null, command.getContext());
      if (conditionResult != Boolean.TRUE) {
        return drop();
      }
    }

    // UPDATE ALL TRAVERSED RECORD TO AVOID RECURSION
    command.getContext().addTraversed(target, depth);

    final var maxDepth = command.getMaxDepth();
    if (maxDepth > -1 && depth == maxDepth) {
      // SKIP IT
      pop();
    } else {
      var transaction = session.getActiveTransaction();
      var targetRec = transaction.load(target);
      if (!(targetRec instanceof EntityImpl targeEntity))
      // SKIP IT
      {
        return pop();
      }


      // MATCH!
      final List<Object> fields = new ArrayList<Object>();
      // TRAVERSE THE DOCUMENT ITSELF
      for (var cfgFieldObject : command.getFields()) {
        var cfgField = cfgFieldObject.toString();

        if ("*".equals(cfgField)
            || SQLFilterItemFieldAll.FULL_NAME.equalsIgnoreCase(cfgField)
            || SQLFilterItemFieldAny.FULL_NAME.equalsIgnoreCase(cfgField)) {

          // ADD ALL THE DOCUMENT FIELD
          fields.addAll(targeEntity.getPropertyNames());
          break;

        } else {
          // SINGLE FIELD
          final var pos =
              StringSerializerHelper.parse(
                  cfgField,
                  new StringBuilder(),
                  0,
                  -1,
                  new char[]{'.'},
                  true,
                  true,
                  true,
                  0,
                  true)
                  - 1;
          if (pos > -1) {
            // FOUND <CLASS>.<FIELD>
            SchemaImmutableClass result = null;
            if (targeEntity != null) {
              result = targeEntity.getImmutableSchemaClass(session);
            }
            final SchemaClass cls = result;
            if (cls == null)
            // JUMP IT BECAUSE NO SCHEMA
            {
              continue;
            }

            final var className = cfgField.substring(0, pos);
            if (!cls.isSubClassOf(className))
            // JUMP IT BECAUSE IT'S NOT A INSTANCEOF THE CLASS
            {
              continue;
            }

            cfgField = cfgField.substring(pos + 1);

            fields.add(cfgField);
          } else {
            fields.add(cfgFieldObject);
          }
        }
      }

      if (command.getStrategy() == Traverse.STRATEGY.DEPTH_FIRST)
      // REVERSE NAMES TO BE PROCESSED IN THE RIGHT ORDER
      {
        Collections.reverse(fields);
      }

      processFields(fields.iterator());

      if (targeEntity.isEmbedded()) {
        return null;
      }
    }

    return target;
  }

  private void processFields(Iterator<Object> target) {
    var transaction2 = session.getActiveTransaction();
    EntityImpl entity = transaction2.load(this.target);
    var database = command.getContext().getDatabaseSession();
    while (target.hasNext()) {
      var field = target.next();

      final Object fieldValue;
      if (field instanceof SQLFilterItem) {
        var context = new BasicCommandContext();
        context.setParent(command.getContext());
        fieldValue = ((SQLFilterItem) field).getValue(entity, null, context);
      } else {
        fieldValue = entity.getProperty(field.toString());
      }

      if (fieldValue != null) {
        final TraverseAbstractProcess<?> subProcess;

        var transaction1 = session.getActiveTransaction();
        if (fieldValue instanceof Iterator<?> || MultiValue.isMultiValue(fieldValue)) {
          final var coll = MultiValue.getMultiValueIterator(fieldValue);

          subProcess =
              new TraverseMultiValueProcess(
                  command, (Iterator<Object>) coll, path.appendField(field.toString()), session);
        } else if (fieldValue instanceof Identifiable
            && transaction1.load(((Identifiable) fieldValue)) instanceof EntityImpl) {
          var transaction = session.getActiveTransaction();
          subProcess =
              new TraverseRecordProcess(
                  command,
                  transaction.load(((Identifiable) fieldValue)),
                  path.appendField(field.toString()), session);
        } else {
          continue;
        }

        command.getContext().push(subProcess);
      }
    }
  }

  @Override
  public String toString() {
    return target != null ? target.getIdentity().toString() : "-";
  }

  @Override
  public TraversePath getPath() {
    return path;
  }

  public Identifiable drop() {
    command.getContext().pop(null);
    return null;
  }

  @Override
  public Identifiable pop() {
    command.getContext().pop(target);
    return null;
  }
}
