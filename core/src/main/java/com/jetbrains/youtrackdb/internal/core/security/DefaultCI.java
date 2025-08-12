/*
 *
 *  *  Copyright 2017 YouTrackDB LTD
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

import com.jetbrains.youtrackdb.api.exception.SecurityException;

/**
 * Provides a default credential interceptor that does nothing.
 */
public class DefaultCI implements CredentialInterceptor {

  private String username;
  private String password;

  public String getUsername() {
    return this.username;
  }

  public String getPassword() {
    return this.password;
  }

  public void intercept(final String url, final String username, final String password)
      throws SecurityException {
    this.username = username;
    this.password = password;
  }
}
