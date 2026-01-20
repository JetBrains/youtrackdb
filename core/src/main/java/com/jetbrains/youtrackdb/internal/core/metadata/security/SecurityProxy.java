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
package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import java.util.List;
import java.util.Set;

/**
 * Proxy class for user management
 */
public class SecurityProxy implements Security {

  private final DatabaseSessionEmbedded session;
  private final SecurityInternal security;

  public SecurityProxy(SecurityInternal security, DatabaseSessionEmbedded session) {
    this.security = security;
    this.session = session;
  }

  @Override
  public boolean isAllowed(
      final Set<Identifiable> iAllowAll, final Set<Identifiable> iAllowOperation) {
    return security.isAllowed(session, iAllowAll, iAllowOperation);
  }

  @Override
  public SecurityUser authenticate(final String iUsername, final String iUserPassword) {
    return security.authenticate(session, iUsername, iUserPassword);
  }

  @Override
  public SecurityUser authenticate(final Token authToken) {
    return security.authenticate(session, authToken);
  }

  @Override
  public SecurityUser getUser(final String iUserName) {
    return security.getUser(session, iUserName);
  }

  @Override
  public SecurityUserImpl getUser(final RID iUserId) {
    return security.getUser(session, iUserId);
  }

  @Override
  public SecurityUserImpl createUser(
      final String iUserName, final String iUserPassword, final String... iRoles) {
    return security.createUser(session, iUserName, iUserPassword, iRoles);
  }

  @Override
  public SecurityUserImpl createUser(
      final String iUserName, final String iUserPassword, final Role... iRoles) {
    return security.createUser(session, iUserName, iUserPassword, iRoles);
  }

  @Override
  public Role getRole(final String iRoleName) {
    return security.getRole(session, iRoleName);
  }

  @Override
  public Role getRole(final Identifiable iRole) {
    return security.getRole(session, iRole);
  }

  @Override
  public Role createRole(final String iRoleName) {
    return security.createRole(session, iRoleName);
  }

  @Override
  public Role createRole(
      final String iRoleName, final Role iParent) {
    return security.createRole(session, iRoleName, iParent);
  }

  @Override
  public List<EntityImpl> getAllUsers() {
    return security.getAllUsers(session);
  }

  @Override
  public List<EntityImpl> getAllRoles() {
    return security.getAllRoles(session);
  }

  public String toString() {
    return security.toString();
  }

  @Override
  public boolean dropUser(final String iUserName) {
    return security.dropUser(session, iUserName);
  }

  @Override
  public void dropRole(final String iRoleName) {
    security.dropRole(session, iRoleName);
  }
}
