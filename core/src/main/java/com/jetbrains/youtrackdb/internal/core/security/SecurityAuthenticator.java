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
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.Subject;

/**
 * Provides an interface for creating security authenticators.
 */
public interface SecurityAuthenticator extends SecurityComponent {

  // Returns the actual username if successful, null otherwise.
  // Some token-based authentication (e.g., SPNEGO tokens have the user's name embedded in the
  // service ticket).
  SecurityUser authenticate(
      DatabaseSessionEmbedded session, final String username, final String password);

  SecurityUser authenticate(DatabaseSessionEmbedded session,
      AuthenticationInfo authenticationInfo);

  String getAuthenticationHeader(final String databaseName);

  default Map<String, String> getAuthenticationHeaders(String databaseName) {
    return new HashMap<>();
  }

  Subject getClientSubject();

  // Returns the name of this SecurityAuthenticator.
  String getName();

  SecurityUser getUser(final String username, DatabaseSessionEmbedded session);

  boolean isAuthorized(DatabaseSessionEmbedded session, final String username,
      final String resource);

  boolean isSingleSignOnSupported();
}
