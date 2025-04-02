package com.jetbrains.youtrack.db.internal.client.remote.metadata.security;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
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
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class SecurityRemote implements SecurityInternal {

  public SecurityRemote() {
  }

  @Override
  public boolean isAllowed(
      DatabaseSessionInternal session, Set<Identifiable> iAllowAll,
      Set<Identifiable> iAllowOperation) {
    return true;
  }

  @Override
  public SecurityUserImpl authenticate(DatabaseSessionInternal session, String iUsername,
      String iUserPassword) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityUserImpl createUser(
      final DatabaseSessionInternal session,
      final String iUserName,
      final String iUserPassword,
      final String... iRoles) {
    final var user = new SecurityUserImpl(session, iUserName, iUserPassword);
    if (iRoles != null) {
      for (var r : iRoles) {
        user.addRole(session, r);
      }
    }
    user.save(session);
    return user;
  }

  @Override
  public SecurityUserImpl createUser(
      final DatabaseSessionInternal session,
      final String userName,
      final String userPassword,
      final Role... roles) {
    final var user = new SecurityUserImpl(session, userName, userPassword);

    if (roles != null) {
      for (var r : roles) {
        user.addRole(session, r);
      }
    }

    user.save(session);
    return user;
  }

  @Override
  public SecurityUserImpl authenticate(DatabaseSessionInternal session, Token authToken) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Role createRole(
      final DatabaseSessionInternal session, final String iRoleName) {
    return createRole(session, iRoleName, null);
  }

  @Override
  public Role createRole(
      final DatabaseSessionInternal session,
      final String iRoleName,
      final Role iParent) {
    final var role = new Role(session, iRoleName, iParent);
    role.save(session);
    return role;
  }

  @Override
  public SecurityUserImpl getUser(final DatabaseSession session, final String iUserName) {
    return session.computeInTx(transaction -> {
      try (var result = transaction.query("select from OUser where name = ? limit 1",
          iUserName)) {
        if (result.hasNext()) {
          return new SecurityUserImpl((DatabaseSessionInternal) session,
              (EntityImpl) result.next().asEntity());
        }
      }
      //noinspection ReturnOfNull
      return null;
    });
  }

  @Nullable
  public SecurityUserImpl getUser(final DatabaseSession session, final RID iRecordId) {
    if (iRecordId == null) {
      return null;
    }

    var sessionInternal = (DatabaseSessionInternal) session;
    EntityImpl result;
    result = sessionInternal.load(iRecordId);
    if (!result.getSchemaClassName().equals(SecurityUserImpl.CLASS_NAME)) {
      result = null;
    }
    return new SecurityUserImpl((DatabaseSessionInternal) session, result);
  }

  @Nullable
  public Role getRole(final DatabaseSession session, final Identifiable iRole) {
    var sessionInternal = (DatabaseSessionInternal) session;
    final EntityImpl entity = sessionInternal.load(iRole.getIdentity());
    SchemaImmutableClass clazz = null;
    clazz = entity.getImmutableSchemaClass(sessionInternal);
    if (clazz != null && clazz.isRole()) {
      return new Role(sessionInternal, entity);
    }

    return null;
  }

  @Nullable
  public Role getRole(final DatabaseSession session, final String iRoleName) {
    if (iRoleName == null) {
      return null;
    }

    return session.computeInTx(transaction -> {
      try (final var result =
          transaction.query("select from " + Role.CLASS_NAME + " where name = ? limit 1",
              iRoleName)) {
        if (result.hasNext()) {
          return new Role((DatabaseSessionInternal) session,
              (EntityImpl) result.next().asEntity());
        }
      }

      //noinspection ReturnOfNull
      return null;
    });
  }

  public List<EntityImpl> getAllUsers(final DatabaseSession session) {
    return session.computeInTx(transaction -> {
      try (var rs = transaction.query("select from OUser")) {
        return rs.stream().map((e) -> (EntityImpl) e.asEntity())
            .collect(Collectors.toList());
      }
    });
  }

  public List<EntityImpl> getAllRoles(final DatabaseSession session) {
    return session.computeInTx(transaction -> {
      try (var rs = transaction.query("select from " + Role.CLASS_NAME)) {
        return rs.stream().map((e) -> (EntityImpl) e.asEntity())
            .collect(Collectors.toList());
      }
    });
  }

  @Override
  public Map<String, ? extends SecurityPolicy> getSecurityPolicies(
      DatabaseSession session, SecurityRole role) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityPolicy getSecurityPolicy(
      DatabaseSession session, SecurityRole role, String resource) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setSecurityPolicy(
      DatabaseSessionInternal session, SecurityRole role, String resource,
      SecurityPolicyImpl policy) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityPolicyImpl createSecurityPolicy(DatabaseSession session, String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityPolicyImpl getSecurityPolicy(DatabaseSession session, String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void saveSecurityPolicy(DatabaseSession session, SecurityPolicyImpl policy) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void deleteSecurityPolicy(DatabaseSession session, String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeSecurityPolicy(DatabaseSession session, Role role, String resource) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean dropUser(final DatabaseSession session, final String iUserName) {
    return session.computeInTx(transaction -> {
      try (var result = transaction.execute("delete from OUser where name = ?", iUserName)) {
        final Number removed = result.next().getProperty("count");
        return removed != null && removed.intValue() > 0;
      }
    });
  }

  @Override
  public boolean dropRole(final DatabaseSession session, final String iRoleName) {
    return session.computeInTx(transaction -> {
      try (var resutl = transaction.execute(
          "delete from " + Role.CLASS_NAME + " where name = '" + iRoleName + "'")) {
        final Number removed = resutl.next().getProperty("count");
        return removed != null && removed.intValue() > 0;
      }
    });
  }

  @Override
  public long getVersion(DatabaseSession session) {
    return 0;
  }

  @Override
  public void incrementVersion(DatabaseSession session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityUserImpl create(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void load(DatabaseSessionInternal session) {
  }

  @Override
  public void close() {
  }

  @Override
  public Set<String> getFilteredProperties(DatabaseSessionInternal session,
      EntityImpl entity) {
    return Collections.emptySet();
  }

  @Override
  public boolean isAllowedWrite(DatabaseSessionInternal session, EntityImpl entity,
      String propertyName) {
    return true;
  }

  @Override
  public boolean canCreate(DatabaseSessionInternal session, DBRecord record) {
    return true;
  }

  @Override
  public boolean canRead(DatabaseSessionInternal session, DBRecord record) {
    return true;
  }

  @Override
  public boolean canUpdate(DatabaseSessionInternal session, DBRecord record) {
    return true;
  }

  @Override
  public boolean canDelete(DatabaseSessionInternal session, DBRecord record) {
    return true;
  }

  @Override
  public boolean canExecute(DatabaseSessionInternal session, Function function) {
    return true;
  }

  @Override
  public boolean isReadRestrictedBySecurityPolicy(DatabaseSession session, String resource) {
    return false;
  }

  @Override
  public Set<SecurityResourceProperty> getAllFilteredProperties(
      DatabaseSessionInternal database) {
    return Collections.emptySet();
  }

  @Override
  public SecurityUser securityAuthenticate(
      DatabaseSessionInternal session, String userName, String password) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityUser securityAuthenticate(
      DatabaseSessionInternal session, AuthenticationInfo authenticationInfo) {
    throw new UnsupportedOperationException();
  }
}
