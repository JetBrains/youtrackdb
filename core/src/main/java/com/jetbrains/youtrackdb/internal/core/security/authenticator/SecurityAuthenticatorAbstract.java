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
package com.jetbrains.youtrackdb.internal.core.security.authenticator;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.security.SecurityAuthenticator;
import com.jetbrains.youtrackdb.internal.core.security.SecuritySystem;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
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
  @Override
  public void active() {
  }

  // SecurityComponent
  @Override
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
  @Override
  public void dispose() {
  }

  // SecurityComponent
  @Override
  public boolean isEnabled() {
    return enabled;
  }

  // SecurityAuthenticator
  // databaseName may be null.
  @Override
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
  @Override
  public Subject getClientSubject() {
    return null;
  }

  // Returns the name of this SecurityAuthenticator.
  @Override
  public String getName() {
    return name;
  }

  @Nullable
  @Override
  public SecurityUser getUser(final String username, DatabaseSessionEmbedded session) {
    return null;
  }

  @Override
  public boolean isAuthorized(DatabaseSessionEmbedded session, final String username,
      final String resource) {
    return false;
  }

  @Nullable
  @Override
  public SecurityUser authenticate(
      DatabaseSessionEmbedded session, AuthenticationInfo authenticationInfo) {
    // Return null means no valid authentication
    return null;
  }

  @Override
  public boolean isSingleSignOnSupported() {
    return false;
  }

  protected boolean isPasswordValid(DatabaseSessionEmbedded session, final SecurityUser user) {
    return user != null && user.getPassword(session) != null && !user.getPassword(session)
        .isEmpty();
  }
}
