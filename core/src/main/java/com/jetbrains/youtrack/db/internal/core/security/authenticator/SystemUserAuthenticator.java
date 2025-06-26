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
package com.jetbrains.youtrack.db.internal.core.security.authenticator;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityRole;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a default password authenticator.
 */
public class SystemUserAuthenticator extends SecurityAuthenticatorAbstract {

  private static final Logger logger = LoggerFactory.getLogger(SystemUserAuthenticator.class);

  // SecurityComponent
  // Called once the Server is running.
  public void active() {
    LogManager.instance().debug(this, "SystemUserAuthenticator is active", logger);
  }

  // SecurityComponent
  // Called on removal of the authenticator.
  public void dispose() {
  }

  // SecurityAuthenticator
  // Returns the actual username if successful, null otherwise.
  // This will authenticate username using the system database.
  @Nullable
  public SecurityUser authenticate(
      DatabaseSessionInternal session, final String username, final String password) {

    try {
      if (getSecurity() != null) {
        // dbName parameter is null because we don't need to filter any roles for this.
        var user = getSecurity().getSystemUser(username, null);

        if (user != null && user.getAccountStatus(session) == SecurityUser.STATUSES.ACTIVE) {
          if (user.checkPassword(session, password)) {
            return user;
          }
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "authenticate()", ex);
    }

    return null;
  }

  // SecurityAuthenticator
  // If not supported by the authenticator, return false.
  // Checks to see if a
  public boolean isAuthorized(DatabaseSessionInternal session, final String username,
      final String resource) {
    if (username == null || resource == null) {
      return false;
    }

    try {
      if (getSecurity() != null) {
        var user = getSecurity().getSystemUser(username, null);

        if (user != null && user.getAccountStatus(session) == SecurityUser.STATUSES.ACTIVE) {
          SecurityRole role = null;

          var rg = Rule.mapLegacyResourceToGenericResource(resource);

          if (rg != null) {
            var specificResource = Rule.mapLegacyResourceToSpecificResource(resource);

            if (specificResource == null || specificResource.equals("*")) {
              specificResource = null;
            }

            role = user.checkIfAllowed(session, rg, specificResource, Role.PERMISSION_EXECUTE);
          }

          return role != null;
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "isAuthorized()", ex);
    }

    return false;
  }

  // SecurityAuthenticator
  public SecurityUser getUser(final String username, DatabaseSessionInternal session) {
    SecurityUser userCfg = null;

    try {
      if (getSecurity() != null) {
        userCfg = getSecurity().getSystemUser(username, null);
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "getUser()", ex);
    }

    return userCfg;
  }
}
