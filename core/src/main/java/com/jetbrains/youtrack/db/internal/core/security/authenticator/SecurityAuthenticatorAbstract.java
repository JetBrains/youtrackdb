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
package com.jetbrains.youtrack.db.internal.core.security.authenticator;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrack.db.internal.core.security.SecurityAuthenticator;
import com.jetbrains.youtrack.db.internal.core.security.SecuritySystem;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import java.util.Map;
import javax.annotation.Nullable;
import javax.security.auth.Subject;

/**
 * Provides an abstract implementation of SecurityAuthenticator.
 */
public abstract class SecurityAuthenticatorAbstract implements SecurityAuthenticator {

  private String name = "";
  private boolean debug = false;
  private boolean enabled = true;
  private boolean caseSensitive = true;
  private SecuritySystem security;

  protected SecuritySystem getSecurity() {
    return security;
  }

  protected boolean isDebug() {
    return debug;
  }

  protected boolean isCaseSensitive() {
    return caseSensitive;
  }

  // SecurityComponent
  public void active() {
  }

  // SecurityComponent
  public void config(DatabaseSessionEmbedded session, final Map<String, Object> jsonConfig,
      SecuritySystem security) {
    this.security = security;
    if (jsonConfig != null) {
      if (jsonConfig.containsKey("name")) {
        name = jsonConfig.get("name").toString();
      }

      if (jsonConfig.containsKey("debug")) {
        debug = (Boolean) jsonConfig.get("debug");
      }

      if (jsonConfig.containsKey("enabled")) {
        enabled = (Boolean) jsonConfig.get("enabled");
      }

      if (jsonConfig.containsKey("caseSensitive")) {
        caseSensitive = (Boolean) jsonConfig.get("caseSensitive");
      }
    }
  }

  // SecurityComponent
  public void dispose() {
  }

  // SecurityComponent
  public boolean isEnabled() {
    return enabled;
  }

  // SecurityAuthenticator
  // databaseName may be null.
  public String getAuthenticationHeader(String databaseName) {
    String header;

    // Default to Basic.
    if (databaseName != null) {
      header = "WWW-Authenticate: Basic realm=\"YouTrackDB db-" + databaseName + "\"";
    } else {
      header = "WWW-Authenticate: Basic realm=\"YouTrackDB Server\"";
    }

    return header;
  }

  @Nullable
  public Subject getClientSubject() {
    return null;
  }

  // Returns the name of this SecurityAuthenticator.
  public String getName() {
    return name;
  }

  @Nullable
  public SecurityUser getUser(final String username, DatabaseSessionInternal session) {
    return null;
  }

  public boolean isAuthorized(DatabaseSessionInternal session, final String username,
      final String resource) {
    return false;
  }

  @Nullable
  @Override
  public SecurityUser authenticate(
      DatabaseSessionInternal session, AuthenticationInfo authenticationInfo) {
    // Return null means no valid authentication
    return null;
  }

  public boolean isSingleSignOnSupported() {
    return false;
  }

  protected boolean isPasswordValid(DatabaseSessionInternal session, final SecurityUser user) {
    return user != null && user.getPassword(session) != null && !user.getPassword(session)
        .isEmpty();
  }
}
