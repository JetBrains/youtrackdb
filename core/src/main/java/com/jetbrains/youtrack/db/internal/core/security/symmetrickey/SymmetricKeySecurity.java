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
package com.jetbrains.youtrack.db.internal.core.security.symmetrickey;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import com.jetbrains.youtrack.db.internal.core.metadata.security.RestrictedOperation;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityPolicyImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityRole;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.security.SecurityManager;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides a symmetric key specific authentication. Implements an Security interface that delegates
 * to the specified Security object.
 *
 * <p>This is used with embedded (non-server) databases, like so:
 * db.setProperty(ODatabase.OPTIONS.SECURITY.toString(), SymmetricKeySecurity.class);
 */
public class SymmetricKeySecurity implements SecurityInternal {

  private final SecurityInternal delegate;

  public SymmetricKeySecurity(final SecurityInternal iDelegate) {
    this.delegate = iDelegate;
  }

  @Override
  public SecurityUser securityAuthenticate(
      DatabaseSessionInternal session, String userName, String password) {
    return authenticate(session, userName, password);
  }

  public SecurityUserImpl authenticate(
      DatabaseSessionInternal session, final String username, final String password) {
    if (delegate == null) {
      throw new SecurityAccessException(
          "OSymmetricKeySecurity.authenticate() Delegate is null for username: " + username);
    }

    if (session == null) {
      throw new SecurityAccessException(
          "OSymmetricKeySecurity.authenticate() Database is null for username: " + username);
    }

    final var dbName = session.getDatabaseName();

    var user = delegate.getUser(session, username);

    if (user == null) {
      throw new SecurityAccessException(
          dbName,
          "OSymmetricKeySecurity.authenticate() Username or Key is invalid for username: "
              + username);
    }

    if (user.getAccountStatus(session) != SecurityUser.STATUSES.ACTIVE) {
      throw new SecurityAccessException(
          dbName, "OSymmetricKeySecurity.authenticate() User '" + username + "' is not active");
    }

    try {
      Identifiable identifiable = user.getIdentity();
      var transaction = session.getActiveTransaction();
      var userConfig = new UserSymmetricKeyConfig(
          transaction.loadEntity(identifiable).toMap(false));

      var sk = SymmetricKey.fromConfig(userConfig);

      var decryptedUsername = sk.decryptAsString(password);

      if (SecurityManager.checkPassword(username, decryptedUsername)) {
        return user;
      }
    } catch (Exception ex) {
      throw BaseException.wrapException(
          new SecurityAccessException(
              dbName,
              "OSymmetricKeySecurity.authenticate() Exception for session: "
                  + dbName
                  + ", username: "
                  + username
                  + " "
                  + ex.getMessage()),
          ex, session.getDatabaseName());
    }

    throw new SecurityAccessException(
        dbName,
        "OSymmetricKeySecurity.authenticate() Username or Key is invalid for session: "
            + dbName
            + ", username: "
            + username);
  }

  @Override
  public boolean isAllowed(
      DatabaseSessionInternal session,
      final Set<Identifiable> iAllowAll,
      final Set<Identifiable> iAllowOperation) {
    return delegate.isAllowed(session, iAllowAll, iAllowOperation);
  }


  public SecurityUserImpl create(DatabaseSessionInternal session) {
    return delegate.create(session);
  }

  public void load(DatabaseSessionInternal session) {
    delegate.load(session);
  }

  public SecurityUserImpl authenticate(DatabaseSessionInternal session, final Token authToken) {
    return null;
  }

  public SecurityUserImpl getUser(DatabaseSession session, final String iUserName) {
    return delegate.getUser(session, iUserName);
  }

  public SecurityUserImpl getUser(DatabaseSession session, final RID iUserId) {
    return delegate.getUser(session, iUserId);
  }

  public SecurityUserImpl createUser(
      DatabaseSessionInternal session,
      final String iUserName,
      final String iUserPassword,
      final String... iRoles) {
    return delegate.createUser(session, iUserName, iUserPassword, iRoles);
  }

  public SecurityUserImpl createUser(
      DatabaseSessionInternal session,
      final String iUserName,
      final String iUserPassword,
      final Role... iRoles) {
    return delegate.createUser(session, iUserName, iUserPassword, iRoles);
  }

  public Role getRole(DatabaseSession session, final String iRoleName) {
    return delegate.getRole(session, iRoleName);
  }

  public Role getRole(DatabaseSession session, final Identifiable iRole) {
    return delegate.getRole(session, iRole);
  }

  public Role createRole(
      DatabaseSessionInternal session,
      final String iRoleName) {
    return delegate.createRole(session, iRoleName);
  }

  public Role createRole(
      DatabaseSessionInternal session,
      final String iRoleName,
      final Role iParent) {
    return delegate.createRole(session, iRoleName, iParent);
  }

  public List<EntityImpl> getAllUsers(DatabaseSession session) {
    return delegate.getAllUsers(session);
  }

  public List<EntityImpl> getAllRoles(DatabaseSession session) {
    return delegate.getAllRoles(session);
  }

  @Override
  public Map<String, ? extends SecurityPolicy> getSecurityPolicies(
      DatabaseSession session, SecurityRole role) {
    return delegate.getSecurityPolicies(session, role);
  }

  @Override
  public SecurityPolicy getSecurityPolicy(
      DatabaseSession session, SecurityRole role, String resource) {
    return delegate.getSecurityPolicy(session, role, resource);
  }

  @Override
  public void setSecurityPolicy(
      DatabaseSessionInternal session, SecurityRole role, String resource,
      SecurityPolicyImpl policy) {
    delegate.setSecurityPolicy(session, role, resource, policy);
  }

  @Override
  public SecurityPolicyImpl createSecurityPolicy(DatabaseSession session, String name) {
    return delegate.createSecurityPolicy(session, name);
  }

  @Override
  public SecurityPolicyImpl getSecurityPolicy(DatabaseSession session, String name) {
    return delegate.getSecurityPolicy(session, name);
  }

  @Override
  public void saveSecurityPolicy(DatabaseSession session, SecurityPolicyImpl policy) {
    delegate.saveSecurityPolicy(session, policy);
  }

  @Override
  public void deleteSecurityPolicy(DatabaseSession session, String name) {
    delegate.deleteSecurityPolicy(session, name);
  }

  @Override
  public void removeSecurityPolicy(DatabaseSession session, Role role, String resource) {
    delegate.removeSecurityPolicy(session, role, resource);
  }

  public String toString() {
    return delegate.toString();
  }

  public boolean dropUser(DatabaseSession session, final String iUserName) {
    return delegate.dropUser(session, iUserName);
  }

  public boolean dropRole(DatabaseSession session, final String iRoleName) {
    return delegate.dropRole(session, iRoleName);
  }

  public void createClassTrigger(DatabaseSessionInternal session) {
    delegate.createClassTrigger(session);
  }

  @Override
  public long getVersion(DatabaseSession session) {
    return delegate.getVersion(session);
  }

  @Override
  public void incrementVersion(DatabaseSession session) {
    delegate.incrementVersion(session);
  }

  @Override
  public Set<String> getFilteredProperties(DatabaseSessionInternal session,
      EntityImpl entity) {
    return delegate.getFilteredProperties(session, entity);
  }

  @Override
  public boolean isAllowedWrite(DatabaseSessionInternal session, EntityImpl entity,
      String propertyName) {
    return delegate.isAllowedWrite(session, entity, propertyName);
  }

  @Override
  public boolean canCreate(DatabaseSessionInternal session, DBRecord record) {
    return delegate.canCreate(session, record);
  }

  @Override
  public boolean canRead(DatabaseSessionInternal session, DBRecord record) {
    return delegate.canRead(session, record);
  }

  @Override
  public boolean canUpdate(DatabaseSessionInternal session, DBRecord record) {
    return delegate.canUpdate(session, record);
  }

  @Override
  public boolean canDelete(DatabaseSessionInternal session, DBRecord record) {
    return delegate.canDelete(session, record);
  }

  @Override
  public boolean canExecute(DatabaseSessionInternal session, Function function) {
    return delegate.canExecute(session, function);
  }

  @Override
  public boolean isReadRestrictedBySecurityPolicy(DatabaseSession session, String resource) {
    return delegate.isReadRestrictedBySecurityPolicy(session, resource);
  }

  @Override
  public Set<SecurityResourceProperty> getAllFilteredProperties(
      DatabaseSessionInternal database) {
    return delegate.getAllFilteredProperties(database);
  }

  @Override
  public SecurityUser securityAuthenticate(
      DatabaseSessionInternal session, AuthenticationInfo authenticationInfo) {
    return delegate.securityAuthenticate(session, authenticationInfo);
  }

  @Override
  public void close() {
  }
}
