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
package com.jetbrains.youtrackdb.internal.core.security.authenticator;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides an SecurityAuthenticator for the users listed in youtrackdb-server-config.xml.
 */
public class ServerConfigAuthenticator extends SecurityAuthenticatorAbstract {

  private static final Logger logger = LoggerFactory.getLogger(ServerConfigAuthenticator.class);

  // SecurityComponent
  // Called once the Server is running.
  public void active() {
    LogManager.instance().debug(this, "ServerConfigAuthenticator is active", logger);
  }

  // SecurityAuthenticator
  // Returns the actual username if successful, null otherwise.
  public SecurityUser authenticate(
      DatabaseSessionInternal session, final String username, final String password) {
    return getSecurity().authenticateServerUser(session, username, password);
  }

  // SecurityAuthenticator
  public SecurityUser getUser(final String username, DatabaseSessionInternal session) {
    return getSecurity().getServerUser(session, username);
  }

  // SecurityAuthenticator
  // If not supported by the authenticator, return false.
  public boolean isAuthorized(DatabaseSessionInternal session, final String username,
      final String resource) {
    return getSecurity().isServerUserAuthorized(session, username, resource);
  }

  // Server configuration users are never case sensitive.
  @Override
  protected boolean isCaseSensitive() {
    return false;
  }
}
