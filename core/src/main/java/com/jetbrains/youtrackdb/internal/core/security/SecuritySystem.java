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
package com.jetbrains.youtrackdb.internal.core.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides a basic interface for a modular security system.
 */
public interface SecuritySystem {

  void shutdown();

  // Some external security implementations may permit falling back to a
  // default authentication mode if external authentication fails.
  boolean isDefaultAllowed();

  // Returns the actual username if successful, null otherwise.
  // Some token-based authentication (e.g., SPNEGO tokens have the user's name embedded in the
  // service ticket).
  SecurityUser authenticate(
      DatabaseSessionInternal session, final String username, final String password);

  // Used for generating the appropriate HTTP authentication mechanism. The chain of authenticators
  // is used for this.
  String getAuthenticationHeader(final String databaseName);

  default Map<String, String> getAuthenticationHeaders(final String databaseName) {
    return new HashMap<>();
  }

  Map<String, Object> getConfig();

  Map<String, Object> getComponentConfig(final String name);

  /**
   * Returns the "System User" associated with 'username' from the system database. If not found,
   * returns null. dbName is used to filter the assigned roles. It may be null.
   */
  SecurityUser getSystemUser(final String username, final String dbName);

  // Walks through the list of Authenticators.
  boolean isAuthorized(DatabaseSessionInternal session, final String username,
      final String resource);

  boolean isEnabled();

  // Indicates if passwords should be stored when creating new users.
  boolean arePasswordsStored();

  // Indicates if the primary security mechanism supports single sign-on.
  boolean isSingleSignOnSupported();

  /**
   * Logs to the auditing service, if installed.
   *
   * @param session
   * @param dbName  May be null or empty.
   * @param user    May be null or empty.
   */
  void log(
      DatabaseSessionInternal session, final AuditingOperation operation,
      final String dbName,
      SecurityUser user,
      final String message);

  void registerSecurityClass(final Class<?> cls);

  void reload(DatabaseSessionEmbedded session, final Map<String, Object> jsonConfig);

  void reload(DatabaseSessionEmbedded session, SecurityUser user,
      final Map<String, Object> jsonConfig);

  void reloadComponent(DatabaseSessionEmbedded session, SecurityUser user, final String name,
      final Map<String, Object> jsonConfig);

  void unregisterSecurityClass(final Class<?> cls);

  // If a password validator is registered with the security system, it will be called to validate
  // the specified password. An InvalidPasswordException is thrown if the password does not meet
  // the password validator's requirements.
  void validatePassword(final String username, final String password)
      throws InvalidPasswordException;

  AuditingService getAuditing();

  /**
   * Returns the authenticator based on name, if one exists.
   */
  SecurityAuthenticator getAuthenticator(final String authName);

  /**
   * Returns the first authenticator in the list, which is the primary authenticator.
   */
  SecurityAuthenticator getPrimaryAuthenticator();

  Syslog getSyslog();

  /**
   * Some authenticators support maintaining a list of users and associated resources (and sometimes
   * passwords).
   */
  SecurityUser getUser(final String username, DatabaseSessionInternal session);

  void onAfterDynamicPlugins(DatabaseSessionEmbedded session);

  default void onAfterDynamicPlugins(DatabaseSessionEmbedded session, SecurityUser user) {
    onAfterDynamicPlugins(session);
  }

  SecurityUser authenticateAndAuthorize(
      DatabaseSessionInternal session, String iUserName, String iPassword,
      String iResourceToCheck);

  SecurityUser authenticateServerUser(DatabaseSessionInternal session, String username,
      String password);

  SecurityUser getServerUser(DatabaseSessionInternal session, String username);

  boolean isServerUserAuthorized(DatabaseSessionInternal session, String username,
      String resource);

  YouTrackDBInternalEmbedded getContext();

  boolean existsUser(String defaultRootUser);

  void addTemporaryUser(String user, String password, String resources);

  SecurityInternal newSecurity(String database);

  SecurityUser authenticate(DatabaseSessionInternal session,
      AuthenticationInfo authenticationInfo);

  TokenSign getTokenSign();
}
