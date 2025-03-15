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
package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import java.util.List;

/**
 * Repair database tool.
 *
 * @since v2.2.0
 */
public class DatabaseRepair extends DatabaseTool {

  private boolean removeBrokenLinks = true;
  private final DatabaseSessionInternal db;

  public DatabaseRepair(DatabaseSessionInternal db) {
    this.db = db;
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
      errors += removeBrokenLinks(db);
    }

    message("\nRepair database complete (" + errors + " errors)");
  }

  protected long removeBrokenLinks(DatabaseSessionInternal db) {
    var fixedLinks = 0L;
    var modifiedEntities = 0L;
    var errors = 0L;

    message("\n- Removing broken links...");
    for (var clusterName : session.getClusterNames()) {
      var recIterator = session.browseCluster(clusterName);
      while (recIterator.hasNext()) {
        final var rec = recIterator.next();
        try {
          if (rec instanceof EntityImpl entity) {
            var changed = false;

            for (var fieldName : entity.propertyNames()) {
              final var fieldValue = entity.getProperty(fieldName);

              if (fieldValue instanceof Identifiable) {
                if (fixLink(fieldValue, db)) {
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
                  if (fixLink(v, db)) {
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
   * @param db
   * @return true to fix it, otherwise false
   */
  protected boolean fixLink(final Object fieldValue, DatabaseSessionInternal db) {
    if (fieldValue instanceof Identifiable) {
      final var id = ((Identifiable) fieldValue).getIdentity();

      if (id.getClusterId() == 0 && id.getClusterPosition() == 0) {
        return true;
      }

      if (((RecordId) id).isValid()) {
        if (id.isPersistent()) {
          try {
            ((Identifiable) fieldValue).getRecord(db);
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
