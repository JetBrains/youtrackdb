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
package com.jetbrains.youtrackdb.internal.core.security.symmetrickey;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityPolicyImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityRole;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Token;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

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
      DatabaseSessionEmbedded session, String userName, String password) {
    return authenticate(session, userName, password);
  }

  @Override
  public SecurityUser authenticate(
      DatabaseSessionEmbedded session, final String username, final String password) {
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
      var identifiable = user.getIdentity();
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
      DatabaseSessionEmbedded session,
      final Set<Identifiable> iAllowAll,
      final Set<Identifiable> iAllowOperation) {
    return delegate.isAllowed(session, iAllowAll, iAllowOperation);
  }


  @Override
  public SecurityUserImpl create(DatabaseSessionEmbedded session) {
    return delegate.create(session);
  }

  @Override
  public void load(DatabaseSessionEmbedded session) {
    delegate.load(session);
  }

  @Override
  @Nullable
  public SecurityUser authenticate(DatabaseSessionEmbedded session, final Token authToken) {
    return null;
  }

  @Override
  public SecurityUser getUser(DatabaseSessionEmbedded session, final String iUserName) {
    return delegate.getUser(session, iUserName);
  }

  @Override
  public SecurityUserImpl getUser(DatabaseSessionEmbedded session, final RID iUserId) {
    return delegate.getUser(session, iUserId);
  }

  @Override
  public SecurityUserImpl createUser(
      DatabaseSessionEmbedded session,
      final String iUserName,
      final String iUserPassword,
      final String... iRoles) {
    return delegate.createUser(session, iUserName, iUserPassword, iRoles);
  }

  @Override
  public SecurityUserImpl createUser(
      DatabaseSessionEmbedded session,
      final String iUserName,
      final String iUserPassword,
      final Role... iRoles) {
    return delegate.createUser(session, iUserName, iUserPassword, iRoles);
  }

  @Override
  public Role getRole(DatabaseSessionEmbedded session, final String iRoleName) {
    return delegate.getRole(session, iRoleName);
  }

  @Override
  public Role getRole(DatabaseSessionEmbedded session, final Identifiable iRole) {
    return delegate.getRole(session, iRole);
  }

  @Override
  public Role createRole(
      DatabaseSessionEmbedded session,
      final String iRoleName) {
    return delegate.createRole(session, iRoleName);
  }

  @Override
  public Role createRole(
      DatabaseSessionEmbedded session,
      final String iRoleName,
      final Role iParent) {
    return delegate.createRole(session, iRoleName, iParent);
  }

  @Override
  public List<EntityImpl> getAllUsers(DatabaseSessionEmbedded session) {
    return delegate.getAllUsers(session);
  }

  @Override
  public List<EntityImpl> getAllRoles(DatabaseSessionEmbedded session) {
    return delegate.getAllRoles(session);
  }

  @Override
  public Map<String, ? extends SecurityPolicy> getSecurityPolicies(
      DatabaseSessionEmbedded session, SecurityRole role) {
    return delegate.getSecurityPolicies(session, role);
  }

  @Override
  public SecurityPolicy getSecurityPolicy(
      DatabaseSessionEmbedded session, SecurityRole role, String resource) {
    return delegate.getSecurityPolicy(session, role, resource);
  }

  @Override
  public void setSecurityPolicy(
      DatabaseSessionEmbedded session, SecurityRole role, String resource,
      SecurityPolicyImpl policy) {
    delegate.setSecurityPolicy(session, role, resource, policy);
  }

  @Override
  public SecurityPolicyImpl createSecurityPolicy(DatabaseSessionEmbedded session, String name) {
    return delegate.createSecurityPolicy(session, name);
  }

  @Override
  public SecurityPolicyImpl getSecurityPolicy(DatabaseSessionEmbedded session, String name) {
    return delegate.getSecurityPolicy(session, name);
  }

  @Override
  public void saveSecurityPolicy(DatabaseSessionEmbedded session, SecurityPolicyImpl policy) {
    delegate.saveSecurityPolicy(session, policy);
  }

  @Override
  public void deleteSecurityPolicy(DatabaseSessionEmbedded session, String name) {
    delegate.deleteSecurityPolicy(session, name);
  }

  @Override
  public void removeSecurityPolicy(DatabaseSessionEmbedded session, Role role, String resource) {
    delegate.removeSecurityPolicy(session, role, resource);
  }

  public String toString() {
    return delegate.toString();
  }

  @Override
  public boolean dropUser(DatabaseSessionEmbedded session, final String iUserName) {
    return delegate.dropUser(session, iUserName);
  }

  @Override
  public boolean dropRole(DatabaseSessionEmbedded session, final String iRoleName) {
    return delegate.dropRole(session, iRoleName);
  }

  @Override
  public long getVersion(DatabaseSessionEmbedded session) {
    return delegate.getVersion(session);
  }

  @Override
  public void incrementVersion(DatabaseSessionEmbedded session) {
    delegate.incrementVersion(session);
  }

  @Override
  public Set<String> getFilteredProperties(DatabaseSessionEmbedded session,
      EntityImpl entity) {
    return delegate.getFilteredProperties(session, entity);
  }

  @Override
  public boolean isAllowedWrite(DatabaseSessionEmbedded session, EntityImpl entity,
      String propertyName) {
    return delegate.isAllowedWrite(session, entity, propertyName);
  }

  @Override
  public boolean canCreate(DatabaseSessionEmbedded session, DBRecord record) {
    return delegate.canCreate(session, record);
  }

  @Override
  public boolean canRead(DatabaseSessionEmbedded session, DBRecord record) {
    return delegate.canRead(session, record);
  }

  @Override
  public boolean canUpdate(DatabaseSessionEmbedded session, DBRecord record) {
    return delegate.canUpdate(session, record);
  }

  @Override
  public boolean canDelete(DatabaseSessionEmbedded session, DBRecord record) {
    return delegate.canDelete(session, record);
  }

  @Override
  public boolean canExecute(DatabaseSessionEmbedded session, Function function) {
    return delegate.canExecute(session, function);
  }

  @Override
  public boolean isReadRestrictedBySecurityPolicy(DatabaseSessionEmbedded session, String resource) {
    return delegate.isReadRestrictedBySecurityPolicy(session, resource);
  }

  @Override
  public Set<SecurityResourceProperty> getAllFilteredProperties(
      DatabaseSessionEmbedded database) {
    return delegate.getAllFilteredProperties(database);
  }

  @Override
  public SecurityUser securityAuthenticate(
      DatabaseSessionEmbedded session, AuthenticationInfo authenticationInfo) {
    return delegate.securityAuthenticate(session, authenticationInfo);
  }

  @Override
  public void close() {
  }
}
