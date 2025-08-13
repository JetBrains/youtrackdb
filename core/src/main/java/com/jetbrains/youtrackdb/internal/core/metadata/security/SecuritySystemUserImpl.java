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
package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SecuritySystemUserImpl extends SecurityUserImpl {
  public static final String SYSTEM_USER = "SYSTEM_USER";

  private final String databaseName;
  private final Set<Role> systemRoles = ConcurrentHashMap.newKeySet();

  /**
   * dbName is the name of the source database and is used for filtering roles.
   */
  public SecuritySystemUserImpl(DatabaseSessionInternal session, final EntityImpl source,
      final String dbName) {
    super(session, source);

    databaseName = dbName;
    populateSystemRoles(session);
  }

  @Override
  public SecurityUserImpl addRole(DatabaseSessionInternal session, SecurityRole role) {
    super.addRole(session, role);
    populateSystemRoles(session);
    return this;
  }

  @Override
  public boolean removeRole(DatabaseSessionInternal session, String roleName) {
    var result = super.removeRole(session, roleName);

    if (result) {
      populateSystemRoles(session);
    }

    return result;
  }

  @Override
  public String getUserType() {
    return SYSTEM_USER;
  }

  @Override
  public Set<Role> getRoles() {
    return Collections.unmodifiableSet(systemRoles);
  }

  private void populateSystemRoles(DatabaseSessionInternal databaseSession) {
    systemRoles.clear();

    for (var role : roles) {
      Identifiable identifiable = role.getIdentity();
      var transaction = databaseSession.getActiveTransaction();
      var entity = transaction.loadEntity(identifiable);
      // If databaseName is set, then only allow roles with the same databaseName.
      if (databaseName != null && !databaseName.isEmpty()) {
        List<String> dbNames = entity.getProperty(SystemRole.DB_FILTER);
        for (var dbName : dbNames) {
          if (!dbName.isEmpty()
              && (dbName.equalsIgnoreCase(databaseName) || dbName.equals("*"))) {
            systemRoles.add(role);
            break;
          }
        }

      } else {
        // If databaseName is not set, only return roles without a SystemRole.DB_FILTER property or
        // if set to "*".
        List<String> dbNames = entity.getProperty(SystemRole.DB_FILTER);
        if (dbNames == null || dbNames.isEmpty()) {
          systemRoles.add(role);
        } else { // It does use the dbFilter property.
          for (var dbName : dbNames) {
            if (dbName != null && dbName.equals("*")) {
              systemRoles.add(role);
              break;
            }
          }
        }
      }
    }
  }
}
