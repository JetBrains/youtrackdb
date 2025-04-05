package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLOrBlock;
import java.util.Map;
import java.util.Set;

public class SecurityEngine {

  private static final PredicateCache cache =
      new PredicateCache(GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger());

  /**
   * Calculates a predicate for a security resource. It also takes into consideration the security
   * and schema hierarchies. ie. invoking it with a specific session for a specific class, the
   * method checks all the roles of the session, all the parent roles and all the parent classes
   * until it finds a valid predicarte (the most specific one).
   *
   * <p>For multiple-role session, the result is the OR of the predicates that would be calculated
   * for each single role.
   *
   * <p>For class hierarchies: - the most specific (ie. defined on subclass) defined predicate is
   * applied - in case a class does not have a direct predicate defined, the superclass predicate is
   * used (and recursively) - in case of multiple superclasses, the AND of the predicates for
   * superclasses (also recursively) is applied
   *
   * @param session
   * @param security
   * @param resourceString
   * @param scope
   * @return always returns a valid predicate (it is never supposed to be null)
   */
  static SQLBooleanExpression getPredicateForSecurityResource(
      DatabaseSessionInternal session,
      SecurityShared security,
      String resourceString,
      SecurityPolicy.Scope scope) {
    SecurityUser user = session.geCurrentUser();
    if (user == null) {
      return SQLBooleanExpression.FALSE;
    }

    Set<? extends SecurityRole> roles = user.getRoles();
    if (roles == null || roles.isEmpty()) {
      return SQLBooleanExpression.FALSE;
    }

    SecurityResource resource = getResourceFromString(resourceString);
    if (resource instanceof SecurityResourceClass) {
      return getPredicateForClass(session, security, (SecurityResourceClass) resource, scope);
    } else if (resource instanceof SecurityResourceProperty) {
      return getPredicateForProperty(
          session, security, (SecurityResourceProperty) resource, scope);
    } else if (resource instanceof SecurityResourceFunction) {
      return getPredicateForFunction(
          session, security, (SecurityResourceFunction) resource, scope);
    }
    return SQLBooleanExpression.FALSE;
  }

  private static SQLBooleanExpression getPredicateForFunction(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityResourceFunction resource,
      SecurityPolicy.Scope scope) {
    Function function =
        session.getMetadata().getFunctionLibrary().getFunction(resource.getFunctionName());
    Set<? extends SecurityRole> roles = session.geCurrentUser().getRoles();
    if (roles == null || roles.size() == 0) {
      return null;
    }
    if (roles.size() == 1) {
      return getPredicateForRoleHierarchy(
          session, security, roles.iterator().next(), function, scope);
    }

    SQLOrBlock result = new SQLOrBlock(-1);

    for (SecurityRole role : roles) {
      SQLBooleanExpression roleBlock =
          getPredicateForRoleHierarchy(session, security, role, function, scope);
      if (SQLBooleanExpression.TRUE.equals(roleBlock)) {
        return SQLBooleanExpression.TRUE;
      }
      result.getSubBlocks().add(roleBlock);
    }

    return result;
  }

  private static SQLBooleanExpression getPredicateForProperty(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityResourceProperty resource,
      SecurityPolicy.Scope scope) {
    SchemaClass clazz =
        session
            .getMetadata()
            .getImmutableSchemaSnapshot()
            .getClass(resource.getClassName());
    String propertyName = resource.getPropertyName();
    Set<? extends SecurityRole> roles = session.geCurrentUser().getRoles();
    if (roles == null || roles.size() == 0) {
      return null;
    }
    if (roles.size() == 1) {
      return getPredicateForRoleHierarchy(
          session, security, roles.iterator().next(), clazz, propertyName, scope);
    }

    SQLOrBlock result = new SQLOrBlock(-1);

    for (SecurityRole role : roles) {
      SQLBooleanExpression roleBlock =
          getPredicateForRoleHierarchy(session, security, role, clazz, propertyName, scope);
      if (SQLBooleanExpression.TRUE.equals(roleBlock)) {
        return SQLBooleanExpression.TRUE;
      }
      result.getSubBlocks().add(roleBlock);
    }

    return result;
  }

  private static SQLBooleanExpression getPredicateForClass(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityResourceClass resource,
      SecurityPolicy.Scope scope) {
    SchemaClass clazz =
        session
            .getMetadata()
            .getImmutableSchemaSnapshot()
            .getClass(resource.getClassName());
    if (clazz == null) {
      return SQLBooleanExpression.TRUE;
    }
    Set<? extends SecurityRole> roles = session.geCurrentUser().getRoles();
    if (roles == null || roles.size() == 0) {
      return null;
    }
    if (roles.size() == 1) {
      return getPredicateForRoleHierarchy(session, security, roles.iterator().next(), clazz, scope);
    }

    SQLOrBlock result = new SQLOrBlock(-1);

    for (SecurityRole role : roles) {
      SQLBooleanExpression roleBlock =
          getPredicateForRoleHierarchy(session, security, role, clazz, scope);
      if (SQLBooleanExpression.TRUE.equals(roleBlock)) {
        return SQLBooleanExpression.TRUE;
      }
      result.getSubBlocks().add(roleBlock);
    }

    return result;
  }

  private static SQLBooleanExpression getPredicateForRoleHierarchy(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityRole role,
      Function function,
      SecurityPolicy.Scope scope) {
    // TODO cache!

    SQLBooleanExpression result = getPredicateForFunction(session, security, role, function, scope);
    if (result != null) {
      return result;
    }

    if (role.getParentRole() != null) {
      return getPredicateForRoleHierarchy(session, security, role.getParentRole(), function, scope);
    }
    return SQLBooleanExpression.FALSE;
  }

  private static SQLBooleanExpression getPredicateForFunction(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityRole role,
      Function clazz,
      SecurityPolicy.Scope scope) {
    String resource = "database.function." + clazz.getName(session);
    Map<String, SecurityPolicy> definedPolicies = security.getSecurityPolicies(session, role);
    SecurityPolicy policy = definedPolicies.get(resource);

    String predicateString = policy != null ? policy.get(scope, session) : null;

    if (predicateString == null) {
      SecurityPolicy wildcardPolicy = definedPolicies.get("database.function.*");
      predicateString = wildcardPolicy == null ? null : wildcardPolicy.get(scope, session);
    }

    if (predicateString != null) {
      return parsePredicate(session, predicateString);
    }
    return SQLBooleanExpression.FALSE;
  }

  private static SQLBooleanExpression getPredicateForRoleHierarchy(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityRole role,
      SchemaClass clazz,
      SecurityPolicy.Scope scope) {
    SQLBooleanExpression result;
    if (role != null) {
      result = security.getPredicateFromCache(role.getName(session), clazz.getName());
      if (result != null) {
        return result;
      }
    }

    result = getPredicateForClassHierarchy(session, security, role, clazz, scope);
    if (result != null) {
      return result;
    }

    if (role.getParentRole() != null) {
      result = getPredicateForRoleHierarchy(session, security, role.getParentRole(), clazz, scope);
    }
    if (result == null) {
      result = SQLBooleanExpression.FALSE;
    }
    security.putPredicateInCache(session, role.getName(session), clazz.getName(), result);
    return result;
  }

  private static SQLBooleanExpression getPredicateForRoleHierarchy(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityRole role,
      SchemaClass clazz,
      String propertyName,
      SecurityPolicy.Scope scope) {
    String cacheKey = "$CLASS$" + clazz.getName() + "$PROP$" + propertyName + "$" + scope;
    SQLBooleanExpression result;
    if (role != null) {
      result = security.getPredicateFromCache(role.getName(session), cacheKey);
      if (result != null) {
        return result;
      }
    }

    result = getPredicateForClassHierarchy(session, security, role, clazz, propertyName, scope);
    if (result == null && role.getParentRole() != null) {
      result =
          getPredicateForRoleHierarchy(
              session, security, role.getParentRole(), clazz, propertyName, scope);
    }
    if (result == null) {
      result = SQLBooleanExpression.FALSE;
    }
    if (role != null) {
      security.putPredicateInCache(session, role.getName(session), cacheKey, result);
    }
    return result;
  }

  private static SQLBooleanExpression getPredicateForClassHierarchy(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityRole role,
      SchemaClass clazz,
      SecurityPolicy.Scope scope) {
    String resource = "database.class." + clazz.getName();
    Map<String, SecurityPolicy> definedPolicies = security.getSecurityPolicies(session, role);
    SecurityPolicy classPolicy = definedPolicies.get(resource);

    String predicateString = classPolicy != null ? classPolicy.get(scope, session) : null;
    if (predicateString == null && !clazz.getSuperClasses(session).isEmpty()) {
      if (clazz.getSuperClasses(session).size() == 1) {
        return getPredicateForClassHierarchy(
            session, security, role, clazz.getSuperClasses(session).iterator().next(), scope);
      }
      SQLAndBlock result = new SQLAndBlock(-1);
      for (SchemaClass superClass : clazz.getSuperClasses(session)) {
        SQLBooleanExpression superClassPredicate =
            getPredicateForClassHierarchy(session, security, role, superClass, scope);
        if (superClassPredicate == null) {
          return SQLBooleanExpression.FALSE;
        }
        result.getSubBlocks().add(superClassPredicate);
      }
      return result;
    }

    if (predicateString == null) {
      SecurityPolicy wildcardPolicy = definedPolicies.get("database.class.*");
      predicateString = wildcardPolicy == null ? null : wildcardPolicy.get(scope, session);
    }

    if (predicateString == null) {
      SecurityPolicy wildcardPolicy = definedPolicies.get("*");
      predicateString = wildcardPolicy == null ? null : wildcardPolicy.get(scope, session);
    }
    if (predicateString != null) {
      return parsePredicate(session, predicateString);
    }
    return SQLBooleanExpression.FALSE;
  }

  private static SQLBooleanExpression getPredicateForClassHierarchy(
      DatabaseSessionInternal session,
      SecurityShared security,
      SecurityRole role,
      SchemaClass clazz,
      String propertyName,
      SecurityPolicy.Scope scope) {
    String resource = "database.class." + clazz.getName() + "." + propertyName;
    Map<String, SecurityPolicy> definedPolicies = security.getSecurityPolicies(session, role);
    SecurityPolicy classPolicy = definedPolicies.get(resource);

    String predicateString = classPolicy != null ? classPolicy.get(scope, session) : null;
    if (predicateString == null && !clazz.getSuperClasses(session).isEmpty()) {
      if (clazz.getSuperClasses(session).size() == 1) {
        return getPredicateForClassHierarchy(
            session,
            security,
            role,
            clazz.getSuperClasses(session).iterator().next(),
            propertyName,
            scope);
      }
      SQLAndBlock result = new SQLAndBlock(-1);
      for (SchemaClass superClass : clazz.getSuperClasses(session)) {
        SQLBooleanExpression superClassPredicate =
            getPredicateForClassHierarchy(session, security, role, superClass, propertyName, scope);
        if (superClassPredicate == null) {
          return SQLBooleanExpression.TRUE;
        }
        result.getSubBlocks().add(superClassPredicate);
      }
      return result;
    }

    if (predicateString == null) {
      SecurityPolicy wildcardPolicy =
          definedPolicies.get("database.class." + clazz.getName() + ".*");
      predicateString = wildcardPolicy == null ? null : wildcardPolicy.get(scope, session);
    }

    if (predicateString == null) {
      SecurityPolicy wildcardPolicy = definedPolicies.get("database.class.*." + propertyName);
      predicateString = wildcardPolicy == null ? null : wildcardPolicy.get(scope, session);
    }

    if (predicateString == null) {
      SecurityPolicy wildcardPolicy = definedPolicies.get("database.class.*.*");
      predicateString = wildcardPolicy == null ? null : wildcardPolicy.get(scope, session);
    }

    if (predicateString == null) {
      SecurityPolicy wildcardPolicy = definedPolicies.get("*");
      predicateString = wildcardPolicy == null ? null : wildcardPolicy.get(scope, session);
    }
    // TODO

    if (predicateString != null) {
      return parsePredicate(session, predicateString);
    }
    return SQLBooleanExpression.TRUE;
  }

  public static SQLBooleanExpression parsePredicate(
      DatabaseSession session, String predicateString) {
    if ("true".equalsIgnoreCase(predicateString)) {
      return SQLBooleanExpression.TRUE;
    }
    if ("false".equalsIgnoreCase(predicateString)) {
      return SQLBooleanExpression.FALSE;
    }
    try {

      return cache.get(predicateString);
    } catch (Exception e) {
      System.out.println("Error parsing predicate: " + predicateString);
      throw e;
    }
  }

  static boolean evaluateSecuirtyPolicyPredicate(
      DatabaseSessionInternal session, SQLBooleanExpression predicate, DBRecord record) {
    if (SQLBooleanExpression.TRUE.equals(predicate)) {
      return true;
    }
    if (SQLBooleanExpression.FALSE.equals(predicate)) {
      return false;
    }
    if (predicate == null) {
      return true; // TODO check!
    }
    try {
      // Create a new instance of EntityImpl with a user record id, this will lazy load the user data
      // at the first access with the same execution permission of the policy
      Identifiable user = session.geCurrentUser().getIdentity(session);

      var sessionInternal = session;
      var recordCopy = ((RecordAbstract) record).copy();
      return sessionInternal
          .getSharedContext()
          .getYouTrackDB()
          .executeNoAuthorizationSync(
              sessionInternal,
              (db -> {
                BasicCommandContext ctx = new BasicCommandContext();
                ctx.setDatabase(db);
                ctx.setDynamicVariable("$currentUser", (inContext) -> user.getRecordSilently());

                recordCopy.setup(db);
                return predicate.evaluate(recordCopy, ctx);
              }));
    } catch (Exception e) {
      throw BaseException.wrapException(
          new SecurityException("Cannot execute security predicate"), e);
    }
  }

  static boolean evaluateSecuirtyPolicyPredicate(
      DatabaseSessionInternal session, SQLBooleanExpression predicate, Result record) {
    if (SQLBooleanExpression.TRUE.equals(predicate)) {
      return true;
    }
    if (SQLBooleanExpression.FALSE.equals(predicate)) {
      return false;
    }
    try {
      // Create a new instance of EntityImpl with a user record id, this will lazy load the user data
      // at the first access with the same execution permission of the policy
      final EntityImpl user = session.geCurrentUser().getIdentity(session).getRecordSilently();
      return session
          .getSharedContext()
          .getYouTrackDB()
          .executeNoAuthorizationAsync(
              session.getName(),
              (db -> {
                BasicCommandContext ctx = new BasicCommandContext();
                ctx.setDatabase(db);
                ctx.setDynamicVariable(
                    "$currentUser",
                    (inContext) -> {
                      return user;
                    });
                return predicate.evaluate(record, ctx);
              }))
          .get();
    } catch (Exception e) {
      e.printStackTrace();
      throw new SecurityException("Cannot execute security predicate");
    }
  }

  /**
   * returns a resource from a resource string, eg. an OUser SchemaClass from "database.class.OUser"
   * string
   *
   * @param resource a resource string
   * @return
   */
  private static SecurityResource getResourceFromString(String resource) {
    return SecurityResource.getInstance(resource);
  }
}
