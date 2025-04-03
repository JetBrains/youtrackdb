/*
 *
 *  *  Copyright YouTrackDB
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
import com.jetbrains.youtrack.db.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.security.SecurityManager;
import com.jetbrains.youtrack.db.internal.core.security.SecuritySystem;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Provides a default password authenticator.
 */
public class DefaultPasswordAuthenticator extends SecurityAuthenticatorAbstract {

  // Holds a map of the users specified in the security.json file.
  private ConcurrentHashMap<String, SecurityUser> usersMap =
      new ConcurrentHashMap<String, SecurityUser>();

  // SecurityComponent
  // Called once the Server is running.
  public void active() {
    LogManager.instance().debug(this, "DefaultPasswordAuthenticator is active");
  }

  // SecurityComponent
  public void config(DatabaseSessionInternal session, final Map<String, Object> jsonConfig,
      SecuritySystem security) {
    super.config(session, jsonConfig, security);

    try {
      if (jsonConfig.containsKey("users")) {
        @SuppressWarnings("unchecked")
        var usersList = (List<Map<String, Object>>) jsonConfig.get("users");

        for (var userDoc : usersList) {

          var userCfg = createServerUser(session, userDoc);

          if (userCfg != null) {
            var checkName = userCfg.getName(session);

            if (!isCaseSensitive()) {
              checkName = checkName.toLowerCase(Locale.ENGLISH);
            }

            usersMap.put(checkName, userCfg);
          }
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "config()", ex);
    }
  }

  // Derived implementations can override this method to provide new server user implementations.
  protected SecurityUser createServerUser(DatabaseSessionInternal session,
      final Map<String, Object> userMap) {
    SecurityUser userCfg = null;

    if (userMap.containsKey("username") && userMap.containsKey("resources")) {
      final var user = userMap.get("username").toString();
      final var resources = userMap.get("resources").toString();
      var password = (String) userMap.get("password");

      if (password == null) {
        password = "";
      }
      userCfg = new ImmutableUser(session, user, SecurityUser.SERVER_USER_TYPE);
      // userCfg.addRole(SecurityShared.createRole(null, user));
    }

    return userCfg;
  }

  // SecurityComponent
  // Called on removal of the authenticator.
  public void dispose() {
    synchronized (usersMap) {
      usersMap.clear();
      usersMap = null;
    }
  }

  // SecurityAuthenticator
  // Returns the actual username if successful, null otherwise.
  @Nullable
  public SecurityUser authenticate(
      DatabaseSessionInternal session, final String username, final String password) {

    try {
      var user = getUser(username, session);

      if (isPasswordValid(session, user)) {
        if (SecurityManager.checkPassword(password, user.getPassword(session))) {
          return user;
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultPasswordAuthenticator.authenticate()", ex);
    }
    return null;
  }

  // SecurityAuthenticator
  // If not supported by the authenticator, return false.
  public boolean isAuthorized(DatabaseSessionInternal session, final String username,
      final String resource) {
    if (username == null || resource == null) {
      return false;
    }

    var userCfg = getUser(username, session);

    if (userCfg != null) {
      // TODO: to verify if this logic match previous logic
      return userCfg.checkIfAllowed(session, resource, Role.PERMISSION_ALL) != null;

      // Total Access
      /*
      if (userCfg.getResources().equals("*")) return true;

      String[] resourceParts = userCfg.getResources().split(",");

      for (String r : resourceParts) {
        if (r.equalsIgnoreCase(resource)) return true;
      }
      */
    }

    return false;
  }

  // SecurityAuthenticator
  public SecurityUser getUser(final String username, DatabaseSessionInternal session) {
    SecurityUser userCfg = null;

    synchronized (usersMap) {
      if (username != null) {
        var checkName = username;

        if (!isCaseSensitive()) {
          checkName = username.toLowerCase(Locale.ENGLISH);
        }

        if (usersMap.containsKey(checkName)) {
          userCfg = usersMap.get(checkName);
        }
      }
    }

    return userCfg;
  }
}
