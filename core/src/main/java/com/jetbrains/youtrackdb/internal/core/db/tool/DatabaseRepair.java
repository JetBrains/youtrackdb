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
package com.jetbrains.youtrackdb.internal.core.db.tool;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import java.util.List;

/**
 * Repair database tool.
 *
 * @since v2.2.0
 */
public class DatabaseRepair extends DatabaseTool<DatabaseSessionEmbedded> {

  private boolean removeBrokenLinks = true;

  public DatabaseRepair(DatabaseSessionEmbedded session) {
    setDatabaseSession(session);
  }

  @Override
  protected void parseSetting(final String option, final List<String> items) {
    if (option.equalsIgnoreCase("-excludeAll")) {

      removeBrokenLinks = false;

    } else if (option.equalsIgnoreCase("-removeBrokenLinks")) {

      removeBrokenLinks = Boolean.parseBoolean(items.get(0));
    }
  }

  public void run() {
    long errors = 0;

    if (removeBrokenLinks) {
      errors += removeBrokenLinks();
    }

    message("\nRepair database complete (" + errors + " errors)");
  }

  protected long removeBrokenLinks() {
    var fixedLinks = 0L;
    var modifiedEntities = 0L;
    var errors = 0L;

    message("\n- Removing broken links...");
    for (var collectionName : session.getCollectionNames()) {
      var recIterator = session.browseCollection(collectionName);
      while (recIterator.hasNext()) {
        final var rec = recIterator.next();
        try {
          if (rec instanceof EntityImpl entity) {
            var changed = false;

            for (var fieldName : entity.propertyNames()) {
              final var fieldValue = entity.getProperty(fieldName);

              if (fieldValue instanceof Identifiable) {
                if (fixLink(fieldValue)) {
                  entity.setProperty(fieldName, null);
                  fixedLinks++;
                  changed = true;
                  if (verbose) {
                    message(
                        "\n--- reset link "
                            + ((Identifiable) fieldValue).getIdentity()
                            + " in field '"
                            + fieldName
                            + "' (rid="
                            + entity.getIdentity()
                            + ")");
                  }
                }
              } else if (fieldValue instanceof Iterable<?>) {
                final Iterator<Object> it = ((Iterable) fieldValue).iterator();
                for (var i = 0; it.hasNext(); ++i) {
                  final var v = it.next();
                  if (fixLink(v)) {
                    it.remove();
                    fixedLinks++;
                    changed = true;
                    if (verbose) {
                      message(
                          "\n--- reset link "
                              + ((Identifiable) v).getIdentity()
                              + " as item "
                              + i
                              + " in collection of field '"
                              + fieldName
                              + "' (rid="
                              + entity.getIdentity()
                              + ")");
                    }
                  }
                }
              }
            }

            if (changed) {
              modifiedEntities++;

              if (verbose) {
                message("\n-- updated entity " + entity.getIdentity());
              }
            }
          }
        } catch (Exception ignore) {
          errors++;
        }
      }
    }

    message("\n-- Done! Fixed links: " + fixedLinks + ", modified entities: " + modifiedEntities);
    return errors;
  }

  /**
   * Checks if the link must be fixed.
   *
   * @param fieldValue Field containing the Identifiable (RID or Record)
   * @return true to fix it, otherwise false
   */
  protected boolean fixLink(final Object fieldValue) {
    if (fieldValue instanceof Identifiable) {
      final var id = ((Identifiable) fieldValue).getIdentity();

      if (id.getCollectionId() == 0 && id.getCollectionPosition() == 0) {
        return true;
      }

      if (((RecordId) id).isValidPosition()) {
        if (id.isPersistent()) {
          try {
            var transaction = session.getActiveTransaction();
            transaction.load(((Identifiable) fieldValue));
          } catch (RecordNotFoundException rnf) {
            return true;
          }
        } else {
          return true;
        }
      }
    }
    return false;
  }
}
