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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SystemDatabase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.index.NullOutputListener;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.security.GlobalUser;
import com.jetbrains.youtrackdb.internal.core.security.SecuritySystem;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser.STATUSES;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Shared security class. It's shared by all the database instances that point to the same storage.
 */
public class SecurityShared implements SecurityInternal {

  private static final String DEFAULT_WRITER_ROLE_NAME = "writer";

  private static final String DEFAULT_READER_ROLE_NAME = "reader";

  private final AtomicLong version = new AtomicLong();

  public static final String IDENTITY_CLASSNAME = "OIdentity";

  /**
   * role name -> class name -> true: has some rules, ie. it's not all allowed
   */
  protected Map<String, Map<String, Boolean>> roleHasPredicateSecurityForClass;

  // used to avoid updating the above while the security schema is being created
  protected boolean skipRoleHasPredicateSecurityForClassUpdate = false;

  protected ConcurrentHashMap<String, Map<String, SQLBooleanExpression>> securityPredicateCache =
      new ConcurrentHashMap<>();

  /**
   * set of all the security resources defined on properties (used for optimizations)
   */
  protected Set<SecurityResourceProperty> filteredProperties;

  private final SecuritySystem security;

  /**
   * Uses the RestrictedOperation ENUM instead.
   */
  @Deprecated
  public static final String ALLOW_ALL_FIELD = RestrictedOperation.ALLOW_ALL.getFieldName();

  /**
   * Uses the RestrictedOperation ENUM instead.
   */
  @Deprecated
  public static final String ALLOW_READ_FIELD = RestrictedOperation.ALLOW_READ.getFieldName();

  /**
   * Uses the RestrictedOperation ENUM instead.
   */
  @Deprecated
  public static final String ALLOW_UPDATE_FIELD = RestrictedOperation.ALLOW_UPDATE.getFieldName();

  /**
   * Uses the RestrictedOperation ENUM instead.
   */
  @Deprecated
  public static final String ALLOW_DELETE_FIELD = RestrictedOperation.ALLOW_DELETE.getFieldName();

  public static final String ONCREATE_IDENTITY_TYPE = "onCreate.identityType";
  public static final String ONCREATE_FIELD = "onCreate.fields";

  public static final Set<String> ALLOW_FIELDS =
      Collections.unmodifiableSet(
          new HashSet<>() {
            {
              add(RestrictedOperation.ALLOW_ALL.getFieldName());
              add(RestrictedOperation.ALLOW_READ.getFieldName());
              add(RestrictedOperation.ALLOW_UPDATE.getFieldName());
              add(RestrictedOperation.ALLOW_DELETE.getFieldName());
            }
          });

  public SecurityShared(SecuritySystem security) {
    this.security = security;
  }

  @Override
  public boolean isAllowed(
      final DatabaseSessionEmbedded session,
      final Set<Identifiable> iAllowAll,
      final Set<Identifiable> iAllowOperation) {
    if ((iAllowAll == null || iAllowAll.isEmpty())
        && (iAllowOperation == null || iAllowOperation.isEmpty()))
    // NO AUTHORIZATION: CAN'T ACCESS
    {
      return false;
    }

    final var currentUser = session.getCurrentUser();
    if (currentUser != null) {
      // CHECK IF CURRENT USER IS ENLISTED
      if (iAllowAll == null || !iAllowAll.contains(currentUser.getIdentity())) {
        // CHECK AGAINST SPECIFIC _ALLOW OPERATION
        if (iAllowOperation != null && iAllowOperation.contains(currentUser.getIdentity())) {
          return true;
        }

        // CHECK IF AT LEAST ONE OF THE USER'S ROLES IS ENLISTED
        for (var r : currentUser.getRoles()) {
          // CHECK AGAINST GENERIC _ALLOW
          if (iAllowAll != null && iAllowAll.contains(r.getIdentity())) {
            return true;
          }
          // CHECK AGAINST SPECIFIC _ALLOW OPERATION
          if (iAllowOperation != null && iAllowOperation.contains(r.getIdentity())) {
            return true;
          }
          // CHECK inherited permissions from parent roles, fixes #1980: Record Level Security:
          // permissions don't follow role's
          // inheritance
          var parentRole = r.getParentRole();
          while (parentRole != null) {
            if (iAllowAll != null && iAllowAll.contains(parentRole.getIdentity())) {
              return true;
            }
            if (iAllowOperation != null && iAllowOperation.contains(
                parentRole.getIdentity())) {
              return true;
            }
            parentRole = parentRole.getParentRole();
          }
        }
        return false;
      }
    }
    return true;
  }

  @Override
  public SecurityUser securityAuthenticate(
      DatabaseSessionEmbedded session, AuthenticationInfo authenticationInfo) {
    SecurityUser user = null;
    final var dbName = session.getDatabaseName();
    user = security.authenticate(session, authenticationInfo);

    if (user != null) {
      if (user.getAccountStatus(session) != SecurityUser.STATUSES.ACTIVE) {
        throw new SecurityAccessException(dbName,
            "User '" + user.getName(session) + "' is not active");
      }
    } else {
      // WAIT A BIT TO AVOID BRUTE FORCE
      try {
        Thread.sleep(200);
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }

      throw new SecurityAccessException(
          dbName, "Invalid authentication info for access to the database " + authenticationInfo);
    }
    return user;
  }

  @Override
  public SecurityUser securityAuthenticate(
      DatabaseSessionEmbedded session, String userName, String password) {
    SecurityUser user;
    final var dbName = session.getDatabaseName();
    user = security.authenticate(session, userName, password);

    if (user != null) {
      if (user.getAccountStatus(session) != SecurityUser.STATUSES.ACTIVE) {
        throw new SecurityAccessException(dbName,
            "User '" + user.getName(session) + "' is not active");
      }
    } else {
      // WAIT A BIT TO AVOID BRUTE FORCE
      try {
        Thread.sleep(200);
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }

      throw new SecurityAccessException(
          dbName,
          "User or password not valid for username: " + userName + ", database: '" + dbName + "'");
    }
    return user;
  }

  @Nullable
  @Override
  public SecurityUserImpl authenticate(
      DatabaseSessionEmbedded session, final String iUsername, final String iUserPassword) {
    return null;
  }

  // Token MUST be validated before being passed to this method.
  @Override
  public SecurityUser authenticate(final DatabaseSessionEmbedded session,
      final Token authToken) {
    final var dbName = session.getDatabaseName();
    if (!authToken.getIsValid()) {
      throw new SecurityAccessException(dbName, "Token not valid");
    }

    var user = authToken.getUser(session);
    if (user == null && authToken.getUserName() != null) {
      // Token handler may not support returning an OUser so let's get username (subject) and query:
      user = getUser(session, authToken.getUserName());
    }

    if (user == null) {
      throw new SecurityAccessException(
          dbName, "Authentication failed, could not load user from token");
    }
    if (user.getAccountStatus(session) != STATUSES.ACTIVE) {
      throw new SecurityAccessException(dbName,
          "User '" + user.getName(session) + "' is not active");
    }

    return user;
  }

  @Override
  @Nullable
  public SecurityUserImpl getUser(final DatabaseSessionEmbedded session, final RID iRecordId) {
    if (iRecordId == null) {
      return null;
    }

    EntityImpl result;
    result = session.load(iRecordId);
    if (!result.getSchemaClassName().equals(SecurityUserImpl.CLASS_NAME)) {
      result = null;
    }
    return new SecurityUserImpl(session, result);
  }

  @Override
  public SecurityUserImpl createUser(
      final DatabaseSessionEmbedded session,
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
      final DatabaseSessionEmbedded session,
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
  public boolean dropUser(final DatabaseSessionEmbedded session, final String iUserName) {
    final Number removed;
    try (var res = session.getActiveTransaction().
        execute("delete from OUser where name = ?", iUserName)) {
      removed = res.next().getProperty("count");
    }

    return removed != null && removed.intValue() > 0;
  }

  @Override
  @Nullable
  public Role getRole(final DatabaseSessionEmbedded session, final Identifiable iRole) {
    try {
      var transaction = session.getActiveTransaction();
      final EntityImpl entity = transaction.load(iRole);
      SchemaImmutableClass clazz = null;
      clazz = entity.getImmutableSchemaClass(session);

      if (clazz != null && clazz.isRole()) {
        return new Role(session, entity);
      }
    } catch (RecordNotFoundException rnf) {
      return null;
    }

    return null;
  }

  @Override
  @Nullable
  public Role getRole(final DatabaseSessionEmbedded session, final String iRoleName) {
    if (iRoleName == null) {
      return null;
    }

    return session.computeInTx(transaction -> {
      try (final var result =
          transaction.query("select from " + Role.CLASS_NAME + " where name = ? limit 1",
              iRoleName)) {
        if (result.hasNext()) {
          return new Role(session,
              (EntityImpl) result.next().asEntity());
        }
      }

      //noinspection ReturnOfNull
      return null;
    });
  }

  @Nullable
  public static RID getRoleRID(final DatabaseSessionEmbedded session, final String iRoleName) {
    if (iRoleName == null) {
      return null;
    }

    return session.computeInTx(transaction -> {
      try (final var result =
          transaction.query(
              "select @rid as rid from " + Role.CLASS_NAME + " where name = ? limit 1",
              iRoleName)) {

        if (result.hasNext()) {
          return result.next().getProperty("rid");
        }
      }
      //noinspection ReturnOfNull
      return null;
    });
  }

  @Override
  public Role createRole(
      final DatabaseSessionEmbedded session, final String iRoleName) {
    return createRole(session, iRoleName, null);
  }

  @Override
  public Role createRole(
      final DatabaseSessionEmbedded session,
      final String iRoleName,
      final Role iParent) {
    final var role = new Role(session, iRoleName, iParent);
    role.save(session);
    return role;
  }

  @Override
  public boolean dropRole(final DatabaseSessionEmbedded session, final String iRoleName) {
    return session.computeInTx(transaction -> {
      final Number removed;
      try (var result =
          transaction.execute(
              "delete from " + Role.CLASS_NAME + " where name = '" + iRoleName + "'")) {
        removed = result.next().getProperty("count");
      }

      return removed != null && removed.intValue() > 0;
    });
  }

  @Override
  public List<EntityImpl> getAllUsers(final DatabaseSessionEmbedded session) {
    return session.computeInTx(transaction -> {
      try (var rs = transaction.query("select from OUser")) {
        return rs.stream().map((e) -> (EntityImpl) e.asEntity())
            .collect(Collectors.toList());
      }
    });
  }

  @Override
  public List<EntityImpl> getAllRoles(final DatabaseSessionEmbedded session) {
    return session.computeInTx(transaction -> {
      try (var rs = transaction.query("select from " + Role.CLASS_NAME)) {
        return rs.stream().map((e) -> (EntityImpl) e.asEntity())
            .collect(Collectors.toList());
      }
    });
  }

  @Override
  public Map<String, ? extends SecurityPolicy> getSecurityPolicies(
      DatabaseSessionEmbedded session, SecurityRole role) {
    var result = role.getPolicies(session);
    return result != null ? result : Collections.emptyMap();
  }

  @Override
  public SecurityPolicy getSecurityPolicy(
      DatabaseSessionEmbedded session, SecurityRole role, String resource) {
    resource = normalizeSecurityResource(session, resource);
    return role.getPolicy(session, resource);
  }

  public void setSecurityPolicyWithBitmask(
      DatabaseSessionEmbedded session, SecurityRole role, String resource, int legacyPolicy) {
    var policyName = "default_" + legacyPolicy;
    var policy = getSecurityPolicy(session, policyName);
    if (policy == null) {
      policy = createSecurityPolicy(session, policyName);
      policy.setCreateRule(
          (legacyPolicy & Role.PERMISSION_CREATE) > 0 ? "true" : "false");
      policy.setReadRule((legacyPolicy & Role.PERMISSION_READ) > 0 ? "true" : "false");
      policy.setBeforeUpdateRule(
          (legacyPolicy & Role.PERMISSION_UPDATE) > 0 ? "true" : "false");
      policy.setAfterUpdateRule(
          (legacyPolicy & Role.PERMISSION_UPDATE) > 0 ? "true" : "false");
      policy.setDeleteRule(
          (legacyPolicy & Role.PERMISSION_DELETE) > 0 ? "true" : "false");
      policy.setExecuteRule(
          (legacyPolicy & Role.PERMISSION_EXECUTE) > 0 ? "true" : "false");
      saveSecurityPolicy(session, policy);
    }
    setSecurityPolicy(session, role, resource, policy);
  }

  @Override
  public void setSecurityPolicy(
      DatabaseSessionEmbedded session, SecurityRole securityRole, String resource,
      SecurityPolicyImpl policy) {
    if (securityRole instanceof Role role) {
      var currentResource = normalizeSecurityResource(session, resource);

      validatePolicyWithIndexes(session, currentResource);
      role.getPolicies(session).put(currentResource, policy);
      role.save(session);

      if (session.getCurrentUser() != null && session.getCurrentUser()
          .hasRole(session, role.getName(session), true)) {
        session.reloadUser();
      }

      updateAllFilteredProperties(session);
      initPredicateSecurityOptimizations(session);
    } else {
      setSecurityPolicy(session, getRole(session, securityRole.getIdentity()), resource, policy);
    }
  }

  private static void validatePolicyWithIndexes(DatabaseSessionEmbedded session, String resource)
      throws IllegalArgumentException {
    var res = SecurityResource.getInstance(resource);
    if (res instanceof SecurityResourceProperty) {
      var clazzName = ((SecurityResourceProperty) res).getClassName();
      var clazz =
          session
              .getMetadata()
              .getImmutableSchemaSnapshot()
              .getClass(clazzName);
      if (clazz == null) {
        return;
      }
      Set<SchemaClass> allClasses = new HashSet<>();
      allClasses.add(clazz);
      allClasses.addAll(clazz.getAllSubclasses());
      allClasses.addAll(clazz.getAllSuperClasses());
      for (var c : allClasses) {
        for (var index : ((SchemaClassInternal) c).getIndexesInternal()) {
          var indexFields = index.getDefinition().getProperties();
          if (indexFields.size() > 1
              && indexFields.contains(((SecurityResourceProperty) res).getPropertyName())) {
            throw new IllegalArgumentException(
                "Cannot bind security policy on "
                    + resource
                    + " because of existing composite indexes: "
                    + index.getName());
          }
        }
      }
    }
  }

  @Override
  public SecurityPolicyImpl createSecurityPolicy(DatabaseSessionEmbedded session, String name) {
    var elem = session.newEntity(SecurityPolicy.CLASS_NAME);
    elem.setProperty("name", name);
    var policy = new SecurityPolicyImpl((EntityImpl) elem);
    saveSecurityPolicy(session, policy);
    return policy;
  }

  @Override
  public SecurityPolicyImpl getSecurityPolicy(DatabaseSessionEmbedded session, String name) {
    var currentTx = session.getActiveTransactionOrNull();
    if (currentTx != null) {
      return doGetSecurityPolicy(name, currentTx);
    }

    return session.computeInTx(transaction -> doGetSecurityPolicy(name, transaction));
  }

  @Nullable
  private static SecurityPolicyImpl doGetSecurityPolicy(String name, Transaction transaction) {
    try (var rs =
        transaction.query(
            "SELECT FROM " + SecurityPolicy.CLASS_NAME + " WHERE name = ?", name)) {
      if (rs.hasNext()) {
        var result = rs.next();
        return new SecurityPolicyImpl((EntityImpl) result.asEntity());
      }
    }
    return null;
  }

  @Override
  public void saveSecurityPolicy(DatabaseSessionEmbedded session, SecurityPolicyImpl policy) {
    policy.save(session);
  }

  @Override
  public void deleteSecurityPolicy(DatabaseSessionEmbedded session, String name) {
    session.executeInTx(transaction -> {
      transaction
          .execute("DELETE FROM " + SecurityPolicy.CLASS_NAME + " WHERE name = ?", name)
          .close();
    });
  }

  @Override
  public void removeSecurityPolicy(DatabaseSessionEmbedded session, Role role, String resource) {
    var calculatedResource = normalizeSecurityResource(session, resource);
    role.getPolicies(session).remove(calculatedResource);
    role.save(session);

    updateAllFilteredProperties(session);
    initPredicateSecurityOptimizations(session);
  }

  private static String normalizeSecurityResource(DatabaseSessionEmbedded session,
      String resource) {
    return resource; // TODO
  }

  @Override
  @Nullable
  public SecurityUserImpl create(final DatabaseSessionEmbedded session) {
    if (!session.getMetadata().getSchema().getClasses().isEmpty()) {
      return null;
    }

    skipRoleHasPredicateSecurityForClassUpdate = true;
    SecurityUserImpl adminUser = null;
    try {
      var identityClass =
          session.getMetadata().getSchema().getClass(Identity.CLASS_NAME); // SINCE 1.2.0
      if (identityClass == null) {
        identityClass = session.getMetadata().getSchema().createAbstractClass(Identity.CLASS_NAME);
      }

      createOrUpdateOSecurityPolicyClass(session);

      var roleClass = createOrUpdateORoleClass(session, identityClass);

      createOrUpdateOUserClass(session, identityClass, roleClass);

      if (!SystemDatabase.SYSTEM_DB_NAME.equals(session.getDatabaseName())) {
        // CREATE ROLES AND USERS
        createDefaultRoles(session);
        adminUser = createDefaultUsers(session);
      }

    } finally {
      skipRoleHasPredicateSecurityForClassUpdate = false;
    }
    initPredicateSecurityOptimizations(session);

    return adminUser;
  }

  private void createDefaultRoles(final DatabaseSessionEmbedded session) {
    session.executeInTx(
        transaction -> {
          createDefaultAdminRole(session);
          createDefaultReaderRole(session);
          createDefaultWriterRole(session);
        });
  }

  private SecurityUserImpl createDefaultUsers(final DatabaseSessionEmbedded session) {
    var createDefUsers =
        session.getConfiguration().getValueAsBoolean(GlobalConfiguration.CREATE_DEFAULT_USERS);

    SecurityUserImpl adminUser = null;
    // This will return the global value if a local storage context configuration value does not
    // exist.
    if (createDefUsers) {
      session.computeInTx(
          transaction -> {
            var admin = createUser(session, SecurityUserImpl.ADMIN, SecurityUserImpl.ADMIN,
                Role.ADMIN);
            createUser(session, "reader", "reader", DEFAULT_READER_ROLE_NAME);
            createUser(session, "writer", "writer", DEFAULT_WRITER_ROLE_NAME);
            return admin;
          });
    }

    return adminUser;
  }

  private void createDefaultWriterRole(final DatabaseSessionEmbedded session) {
    final var writerRole =
        createRole(session, DEFAULT_WRITER_ROLE_NAME);
    sedDefaultWriterPermissions(session, writerRole);
  }

  private void sedDefaultWriterPermissions(final DatabaseSessionEmbedded session,
      final Role writerRole) {
    setSecurityPolicyWithBitmask(session, writerRole, "database.class.*.*", Role.PERMISSION_ALL);

    writerRole.addRule(session, ResourceGeneric.DATABASE, null, Role.PERMISSION_READ);
    writerRole.addRule(session,
        ResourceGeneric.SCHEMA,
        null, Role.PERMISSION_READ + Role.PERMISSION_CREATE + Role.PERMISSION_UPDATE);
    writerRole.addRule(session,
        ResourceGeneric.COLLECTION,
        MetadataDefault.COLLECTION_INTERNAL_NAME, Role.PERMISSION_READ);
    writerRole.addRule(session, ResourceGeneric.CLASS, null, Role.PERMISSION_ALL);
    writerRole.addRule(session, ResourceGeneric.CLASS, "OUser", Role.PERMISSION_READ);
    writerRole.addRule(session, ResourceGeneric.COLLECTION, null, Role.PERMISSION_ALL);
    writerRole.addRule(session, ResourceGeneric.COMMAND, null, Role.PERMISSION_ALL);
    writerRole.addRule(session, ResourceGeneric.RECORD_HOOK, null, Role.PERMISSION_ALL);
    writerRole.addRule(session, ResourceGeneric.FUNCTION, null, Role.PERMISSION_READ);
    writerRole.addRule(session, ResourceGeneric.CLASS, DBSequence.CLASS_NAME,
        Role.PERMISSION_READ);
    writerRole.addRule(session, ResourceGeneric.CLASS, "OTriggered", Role.PERMISSION_READ);
    writerRole.addRule(session, ResourceGeneric.CLASS, "OSchedule", Role.PERMISSION_READ);
    writerRole.addRule(session,
        ResourceGeneric.CLASS,
        SecurityResource.class.getSimpleName(), Role.PERMISSION_READ);
    writerRole.addRule(session, ResourceGeneric.SYSTEM_COLLECTIONS, null, Role.PERMISSION_NONE);
    writerRole.save(session);

    setSecurityPolicyWithBitmask(
        session, writerRole, Rule.ResourceGeneric.DATABASE.getLegacyName(), Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.SCHEMA.getLegacyName(),
        Role.PERMISSION_READ + Role.PERMISSION_CREATE + Role.PERMISSION_UPDATE);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.COLLECTION.getLegacyName()
            + "."
            + MetadataDefault.COLLECTION_INTERNAL_NAME,
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.COLLECTION.getLegacyName() + "." + Role.CLASS_NAME.toLowerCase(
            Locale.ROOT),
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.COLLECTION.getLegacyName() + ".ouser",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.CLASS.getLegacyName() + ".*",
        Role.PERMISSION_ALL);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.CLASS.getLegacyName() + ".OUser",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.COLLECTION.getLegacyName() + ".*",
        Role.PERMISSION_ALL);
    setSecurityPolicyWithBitmask(
        session, writerRole, Rule.ResourceGeneric.COMMAND.getLegacyName(), Role.PERMISSION_ALL);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.RECORD_HOOK.getLegacyName(),
        Role.PERMISSION_ALL);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.FUNCTION.getLegacyName() + ".*",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.CLASS.getLegacyName() + "." + DBSequence.CLASS_NAME,
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.SYSTEM_COLLECTIONS.getLegacyName() + ".OTriggered",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.SYSTEM_COLLECTIONS.getLegacyName() + ".OSchedule",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        writerRole,
        Rule.ResourceGeneric.SYSTEM_COLLECTIONS.getLegacyName(),
        Role.PERMISSION_NONE);
  }

  private void createDefaultReaderRole(final DatabaseSessionEmbedded session) {
    final var readerRole =
        createRole(session, DEFAULT_READER_ROLE_NAME);
    setDefaultReaderPermissions(session, readerRole);
  }

  private void setDefaultReaderPermissions(final DatabaseSessionEmbedded session,
      final Role readerRole) {
    setSecurityPolicyWithBitmask(session, readerRole, "database.class.*.*", Role.PERMISSION_ALL);

    readerRole.addRule(session, ResourceGeneric.DATABASE, null, Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.SCHEMA, null, Role.PERMISSION_READ);
    readerRole.addRule(session,
        ResourceGeneric.COLLECTION,
        MetadataDefault.COLLECTION_INTERNAL_NAME, Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.COLLECTION,
        Role.CLASS_NAME.toLowerCase(Locale.ROOT),
        Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.COLLECTION, "ouser", Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.CLASS, null, Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.CLASS, "OUser", Role.PERMISSION_NONE);
    readerRole.addRule(session, ResourceGeneric.COLLECTION, null, Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.COMMAND, null, Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.RECORD_HOOK, null, Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.FUNCTION, null, Role.PERMISSION_READ);
    readerRole.addRule(session, ResourceGeneric.SYSTEM_COLLECTIONS, null, Role.PERMISSION_NONE);

    readerRole.save(session);

    setSecurityPolicyWithBitmask(
        session, readerRole, Rule.ResourceGeneric.DATABASE.getLegacyName(), Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session, readerRole, Rule.ResourceGeneric.SCHEMA.getLegacyName(), Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.COLLECTION.getLegacyName()
            + "."
            + MetadataDefault.COLLECTION_INTERNAL_NAME,
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.COLLECTION.getLegacyName() + "." + Role.CLASS_NAME.toLowerCase(
            Locale.ROOT),
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.COLLECTION.getLegacyName() + ".ouser",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.CLASS.getLegacyName() + ".*",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.CLASS.getLegacyName() + ".OUser",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.COLLECTION.getLegacyName() + ".*",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session, readerRole, Rule.ResourceGeneric.COMMAND.getLegacyName(), Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.RECORD_HOOK.getLegacyName(),
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.FUNCTION.getLegacyName() + ".*",
        Role.PERMISSION_READ);
    setSecurityPolicyWithBitmask(
        session,
        readerRole,
        Rule.ResourceGeneric.SYSTEM_COLLECTIONS.getLegacyName(),
        Role.PERMISSION_NONE);
  }

  private void createDefaultAdminRole(final DatabaseSessionEmbedded session) {
    Role adminRole;
    adminRole = createRole(session, Role.ADMIN);
    setDefaultAdminPermissions(session, adminRole);
  }

  private void setDefaultAdminPermissions(final DatabaseSessionEmbedded session,
      Role adminRole) {
    setSecurityPolicyWithBitmask(session, adminRole, "*", Role.PERMISSION_ALL);
    adminRole.addRule(session, ResourceGeneric.BYPASS_RESTRICTED, null, Role.PERMISSION_ALL)
        .save(session);
    adminRole.addRule(session, ResourceGeneric.ALL, null, Role.PERMISSION_ALL).save(session);
    adminRole.addRule(session, ResourceGeneric.CLASS, null, Role.PERMISSION_ALL).save(session);
    adminRole.addRule(session, ResourceGeneric.COLLECTION, null, Role.PERMISSION_ALL).save(session);
    adminRole.addRule(session, ResourceGeneric.SYSTEM_COLLECTIONS, null, Role.PERMISSION_ALL)
        .save(session);
    adminRole.addRule(session, ResourceGeneric.DATABASE, null, Role.PERMISSION_ALL).save(session);
    adminRole.addRule(session, ResourceGeneric.SCHEMA, null, Role.PERMISSION_ALL).save(session);
    adminRole.addRule(session, ResourceGeneric.COMMAND, null, Role.PERMISSION_ALL).save(session);
    adminRole.addRule(session, ResourceGeneric.COMMAND_GREMLIN, null, Role.PERMISSION_ALL)
        .save(session);
    adminRole.addRule(session, ResourceGeneric.FUNCTION, null, Role.PERMISSION_ALL).save(session);

    adminRole.save(session);
  }

  private static void createOrUpdateOUserClass(
      final DatabaseSessionEmbedded database, SchemaClass identityClass, SchemaClass roleClass) {
    var unsafe = false;
    var userClass = database.getMetadata().getSchemaInternal()
        .getClassInternal("OUser");
    if (userClass == null) {
      userClass = (SchemaClassInternal) database.getMetadata().getSchema()
          .createClass("OUser", identityClass);
      unsafe = true;
    } else if (!userClass.getSuperClasses().contains(identityClass))
    // MIGRATE AUTOMATICALLY TO 1.2.0
    {
      userClass.setSuperClasses(Collections.singletonList(identityClass));
    }

    if (!userClass.existsProperty("name")) {
      userClass
          .createProperty("name", PropertyTypeInternal.STRING, (PropertyTypeInternal) null, unsafe)
          .setMandatory(true)
          .setNotNull(true)
          .setCollate("ci")
          .setMin("1")
          .setRegexp("\\S+(.*\\S+)*");
      userClass.createIndex("OUser.name", INDEX_TYPE.UNIQUE, NullOutputListener.INSTANCE,
          "name");
    } else {
      var name = userClass.getPropertyInternal("name");
      if (name.getAllIndexes().isEmpty()) {
        userClass.createIndex(
            "OUser.name", INDEX_TYPE.UNIQUE, NullOutputListener.INSTANCE, "name");
      }
    }
    if (!userClass.existsProperty(SecurityUserImpl.PASSWORD_PROPERTY)) {
      userClass
          .createProperty(SecurityUserImpl.PASSWORD_PROPERTY, PropertyTypeInternal.STRING,
              (PropertyTypeInternal) null, unsafe)
          .setMandatory(true)
          .setNotNull(true);
    }
    if (!userClass.existsProperty("roles")) {
      userClass.createProperty("roles", PropertyTypeInternal.LINKSET, roleClass, unsafe);
    }
    if (!userClass.existsProperty("status")) {
      userClass
          .createProperty("status", PropertyTypeInternal.STRING, (PropertyTypeInternal) null,
              unsafe)
          .setMandatory(true)
          .setNotNull(true);
    }
  }

  private static void createOrUpdateOSecurityPolicyClass(
      final DatabaseSessionEmbedded database) {
    var policyClass = database.getMetadata().getSchemaInternal()
        .getClassInternal("OSecurityPolicy");
    var unsafe = false;
    if (policyClass == null) {
      policyClass = (SchemaClassInternal) database.getMetadata().getSchema()
          .createClass("OSecurityPolicy");
      unsafe = true;
    }

    if (!policyClass.existsProperty("name")) {
      policyClass
          .createProperty("name", PropertyTypeInternal.STRING, (PropertyTypeInternal) null, unsafe)
          .setMandatory(true)
          .setNotNull(true)
          .setCollate("ci");
      policyClass.createIndex(
          "OSecurityPolicy.name", INDEX_TYPE.UNIQUE, NullOutputListener.INSTANCE, "name");
    } else {
      var name = policyClass.getPropertyInternal("name");
      if (name.getAllIndexes().isEmpty()) {
        policyClass.createIndex(
            "OSecurityPolicy.name", INDEX_TYPE.UNIQUE, NullOutputListener.INSTANCE, "name");
      }
    }

    if (!policyClass.existsProperty("create")) {
      policyClass.createProperty("create", PropertyTypeInternal.STRING, (PropertyTypeInternal) null,
          unsafe);
    }
    if (!policyClass.existsProperty("read")) {
      policyClass.createProperty("read", PropertyTypeInternal.STRING, (PropertyTypeInternal) null,
          unsafe);
    }
    if (!policyClass.existsProperty("beforeUpdate")) {
      policyClass.createProperty("beforeUpdate", PropertyTypeInternal.STRING,
          (PropertyTypeInternal) null,
          unsafe);
    }
    if (!policyClass.existsProperty("afterUpdate")) {
      policyClass.createProperty("afterUpdate", PropertyTypeInternal.STRING,
          (PropertyTypeInternal) null,
          unsafe);
    }
    if (!policyClass.existsProperty("delete")) {
      policyClass.createProperty("delete", PropertyTypeInternal.STRING, (PropertyTypeInternal) null,
          unsafe);
    }
    if (!policyClass.existsProperty("execute")) {
      policyClass.createProperty("execute", PropertyTypeInternal.STRING,
          (PropertyTypeInternal) null,
          unsafe);
    }

    if (!policyClass.existsProperty("active")) {
      policyClass.createProperty("active", PropertyTypeInternal.BOOLEAN,
          (PropertyTypeInternal) null,
          unsafe);
    }

  }

  private static SchemaClass createOrUpdateORoleClass(final DatabaseSessionEmbedded database,
      SchemaClass identityClass) {
    var roleClass = database.getMetadata().getSchemaInternal()
        .getClassInternal(Role.CLASS_NAME);
    var unsafe = false;
    if (roleClass == null) {
      roleClass = (SchemaClassInternal) database.getMetadata().getSchema()
          .createClass(Role.CLASS_NAME, identityClass);
      unsafe = true;
    } else if (!roleClass.getSuperClasses().contains(identityClass))
    // MIGRATE AUTOMATICALLY TO 1.2.0
    {
      roleClass.setSuperClasses(Collections.singletonList(identityClass));
    }

    if (!roleClass.existsProperty("name")) {
      roleClass
          .createProperty("name", PropertyTypeInternal.STRING, (PropertyTypeInternal) null, unsafe)
          .setMandatory(true)
          .setNotNull(true)
          .setCollate("ci");
      roleClass.createIndex(Role.CLASS_NAME + "." + Role.NAME, INDEX_TYPE.UNIQUE,
          NullOutputListener.INSTANCE,
          "name");
    } else {
      var name = roleClass.getPropertyInternal("name");
      if (name.getAllIndexes().isEmpty()) {
        roleClass.createIndex(
            "ORole.name", INDEX_TYPE.UNIQUE, NullOutputListener.INSTANCE, "name");
      }
    }

    if (!roleClass.existsProperty("mode")) {
      roleClass.createProperty("mode", PropertyTypeInternal.BYTE, (PropertyTypeInternal) null,
          unsafe);
    }

    if (!roleClass.existsProperty("rules")) {
      roleClass.createProperty("rules", PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.BYTE,
          unsafe);
    }
    if (!roleClass.existsProperty("inheritedRole")) {
      roleClass.createProperty("inheritedRole", PropertyTypeInternal.LINK, roleClass, unsafe);
    }

    if (!roleClass.existsProperty("policies")) {
      roleClass.createProperty(
          "policies", PropertyTypeInternal.LINKMAP, database.getClass("OSecurityPolicy"), unsafe);
    }

    return roleClass;
  }

  @Override
  public void load(DatabaseSessionEmbedded session) {
    final var userClass = session.getMetadata().getSchema()
        .getClassInternal("OUser");
    if (userClass != null) {
      // @COMPATIBILITY <1.3.0
      if (!userClass.existsProperty("status")) {
        userClass.createProperty("status", PropertyType.STRING).setMandatory(true)
            .setNotNull(true);
      }
      var p = userClass.getProperty("name");
      if (p == null) {
        p =
            userClass
                .createProperty("name", PropertyType.STRING)
                .setMandatory(true)
                .setNotNull(true)
                .setMin("1")
                .setRegexp("\\S+(.*\\S+)*");
      }

      if (userClass.getInvolvedIndexes(session, "name") == null) {
        p.createIndex(INDEX_TYPE.UNIQUE);
      }

      // ROLE
      Role.generateSchema(session);

      // TODO migrate Role to use security policies
    }

    setupPredicateSecurity(session);
    initPredicateSecurityOptimizations(session);
  }

  private void setupPredicateSecurity(DatabaseSessionEmbedded session) {
    var securityPolicyClass = session.getMetadata().getSchema()
        .getClass(SecurityPolicy.CLASS_NAME);
    if (securityPolicyClass == null) {
      createOrUpdateOSecurityPolicyClass(session);
      session.executeInTx(transaction -> {
        var adminRole = getRole(session, "admin");
        if (adminRole != null) {
          setDefaultAdminPermissions(session, adminRole);
        }
        var readerRole = getRole(session, DEFAULT_READER_ROLE_NAME);
        if (readerRole != null) {
          setDefaultReaderPermissions(session, readerRole);
        }

        var writerRole = getRole(session, DEFAULT_WRITER_ROLE_NAME);
        if (writerRole != null) {
          sedDefaultWriterPermissions(session, writerRole);
        }

        incrementVersion(session);
      });
    }
  }

  public static SecurityUser getUserInternal(final DatabaseSessionEmbedded session,
      final String iUserName) {
    return session.computeInTx(transaction -> {
      try (var result =
          transaction.query("select from OUser where name = ? limit 1", iUserName)) {
        if (result.hasNext()) {
          return new SecurityUserImpl(session,
              (EntityImpl) result.next().asEntity());
        }
      }

      //noinspection ReturnOfNull
      return null;
    });
  }

  @Override
  public SecurityUser getUser(DatabaseSessionEmbedded session, String username) {
    var user = getUserInternal(session, username);
    if (user != null) {
      return user;
    }

    return security.getUser(username, session);
  }

  public static SecurityRole createRole(GlobalUser serverUser) {

    final SecurityRole role;
    if (serverUser.getResources().equalsIgnoreCase("*")) {
      role = createRoot(serverUser);
    } else {
      var permissions = mapPermission(serverUser);
      role = new ImmutableRole(null, serverUser.getName(), permissions, null);
    }

    return role;
  }

  private static SecurityRole createRoot(GlobalUser serverUser) {
    var policies = createrRootSecurityPolicy("*");
    Map<Rule.ResourceGeneric, Rule> rules = new HashMap<Rule.ResourceGeneric, Rule>();
    for (var resource : Rule.ResourceGeneric.values()) {
      var rule = new Rule(resource, null, null);
      rule.grantAccess(null, Role.PERMISSION_ALL);
      rules.put(resource, rule);
    }

    return new ImmutableRole(null, serverUser.getName(), rules, policies);
  }

  private static Map<ResourceGeneric, Rule> mapPermission(GlobalUser user) {
    Map<Rule.ResourceGeneric, Rule> rules = new HashMap<Rule.ResourceGeneric, Rule>();
    var strings = user.getResources().split(",");

    for (var string : strings) {
      var generic = Rule.mapLegacyResourceToGenericResource(string);
      if (generic != null) {
        var rule = new Rule(generic, null, null);
        rule.grantAccess(null, Role.PERMISSION_ALL);
        rules.put(generic, rule);
      }
    }
    return rules;
  }

  public static Map<String, SecurityPolicy> createrRootSecurityPolicy(String resource) {
    Map<String, SecurityPolicy> policies = new HashMap<>();
    policies.put(
        resource,
        new ImmutableSecurityPolicy(resource, "true", "true", "true", "true", "true", "true"));
    return policies;
  }

  public static RID getUserRID(final DatabaseSessionEmbedded session, final String userName) {
    return session.computeInTx(transaction -> {
      try (var result =
          transaction.query("select @rid as rid from OUser where name = ? limit 1", userName)) {

        if (result.hasNext()) {
          return result.next().getProperty("rid");
        }
      }

      //noinspection ReturnOfNull
      return null;
    });
  }

  @Override
  public void close() {
  }

  @Override
  public long getVersion(final DatabaseSessionEmbedded session) {
    return version.get();
  }

  @Override
  public void incrementVersion(final DatabaseSessionEmbedded session) {
    version.incrementAndGet();
    securityPredicateCache.clear();
    updateAllFilteredProperties(session);
    initPredicateSecurityOptimizations(session);
  }

  protected void initPredicateSecurityOptimizations(DatabaseSessionEmbedded session) {
    if (skipRoleHasPredicateSecurityForClassUpdate) {
      return;
    }
    var user = session.getCurrentUser();
    try {
      if (user != null) {
        session.setUser(null);
      }

      initPredicateSecurityOptimizationsInternal(session);
    } finally {

      if (user != null) {
        session.setUser(user);
      }
    }
  }

  private void initPredicateSecurityOptimizationsInternal(DatabaseSessionEmbedded session) {
    Map<String, Map<String, Boolean>> result = new HashMap<>();
    var allClasses = session.getMetadata().getSchema().getClasses();

    if (!session
        .getMetadata()
        .getImmutableSchemaSnapshot()
        .existsClass(Role.CLASS_NAME)) {
      return;
    }
    if (session.isTxActive()) {
      doInitPredicateOptimization(session, allClasses, result);
    } else {
      session.executeInTx(
          transaction -> {
            doInitPredicateOptimization(session, allClasses, result);
          });
    }
  }

  private void doInitPredicateOptimization(DatabaseSessionEmbedded session,
      Collection<SchemaClass> allClasses,
      Map<String, Map<String, Boolean>> result) {
    synchronized (this) {
      try (var rs = session.query("select name, policies from " + Role.CLASS_NAME)) {
        while (rs.hasNext()) {
          var item = rs.next();
          String roleName = item.getProperty("name");

          Map<String, Identifiable> policies = item.getProperty("policies");
          if (policies != null) {
            for (var policyEntry : policies.entrySet()) {
              var res = SecurityResource.getInstance(policyEntry.getKey());
              try {
                var transaction1 = session.getActiveTransaction();
                Entity policy = transaction1.load(policyEntry.getValue());

                for (var clazz : allClasses) {
                  if (isClassInvolved(session, clazz, res)
                      && !isAllAllowed(
                      session,
                      new ImmutableSecurityPolicy(
                          new SecurityPolicyImpl((EntityImpl) policy)))) {
                    var roleMap =
                        result.computeIfAbsent(roleName, k -> new HashMap<>());
                    roleMap.put(clazz.getName(), true);
                  }
                }
              } catch (RecordNotFoundException rne) {
                // ignore
              }
            }
          }
        }
      }
      this.roleHasPredicateSecurityForClass = result;
    }
  }

  private static boolean isAllAllowed(DatabaseSessionEmbedded db, SecurityPolicy policy) {
    for (var scope : SecurityPolicy.Scope.values()) {
      var predicateString = policy.get(scope, db);
      if (predicateString == null) {
        continue;
      }
      var predicate = SecurityEngine.parsePredicate(predicateString);
      if (!predicate.isConstantExpression()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isClassInvolved(DatabaseSessionEmbedded db, SchemaClass clazz,
      SecurityResource res) {
    if (res instanceof SecurityResourceAll
        || res.equals(SecurityResourceClass.ALL_CLASSES)
        || res.equals(SecurityResourceProperty.ALL_PROPERTIES)) {
      return true;
    }
    if (res instanceof SecurityResourceClass) {
      var resourceClass = ((SecurityResourceClass) res).getClassName();
      return clazz.isSubClassOf(resourceClass);
    } else if (res instanceof SecurityResourceProperty) {
      var resourceClass = ((SecurityResourceProperty) res).getClassName();
      return clazz.isSubClassOf(resourceClass);
    }
    return false;
  }

  @Override
  public Set<String> getFilteredProperties(DatabaseSessionEmbedded session,
      EntityImpl entity) {
    if (session.getCurrentUser() == null) {
      return Collections.emptySet();
    }
    SchemaImmutableClass clazz = null;
    if (entity != null) {
      clazz = entity.getImmutableSchemaClass(session);
    }
    if (clazz == null) {
      return Collections.emptySet();
    }
    if (clazz.isSecurityPolicy()) {
      return Collections.emptySet();
    }

    if (roleHasPredicateSecurityForClass != null) {
      for (var role : session.getCurrentUser().getRoles()) {

        var roleMap = roleHasPredicateSecurityForClass.get(role.getName(session));
        if (roleMap == null) {
          return Collections.emptySet(); // TODO hierarchy...?
        }
        var val = roleMap.get(clazz.getName());
        if (!(Boolean.TRUE.equals(val))) {
          return Collections.emptySet(); // TODO hierarchy...?
        }
      }
    }
    var props = entity.getPropertyNamesInternal(false, false);
    Set<String> result = new HashSet<>();

    for (var prop : props) {
      var predicate =
          SecurityEngine.getPredicateForSecurityResource(
              session,
              this,
              "database.class.`" + clazz.getName() + "`.`" + prop + "`",
              SecurityPolicy.Scope.READ);
      if (!SecurityEngine.evaluateSecuirtyPolicyPredicate(session, predicate, (DBRecord) entity)) {
        result.add(prop);
      }
    }
    return result;
  }

  @Override
  public boolean isAllowedWrite(DatabaseSessionEmbedded session, EntityImpl entity,
      String propertyName) {

    if (session.getCurrentUser() == null) {
      // executeNoAuth
      return true;
    }

    String className;
    SchemaClass clazz;
    className = entity.getSchemaClassName();

    if (className == null) {
      return true;
    }

    if (roleHasPredicateSecurityForClass != null) {
      for (var role : session.getCurrentUser().getRoles()) {
        var roleMap = roleHasPredicateSecurityForClass.get(role.getName(session));
        if (roleMap == null) {
          return true; // TODO hierarchy...?
        }
        var val = roleMap.get(className);
        if (!(Boolean.TRUE.equals(val))) {
          return true; // TODO hierarchy...?
        }
      }
    }

    var sessionInternal = session;
    if (entity.getIdentity().isNew()) {
      var predicate =
          SecurityEngine.getPredicateForSecurityResource(
              sessionInternal,
              this,
              "database.class.`" + className + "`.`" + propertyName + "`",
              SecurityPolicy.Scope.CREATE);
      return SecurityEngine.evaluateSecuirtyPolicyPredicate(session, predicate, (DBRecord) entity);
    } else {

      var readPredicate =
          SecurityEngine.getPredicateForSecurityResource(
              sessionInternal,
              this,
              "database.class.`" + className + "`.`" + propertyName + "`",
              SecurityPolicy.Scope.READ);
      if (!SecurityEngine.evaluateSecuirtyPolicyPredicate(session, readPredicate,
          (DBRecord) entity)) {
        return false;
      }

      var beforePredicate =
          SecurityEngine.getPredicateForSecurityResource(
              sessionInternal,
              this,
              "database.class.`" + className + "`.`" + propertyName + "`",
              SecurityPolicy.Scope.BEFORE_UPDATE);
      var originalRecord = calculateOriginalValue(entity,
          session);

      if (!SecurityEngine.evaluateSecuirtyPolicyPredicate(
          session, beforePredicate, originalRecord)) {
        return false;
      }

      var predicate =
          SecurityEngine.getPredicateForSecurityResource(
              sessionInternal,
              this,
              "database.class.`" + className + "`.`" + propertyName + "`",
              SecurityPolicy.Scope.AFTER_UPDATE);
      return SecurityEngine.evaluateSecuirtyPolicyPredicate(session, predicate, (DBRecord) entity);
    }
  }

  @Override
  public boolean canCreate(DatabaseSessionEmbedded session, DBRecord record) {
    if (session.getCurrentUser() == null) {
      // executeNoAuth
      return true;
    }

    if (record instanceof Entity) {
      String className;
      if (record instanceof EntityImpl) {
        className = ((EntityImpl) record).getSchemaClassName();
      } else {
        className = ((Entity) record).getSchemaClassName();
      }

      if (roleHasPredicateSecurityForClass != null) {
        for (var role : session.getCurrentUser().getRoles()) {
          var roleMap = roleHasPredicateSecurityForClass.get(
              role.getName(session));
          if (roleMap == null) {
            return true; // TODO hierarchy...?
          }
          var val = roleMap.get(className);
          if (!(Boolean.TRUE.equals(val))) {
            return true; // TODO hierarchy...?
          }
        }
      }

      SQLBooleanExpression predicate;
      if (className == null) {
        predicate = null;
      } else {
        predicate =
            SecurityEngine.getPredicateForSecurityResource(
                session, this, "database.class.`" + className + "`",
                SecurityPolicy.Scope.CREATE);
      }
      return SecurityEngine.evaluateSecuirtyPolicyPredicate(session, predicate, record);
    }
    return true;
  }

  @Override
  public boolean canRead(DatabaseSessionEmbedded session, DBRecord record) {
    // TODO what about server users?
    if (session.getCurrentUser() == null) {
      // executeNoAuth
      return true;
    }

    if (record instanceof Entity) {
      SchemaImmutableClass clazz = null;
      if (record != null) {
        clazz = ((EntityImpl) record).getImmutableSchemaClass(session);
      }
      if (clazz == null) {
        return true;
      }
      if (clazz.isSecurityPolicy()) {
        return true;
      }

      if (roleHasPredicateSecurityForClass != null) {
        for (var role : session.getCurrentUser().getRoles()) {
          var roleMap = roleHasPredicateSecurityForClass.get(
              role.getName(session));
          if (roleMap == null) {
            return true; // TODO hierarchy...?
          }
          var val = roleMap.get(((EntityImpl) record).getSchemaClassName());
          if (!(Boolean.TRUE.equals(val))) {
            return true; // TODO hierarchy...?
          }
        }
      }

      var predicate =
          SecurityEngine.getPredicateForSecurityResource(
              session,
              this,
              "database.class.`" + ((EntityImpl) record).getSchemaClassName() + "`",
              SecurityPolicy.Scope.READ);
      return SecurityEngine.evaluateSecuirtyPolicyPredicate(session, predicate, record);
    }
    return true;
  }

  @Override
  public boolean canUpdate(DatabaseSessionEmbedded session, DBRecord record) {
    if (session.getCurrentUser() == null) {
      // executeNoAuth
      return true;
    }
    if (record instanceof Entity) {

      String className;
      if (record instanceof EntityImpl) {
        className = ((EntityImpl) record).getSchemaClassName();
      } else {
        className = ((Entity) record).getSchemaClassName();
      }

      if (className != null && roleHasPredicateSecurityForClass != null) {
        for (var role : session.getCurrentUser().getRoles()) {
          var roleMap = roleHasPredicateSecurityForClass.get(
              role.getName(session));
          if (roleMap == null) {
            return true; // TODO hierarchy...?
          }
          var val = roleMap.get(className);
          if (!(Boolean.TRUE.equals(val))) {
            return true; // TODO hierarchy...?
          }
        }
      }

      SQLBooleanExpression beforePredicate = null;
      if (className != null) {
        beforePredicate =
            SecurityEngine.getPredicateForSecurityResource(
                session,
                this,
                "database.class.`" + className + "`",
                SecurityPolicy.Scope.BEFORE_UPDATE);
      }

      // TODO avoid calculating original valueif not needed!!!

      var originalRecord = calculateOriginalValue(record, session);
      if (!SecurityEngine.evaluateSecuirtyPolicyPredicate(
          session, beforePredicate, originalRecord)) {
        return false;
      }

      SQLBooleanExpression predicate = null;
      if (className != null) {
        predicate =
            SecurityEngine.getPredicateForSecurityResource(
                session,
                this,
                "database.class.`" + className + "`",
                SecurityPolicy.Scope.AFTER_UPDATE);
      }
      return SecurityEngine.evaluateSecuirtyPolicyPredicate(session, predicate, record);
    }
    return true;
  }

  private static ResultInternal calculateOriginalValue(DBRecord record,
      DatabaseSessionEmbedded db) {
    var transaction = db.getActiveTransaction();
    return calculateBefore(transaction.load(record), db);
  }

  public static ResultInternal calculateBefore(EntityImpl entity,
      DatabaseSessionEmbedded db) {
    var result = new ResultInternal(db);
    for (var prop : entity.getPropertyNamesInternal(false, false)) {
      result.setProperty(prop, unboxRidbags(entity.getProperty(prop)));
    }
    result.setProperty("@rid", entity.getIdentity());
    result.setProperty("@class", entity.getSchemaClassName());
    result.setProperty("@version", entity.getVersion());

    for (var prop : entity.getDirtyPropertiesInternal(false, false)) {
      result.setProperty(prop, convert(entity.getPropertyOnLoadValueInternal(prop)));
    }
    return result;
  }

  private static Object convert(Object originalValue) {
    if (originalValue instanceof LinkBag) {
      Set result = new LinkedHashSet<>();
      ((LinkBag) originalValue).iterator().forEachRemaining(result::add);
      return result;
    }
    return originalValue;
  }

  public static Object unboxRidbags(Object value) {
    // TODO move it to some helper class
    if (value instanceof LinkBag) {
      List<Identifiable> result = new ArrayList<>(((LinkBag) value).size());
      for (Identifiable identifiable : (LinkBag) value) {
        result.add(identifiable);
      }

      return result;
    }
    return value;
  }

  @Override
  public boolean canDelete(DatabaseSessionEmbedded session, DBRecord record) {
    if (session.getCurrentUser() == null) {
      // executeNoAuth
      return true;
    }

    if (record instanceof Entity) {
      String className;
      if (record instanceof EntityImpl) {
        className = ((EntityImpl) record).getSchemaClassName();
      } else {
        className = ((Entity) record).getSchemaClassName();
      }

      if (roleHasPredicateSecurityForClass != null) {
        for (var role : session.getCurrentUser().getRoles()) {
          var roleMap = roleHasPredicateSecurityForClass.get(
              role.getName(session));
          if (roleMap == null) {
            return true; // TODO hierarchy...?
          }
          var val = roleMap.get(className);
          if (!(Boolean.TRUE.equals(val))) {
            return true; // TODO hierarchy...?
          }
        }
      }

      SQLBooleanExpression predicate = null;
      if (className != null) {
        predicate =
            SecurityEngine.getPredicateForSecurityResource(
                session, this, "database.class.`" + className + "`",
                SecurityPolicy.Scope.DELETE);
      }

      return SecurityEngine.evaluateSecuirtyPolicyPredicate(session, predicate, record);
    }
    return true;
  }

  @Override
  public boolean canExecute(DatabaseSessionEmbedded session, Function function) {
    if (session.getCurrentUser() == null) {
      // executeNoAuth
      return true;
    }

    var predicate =
        SecurityEngine.getPredicateForSecurityResource(
            session,
            this,
            "database.function." + function.getName(),
            SecurityPolicy.Scope.EXECUTE);
    if (predicate == null) {
      return true;
    }

    Identifiable identifiable = function.getIdentity();
    var transaction = session.getActiveTransaction();
    return SecurityEngine.evaluateSecuirtyPolicyPredicate(
        session, predicate, (DBRecord) transaction.loadEntity(identifiable));
  }

  @Nullable
  protected SQLBooleanExpression getPredicateFromCache(String roleName, String key) {
    var roleMap = this.securityPredicateCache.get(roleName);
    if (roleMap == null) {
      return null;
    }
    var result = roleMap.get(key.toLowerCase(Locale.ENGLISH));
    if (result != null) {
      return result.copy();
    }
    return null;
  }

  protected void putPredicateInCache(DatabaseSessionEmbedded session, String roleName, String key,
      SQLBooleanExpression predicate) {
    if (predicate.isCacheable(session)) {
      var roleMap = this.securityPredicateCache.computeIfAbsent(roleName,
          k -> new ConcurrentHashMap<>());

      roleMap.put(key.toLowerCase(Locale.ENGLISH), predicate);
    }
  }

  @Override
  public boolean isReadRestrictedBySecurityPolicy(DatabaseSessionEmbedded session,
      String resource) {
    if (session.getCurrentUser() == null) {
      // executeNoAuth
      return false;
    }

    var predicate =
        SecurityEngine.getPredicateForSecurityResource(
            session, this, resource, SecurityPolicy.Scope.READ);
    return predicate != null && !SQLBooleanExpression.TRUE.equals(predicate);
  }

  @Override
  public synchronized Set<SecurityResourceProperty> getAllFilteredProperties(
      DatabaseSessionEmbedded database) {
    if (filteredProperties == null) {
      updateAllFilteredProperties(database);
    }
    if (filteredProperties == null) {
      return Collections.emptySet();
    }
    return new HashSet<>(filteredProperties);
  }

  protected void updateAllFilteredProperties(DatabaseSessionEmbedded session) {
    Set<SecurityResourceProperty> result;
    if (session.getCurrentUser() == null) {
      result = calculateAllFilteredProperties(session);
      synchronized (this) {
        filteredProperties = result;
      }

    } else {
      synchronized (this) {
        if (filteredProperties == null) {
          filteredProperties = new HashSet<>();
        }
        updateAllFilteredPropertiesInternal(session);
      }
    }
  }

  protected void updateAllFilteredPropertiesInternal(DatabaseSessionEmbedded session) {
    var user = session.getCurrentUser();
    try {
      if (user != null) {
        session.setUser(null);
      }

      synchronized (SecurityShared.this) {
        filteredProperties.clear();
        filteredProperties.addAll(calculateAllFilteredProperties(session));
      }
    } finally {
      if (user != null) {
        session.setUser(user);
      }
    }
  }

  protected Set<SecurityResourceProperty> calculateAllFilteredProperties(
      DatabaseSessionEmbedded db) {
    Set<SecurityResourceProperty> result = new HashSet<>();
    if (!db
        .getMetadata()
        .getImmutableSchemaSnapshot()
        .existsClass(Role.CLASS_NAME)) {
      return Collections.emptySet();
    }
    try (var rs = db.query("select policies from " + Role.CLASS_NAME)) {
      while (rs.hasNext()) {
        var item = rs.next();
        Map<String, Identifiable> policies = item.getProperty("policies");
        if (policies != null) {
          for (var policyEntry : policies.entrySet()) {
            try {
              var res = SecurityResource.getInstance(policyEntry.getKey());
              if (res instanceof SecurityResourceProperty) {
                var transaction = db.getActiveTransaction();
                final Entity entity = transaction.load(policyEntry.getValue());
                final SecurityPolicy policy =
                    new ImmutableSecurityPolicy(new SecurityPolicyImpl((EntityImpl) entity));
                final var readRule = policy.getReadRule();
                if (readRule != null && !readRule.trim().equalsIgnoreCase("true")) {
                  result.add((SecurityResourceProperty) res);
                }
              }
            } catch (RecordNotFoundException e) {
              // ignore
            } catch (Exception e) {
              LogManager.instance().error(this, "Error on loading security policy", e);
            }
          }
        }
      }
    }
    return result;
  }

  public boolean couldHaveActivePredicateSecurityRoles(DatabaseSessionEmbedded session,
      String className) {
    if (session.getCurrentUser() == null) {
      return false;
    }
    if (roleHasPredicateSecurityForClass != null) {
      for (var role : session.getCurrentUser().getRoles()) {
        var roleMap = roleHasPredicateSecurityForClass.get(role.getName(session));
        if (roleMap == null) {
          return false; // TODO hierarchy...?
        }
        var val = roleMap.get(className);
        if (Boolean.TRUE.equals(val)) {
          return true; // TODO hierarchy...?
        }
      }

      return false;
    }
    return true;
  }
}
