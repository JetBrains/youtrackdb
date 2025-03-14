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
package com.jetbrains.youtrack.db.internal.core.security;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecuritySystemUserImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrack.db.internal.core.security.authenticator.DatabaseUserAuthenticator;
import com.jetbrains.youtrack.db.internal.core.security.authenticator.ServerConfigAuthenticator;
import com.jetbrains.youtrack.db.internal.core.security.authenticator.SystemUserAuthenticator;
import com.jetbrains.youtrack.db.internal.core.security.authenticator.TemporaryGlobalUser;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerJackson;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides an implementation of OServerSecurity.
 */
public class DefaultSecuritySystem implements SecuritySystem {

  private boolean enabled = false; // Defaults to not
  // enabled at
  // first.
  private boolean debug = false;

  private boolean storePasswords = true;

  // OServerSecurity (via SecurityAuthenticator)
  // Some external security implementations may permit falling back to a
  // default authentication mode if external authentication fails.
  private boolean allowDefault = true;

  private final Object passwordValidatorSynch = new Object();
  private PasswordValidator passwordValidator;

  private final Object importLDAPSynch = new Object();
  private SecurityComponent importLDAP;

  private final Object auditingSynch = new Object();
  private AuditingService auditingService;

  private Map<String, Object> configEntity; // Holds the
  // current JSON
  // configuration.
  private SecurityConfig serverConfig;
  private YouTrackDBInternal context;

  private Map<String, Object> auditingEntity;
  private Map<String, Object> serverEntity;
  private Map<String, Object> authEntity;
  private Map<String, Object> passwdValEntity;
  private Map<String, Object> ldapImportEntity;

  // We use a list because the order indicates priority of method.
  private volatile List<SecurityAuthenticator> authenticatorsList;
  private volatile List<SecurityAuthenticator> enabledAuthenticators;

  private final ConcurrentHashMap<String, Class<?>> securityClassMap =
      new ConcurrentHashMap<String, Class<?>>();
  private TokenSign tokenSign;
  private final Map<String, TemporaryGlobalUser> ephemeralUsers =
      new ConcurrentHashMap<String, TemporaryGlobalUser>();

  private final Map<String, GlobalUser> configUsers = new HashMap<String, GlobalUser>();

  public DefaultSecuritySystem() {
  }

  public void activate(final YouTrackDBInternal context,
      final SecurityConfig serverCfg) {
    this.context = context;
    this.serverConfig = serverCfg;
    if (serverConfig != null) {
      this.load(serverConfig.getConfigurationFile());
    }
    onAfterDynamicPlugins(null);
    tokenSign = new TokenSignImpl(context.getConfiguration().getConfiguration());
    for (var user : context.getConfiguration().getUsers()) {
      configUsers.put(user.getName(), user);
    }
  }

  public static void createSystemRoles(DatabaseSessionInternal session) {
    session.executeInTx(
        () -> {
          var security = session.getMetadata().getSecurity();
          if (security.getRole("root") == null) {
            var root = security.createRole("root");
            for (var resource : Rule.ResourceGeneric.values()) {
              root.addRule(session, resource, null, Role.PERMISSION_ALL);
            }
            // Do not allow root to have access to audit log class by default.
            root.addRule(session, ResourceGeneric.CLASS, "OAuditingLog", Role.PERMISSION_NONE);
            root.addRule(session, ResourceGeneric.CLUSTER, "oauditinglog", Role.PERMISSION_NONE);
            root.save(session);
          }
          if (security.getRole("guest") == null) {
            var guest = security.createRole("guest");
            guest.addRule(session, ResourceGeneric.SERVER, "listDatabases", Role.PERMISSION_ALL);
            guest.save(session);
          }
          // for monitoring/logging purposes, intended to connect from external monitoring systems
          if (security.getRole("monitor") == null) {
            var guest = security.createRole("monitor");
            guest.addRule(session, ResourceGeneric.CLASS, null, Role.PERMISSION_READ);
            guest.addRule(session, ResourceGeneric.CLUSTER, null, Role.PERMISSION_READ);
            guest.addRule(session, ResourceGeneric.SYSTEM_CLUSTERS, null, Role.PERMISSION_READ);
            guest.addRule(session, ResourceGeneric.SCHEMA, null, Role.PERMISSION_READ);
            guest.addRule(session, ResourceGeneric.FUNCTION, null, Role.PERMISSION_ALL);
            guest.addRule(session, ResourceGeneric.COMMAND, null, Role.PERMISSION_ALL);
            guest.addRule(session, ResourceGeneric.COMMAND_GREMLIN, null, Role.PERMISSION_ALL);
            guest.addRule(session, ResourceGeneric.DATABASE, null, Role.PERMISSION_READ);
            guest.addRule(session, ResourceGeneric.SERVER, null, Role.PERMISSION_READ);
            guest.save(session);
          }
          // a separate role for accessing the auditing logs
          if (security.getRole("auditor") == null) {
            var auditor = security.createRole("auditor");
            auditor.addRule(session, ResourceGeneric.DATABASE, null, Role.PERMISSION_READ);
            auditor.addRule(session, ResourceGeneric.SCHEMA, null, Role.PERMISSION_READ);
            auditor.addRule(session, ResourceGeneric.CLASS, null, Role.PERMISSION_READ);
            auditor.addRule(session, ResourceGeneric.CLUSTER, null, Role.PERMISSION_READ);
            auditor.addRule(session, ResourceGeneric.CLUSTER, "orole", Role.PERMISSION_NONE);
            auditor.addRule(session, ResourceGeneric.CLUSTER, "ouser", Role.PERMISSION_NONE);
            auditor.addRule(session, ResourceGeneric.CLASS, "OUser", Role.PERMISSION_NONE);
            auditor.addRule(session, ResourceGeneric.CLASS, "orole", Role.PERMISSION_NONE);
            auditor.addRule(session, ResourceGeneric.SYSTEM_CLUSTERS, null, Role.PERMISSION_NONE);
            auditor.addRule(session, ResourceGeneric.CLASS, "OAuditingLog",
                Role.PERMISSION_CREATE + Role.PERMISSION_READ + Role.PERMISSION_UPDATE);
            auditor.addRule(session,
                ResourceGeneric.CLUSTER,
                "oauditinglog",
                Role.PERMISSION_CREATE + Role.PERMISSION_READ + Role.PERMISSION_UPDATE);
            auditor.save(session);
          }
        });
  }

  private void initDefultAuthenticators(DatabaseSessionInternal session) {
    var serverAuth = new ServerConfigAuthenticator();
    serverAuth.config(session, null, this);

    var databaseAuth = new DatabaseUserAuthenticator();
    databaseAuth.config(session, null, this);

    var systemAuth = new SystemUserAuthenticator();
    systemAuth.config(session, null, this);

    List<SecurityAuthenticator> authenticators = new ArrayList<>();
    authenticators.add(serverAuth);
    authenticators.add(systemAuth);
    authenticators.add(databaseAuth);
    setAuthenticatorList(authenticators);
  }

  public void shutdown() {
    close();
  }

  private Class<?> getClass(final Map<String, Object> jsonConfig) {
    Class<?> cls = null;

    try {
      if (jsonConfig.containsKey("class")) {
        final var clsName = jsonConfig.get("class").toString();

        if (securityClassMap.containsKey(clsName)) {
          cls = securityClassMap.get(clsName);
        } else {
          cls = Class.forName(clsName);
        }
      }
    } catch (Exception th) {
      LogManager.instance().error(this, "DefaultServerSecurity.getClass() Throwable: ", th);
    }

    return cls;
  }

  // SecuritySystem (via OServerSecurity)
  // Some external security implementations may permit falling back to a
  // default authentication mode if external authentication fails.
  public boolean isDefaultAllowed() {
    if (enabled) {
      return allowDefault;
    } else {
      return true; // If the security system is disabled return the original system default.
    }
  }

  @Override
  public SecurityUser authenticate(
      DatabaseSessionInternal session, AuthenticationInfo authenticationInfo) {
    try {
      for (var sa : enabledAuthenticators) {
        var principal = sa.authenticate(session, authenticationInfo);

        if (principal != null) {
          return principal;
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.authenticate()", ex);
    }

    return null; // Indicates authentication failed.
  }

  // SecuritySystem (via OServerSecurity)
  public SecurityUser authenticate(
      DatabaseSessionInternal session, final String username, final String password) {
    try {
      // It's possible for the username to be null or an empty string in the case of SPNEGO
      // Kerberos
      // tickets.
      if (username != null && !username.isEmpty()) {
        if (debug) {
          LogManager.instance()
              .info(
                  this,
                  "DefaultServerSecurity.authenticate() ** Authenticating username: %s",
                  username);
        }
      }

      for (var sa : enabledAuthenticators) {
        var principal = sa.authenticate(session, username, password);

        if (principal != null) {
          return principal;
        }
      }

    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.authenticate()", ex);
    }

    return null; // Indicates authentication failed.
  }

  public SecurityUser authenticateServerUser(DatabaseSessionInternal session,
      final String username,
      final String password) {
    var user = getServerUser(session, username);

    if (user != null && user.getPassword(session) != null) {
      if (SecurityManager.checkPassword(password, user.getPassword(session).trim())) {
        return user;
      }
    }
    return null;
  }

  public YouTrackDBInternal getContext() {
    return context;
  }

  // SecuritySystem (via OServerSecurity)
  // Used for generating the appropriate HTTP authentication mechanism.
  public String getAuthenticationHeader(final String databaseName) {
    String header = null;

    // Default to Basic.
    if (databaseName != null) {
      header = "WWW-Authenticate: Basic realm=\"YouTrackDB db-" + databaseName + "\"";
    } else {
      header = "WWW-Authenticate: Basic realm=\"YouTrackDB Server\"";
    }

    if (enabled) {
      var sb = new StringBuilder();

      // Walk through the list of OSecurityAuthenticators.
      for (var sa : enabledAuthenticators) {
        var sah = sa.getAuthenticationHeader(databaseName);

        if (sah != null && !sah.trim().isEmpty()) {
          // If we're not the first authenticator, then append "\n".
          if (!sb.isEmpty()) {
            sb.append("\r\n");
          }
          sb.append(sah);
        }
      }

      if (!sb.isEmpty()) {
        header = sb.toString();
      }
    }

    return header;
  }

  @Override
  public Map<String, String> getAuthenticationHeaders(String databaseName) {
    Map<String, String> headers = new HashMap<>();

    // Default to Basic.
    if (databaseName != null) {
      headers.put("WWW-Authenticate", "Basic realm=\"YouTrackDB db-" + databaseName + "\"");
    } else {
      headers.put("WWW-Authenticate", "Basic realm=\"YouTrackDB Server\"");
    }

    if (enabled) {

      // Walk through the list of OSecurityAuthenticators.
      for (var sa : enabledAuthenticators) {
        if (sa.isEnabled()) {
          var currentHeaders = sa.getAuthenticationHeaders(databaseName);
          headers.putAll(currentHeaders);
        }
      }
    }

    return headers;
  }

  // SecuritySystem (via OServerSecurity)
  public Map<String, Object> getConfig() {
    var jsonConfig = new HashMap<String, Object>();

    try {
      jsonConfig.put("enabled", enabled);
      jsonConfig.put("debug", debug);

      if (serverEntity != null) {
        jsonConfig.put("server", serverEntity);
      }

      if (authEntity != null) {
        jsonConfig.put("authentication", authEntity);
      }

      if (passwdValEntity != null) {
        jsonConfig.put("passwordValidator", passwdValEntity);
      }

      if (ldapImportEntity != null) {
        jsonConfig.put("ldapImporter", ldapImportEntity);
      }

      if (auditingEntity != null) {
        jsonConfig.put("auditing", auditingEntity);
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.getConfig() Exception: %s", ex);
    }

    return jsonConfig;
  }

  // SecuritySystem (via OServerSecurity)
  // public EntityImpl getComponentConfig(final String name) { return getSection(name); }

  public Map<String, Object> getComponentConfig(final String name) {
    if (name != null) {
      if (name.equalsIgnoreCase("auditing")) {
        return auditingEntity;
      } else if (name.equalsIgnoreCase("authentication")) {
        return authEntity;
      } else if (name.equalsIgnoreCase("ldapImporter")) {
        return ldapImportEntity;
      } else if (name.equalsIgnoreCase("passwordValidator")) {
        return passwdValEntity;
      } else if (name.equalsIgnoreCase("server")) {
        return serverEntity;
      }
    }

    return null;
  }

  // OServerSecurity

  /**
   * Returns the "System User" associated with 'username' from the system database. If not found,
   * returns null. dbName is used to filter the assigned roles. It may be null.
   */
  public SecurityUser getSystemUser(final String username, final String dbName) {
    // ** There are cases when we need to retrieve an OUser that is a system user.
    //  if (isEnabled() && !SystemDatabase.SYSTEM_DB_NAME.equals(dbName)) {
    var systemDb = context.getSystemDatabase();
    if (context.getSystemDatabase().exists()) {
      return systemDb
          .query(
              (resultset, session) -> {
                var sessionInternal = (DatabaseSessionInternal) session;
                if (resultset != null && resultset.hasNext()) {
                  return new ImmutableUser(sessionInternal,
                      0,
                      new SecuritySystemUserImpl(sessionInternal,
                          resultset.next().asEntity().getRecord(session), dbName));
                }
                return null;
              },
              "select from OUser where name = ? limit 1 fetchplan roles:1",
              username);
    }
    return null;
  }

  // SecuritySystem (via OServerSecurity)
  // This will first look for a user in the security.json "users" array and then check if a resource
  // matches.
  public boolean isAuthorized(DatabaseSessionInternal session, final String username,
      final String resource) {
    if (username == null || resource == null) {
      return false;
    }

    // Walk through the list of OSecurityAuthenticators.
    for (var sa : enabledAuthenticators) {
      if (sa.isAuthorized(session, username, resource)) {
        return true;
      }
    }
    return false;
  }

  public boolean isServerUserAuthorized(DatabaseSessionInternal session, final String username,
      final String resource) {
    final var user = getServerUser(session, username);

    if (user != null) {
      // TODO: to verify if this logic match previous logic
      return user.checkIfAllowed(session, resource, Role.PERMISSION_ALL) != null;
      /*
      if (user.getResources().equals("*"))
        // ACCESS TO ALL
        return true;

      String[] resourceParts = user.getResources().split(",");
      for (String r : resourceParts) if (r.equalsIgnoreCase(resource)) return true;
      */
    }
    return false;
  }

  // SecuritySystem (via OServerSecurity)
  public boolean isEnabled() {
    return enabled;
  }

  // SecuritySystem (via OServerSecurity)
  // Indicates if passwords should be stored for users.
  public boolean arePasswordsStored() {
    if (enabled) {
      return storePasswords;
    } else {
      return true; // If the security system is disabled return the original system default.
    }
  }

  // SecuritySystem (via OServerSecurity)
  // Indicates if the primary security mechanism supports single sign-on.
  public boolean isSingleSignOnSupported() {
    if (enabled) {
      var priAuth = getPrimaryAuthenticator();

      if (priAuth != null) {
        return priAuth.isSingleSignOnSupported();
      }
    }

    return false;
  }

  // SecuritySystem (via OServerSecurity)
  public void validatePassword(final String username, final String password)
      throws InvalidPasswordException {
    if (enabled) {
      synchronized (passwordValidatorSynch) {
        if (passwordValidator != null) {
          passwordValidator.validatePassword(username, password);
        }
      }
    }
  }

  /**
   * OServerSecurity Interface *
   */

  // OServerSecurity
  public AuditingService getAuditing() {
    return auditingService;
  }

  // OServerSecurity
  public SecurityAuthenticator getAuthenticator(final String authMethod) {
    for (var am : authenticatorsList) {
      // If authMethod is null or an empty string, then return the first SecurityAuthenticator.
      if (authMethod == null || authMethod.isEmpty()) {
        return am;
      }

      if (am.getName() != null && am.getName().equalsIgnoreCase(authMethod)) {
        return am;
      }
    }

    return null;
  }

  // OServerSecurity
  // Returns the first SecurityAuthenticator in the list.
  public SecurityAuthenticator getPrimaryAuthenticator() {
    if (enabled) {
      var auth = authenticatorsList;
      if (!auth.isEmpty()) {
        return auth.getFirst();
      }
    }

    return null;
  }

  // OServerSecurity
  public SecurityUser getUser(final String username, DatabaseSessionInternal session) {
    SecurityUser userCfg = null;

    // Walk through the list of OSecurityAuthenticators.
    for (var sa : enabledAuthenticators) {
      userCfg = sa.getUser(username, session);
      if (userCfg != null) {
        break;
      }
    }

    return userCfg;
  }

  public SecurityUser getServerUser(DatabaseSessionInternal session, final String username) {
    SecurityUser systemUser = null;
    // This will throw an IllegalArgumentException if iUserName is null or empty.
    // However, a null or empty iUserName is possible with some security implementations.
    if (username != null && !username.isEmpty()) {
      var userCfg = configUsers.get(username);
      if (userCfg == null) {
        for (var user : ephemeralUsers.values()) {
          if (username.equalsIgnoreCase(user.getName())) {
            // FOUND
            userCfg = user;
          }
        }
      }
      if (userCfg != null) {
        var role = SecurityShared.createRole(userCfg);
        systemUser =
            new ImmutableUser(session,
                username, userCfg.getPassword(), SecurityUser.SERVER_USER_TYPE, role);
      }
    }

    return systemUser;
  }

  @Override
  public Syslog getSyslog() {
    return serverConfig.getSyslog();
  }

  // SecuritySystem
  public void log(
      DatabaseSessionInternal session, final AuditingOperation operation,
      final String dbName,
      SecurityUser user,
      final String message) {
    synchronized (auditingSynch) {
      if (auditingService != null) {
        auditingService.log(session, operation, dbName, user, message);
      }
    }
  }

  // SecuritySystem
  public void registerSecurityClass(final Class<?> cls) {
    var fullTypeName = getFullTypeName(cls);

    if (fullTypeName != null) {
      securityClassMap.put(fullTypeName, cls);
    }
  }

  // SecuritySystem
  public void unregisterSecurityClass(final Class<?> cls) {
    var fullTypeName = getFullTypeName(cls);

    if (fullTypeName != null) {
      securityClassMap.remove(fullTypeName);
    }
  }

  // Returns the package plus type name of Class.
  private static String getFullTypeName(Class<?> type) {
    String typeName = null;

    typeName = type.getSimpleName();

    var pack = type.getPackage();

    if (pack != null) {
      typeName = pack.getName() + "." + typeName;
    }

    return typeName;
  }

  public void load(final String cfgPath) {
    this.configEntity = loadConfig(cfgPath);
  }

  // SecuritySystem
  public void reload(DatabaseSessionInternal session, final Map<String, Object> configEntity) {
    reload(session, null, configEntity);
  }

  @Override
  public void reload(DatabaseSessionInternal session, SecurityUser user,
      Map<String, Object> configEntity) {
    if (configEntity != null) {
      close();

      this.configEntity = configEntity;

      onAfterDynamicPlugins(session, user);

      log(session,
          AuditingOperation.RELOADEDSECURITY,
          null,
          user, "The security configuration file has been reloaded");
    } else {
      LogManager.instance()
          .warn(
              this,
              "DefaultServerSecurity.reload(EntityImpl) The provided configuration entity is"
                  + " null");
      throw new SecuritySystemException(
          "DefaultServerSecurity.reload(EntityImpl) The provided configuration entity is null");
    }
  }

  public void reloadComponent(DatabaseSessionInternal session, SecurityUser user,
      final String name, final Map<String, Object> jsonConfig) {
    if (name == null || name.isEmpty()) {
      throw new SecuritySystemException(
          "DefaultServerSecurity.reloadComponent() name is null or empty");
    }
    if (jsonConfig == null) {
      throw new SecuritySystemException(
          "DefaultServerSecurity.reloadComponent() Configuration entity is null");
    }

    if (name.equalsIgnoreCase("auditing")) {
      auditingEntity = jsonConfig;
      reloadAuditingService(session);

    } else if (name.equalsIgnoreCase("authentication")) {
      authEntity = jsonConfig;
      reloadAuthMethods(session);

    } else if (name.equalsIgnoreCase("ldapImporter")) {
      ldapImportEntity = jsonConfig;
      reloadImportLDAP(session);
    } else if (name.equalsIgnoreCase("passwordValidator")) {
      passwdValEntity = jsonConfig;
      reloadPasswordValidator(session);
    } else if (name.equalsIgnoreCase("server")) {
      serverEntity = jsonConfig;
      reloadServer();
    }
    setSection(name, jsonConfig);

    log(session,
        AuditingOperation.RELOADEDSECURITY,
        null,
        user, String.format("The %s security component has been reloaded", name));
  }

  private void loadAuthenticators(DatabaseSessionInternal session,
      final Map<String, Object> authEntity) {
    if (authEntity.containsKey("authenticators")) {
      List<SecurityAuthenticator> autheticators = new ArrayList<>();
      @SuppressWarnings("unchecked")
      var authMethodsList = (List<Map<String, Object>>) authEntity.get(
          "authenticators");

      for (var authMethodEntity : authMethodsList) {
        try {
          if (authMethodEntity.containsKey("name")) {
            final var name = authMethodEntity.get("name").toString();

            // defaults to enabled if "enabled" is missing
            var enabled = true;

            if (authMethodEntity.containsKey("enabled")) {
              enabled = (Boolean) authMethodEntity.get("enabled");
            }

            if (enabled) {
              var authClass = getClass(authMethodEntity);

              if (authClass != null) {
                if (SecurityAuthenticator.class.isAssignableFrom(authClass)) {
                  var authPlugin =
                      (SecurityAuthenticator) authClass.newInstance();

                  authPlugin.config(session, authMethodEntity, this);
                  authPlugin.active();

                  autheticators.add(authPlugin);
                } else {
                  LogManager.instance()
                      .error(
                          this,
                          "DefaultServerSecurity.loadAuthenticators() class is not an"
                              + " SecurityAuthenticator",
                          null);
                }
              } else {
                LogManager.instance()
                    .error(
                        this,
                        "DefaultServerSecurity.loadAuthenticators() authentication class is null"
                            + " for %s",
                        null,
                        name);
              }
            }
          } else {
            LogManager.instance()
                .error(
                    this,
                    "DefaultServerSecurity.loadAuthenticators() authentication object is missing"
                        + " name",
                    null);
          }
        } catch (Exception ex) {
          LogManager.instance()
              .error(this, "DefaultServerSecurity.loadAuthenticators() Exception: ", ex);
        }
      }
      if (isDefaultAllowed()) {
        autheticators.add(new DatabaseUserAuthenticator());
      }
      setAuthenticatorList(autheticators);
    } else {
      initDefultAuthenticators(session);
    }
  }

  // OServerSecurity
  public void onAfterDynamicPlugins(DatabaseSessionInternal session) {
    onAfterDynamicPlugins(session, null);
  }

  @Override
  public void onAfterDynamicPlugins(DatabaseSessionInternal session, SecurityUser user) {
    if (configEntity != null) {
      loadComponents(session);

      if (enabled) {
        log(session, AuditingOperation.SECURITY, null, user, "The security module is now loaded");
      }
    } else {
      initDefultAuthenticators(session);
      LogManager.instance().debug(this, "onAfterDynamicPlugins() Configuration entity is empty");
    }
  }

  protected void loadComponents(DatabaseSessionInternal session) {
    // Loads the top-level configuration properties ("enabled" and "debug").
    loadSecurity();

    if (enabled) {
      // Loads the "auditing" configuration properties.
      auditingEntity = getSection("auditing");
      reloadAuditingService(session);

      // Loads the "server" configuration properties.
      serverEntity = getSection("server");
      reloadServer();

      // Loads the "authentication" configuration properties.
      authEntity = getSection("authentication");
      reloadAuthMethods(session);

      // Loads the "passwordValidator" configuration properties.
      passwdValEntity = getSection("passwordValidator");
      reloadPasswordValidator(session);

      // Loads the "ldapImporter" configuration properties.
      ldapImportEntity = getSection("ldapImporter");
      reloadImportLDAP(session);
    }
  }

  // Returns a section of the JSON document configuration as an EntityImpl if section is present.
  private Map<String, Object> getSection(final String section) {
    Map<String, Object> sectionEntity = null;

    try {
      if (configEntity != null) {
        if (configEntity.containsKey(section)) {
          //noinspection unchecked
          sectionEntity = (Map<String, Object>) configEntity.get(section);
        }
      } else {
        LogManager.instance()
            .error(
                this,
                "DefaultServerSecurity.getSection(%s) Configuration entity is null",
                null,
                section);
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.getSection(%s)", ex, section);
    }

    return sectionEntity;
  }

  // Change the component section and save it to disk
  private void setSection(final String section, Map<String, Object> sectionEntity) {

    var oldSection = getSection(section);
    try {
      if (configEntity != null) {

        configEntity.put(section, sectionEntity);
        var configFile =
            SystemVariableResolver.resolveSystemVariables(
                "${YOUTRACKDB_HOME}/config/security.json");

        var ssf = GlobalConfiguration.SERVER_SECURITY_FILE.getValueAsString();
        if (ssf != null) {
          configFile = ssf;
        }

        var f = new File(configFile);
        IOUtils.writeFile(f, RecordSerializerJackson.mapToJson(configEntity));
      }
    } catch (Exception ex) {
      configEntity.put(section, oldSection);
      LogManager.instance().error(this, "DefaultServerSecurity.setSection(%s)", ex, section);
    }
  }

  // "${YOUTRACKDB_HOME}/config/security.json"
  private Map<String, Object> loadConfig(final String cfgPath) {
    Map<String, Object> securityEntity = null;

    try {
      if (cfgPath != null) {
        // Default
        var jsonFile = SystemVariableResolver.resolveSystemVariables(cfgPath);

        var file = new File(jsonFile);

        if (file.exists() && file.canRead()) {

          try (FileInputStream fis = new FileInputStream(file)) {
            final var buffer = new byte[(int) file.length()];
            fis.read(buffer);

            securityEntity = RecordSerializerJackson.mapFromJson(new String(buffer));
          }
        } else {
          if (file.exists()) {
            LogManager.instance()
                .warn(this, "Could not read the security JSON file: %s", null, jsonFile);
          } else {
            if (file.exists()) {
              LogManager.instance()
                  .warn(this, "Security JSON file: %s do not exists", null, jsonFile);
            }
          }
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "error loading security config", ex);
    }

    return securityEntity;
  }

  private boolean isEnabled(final Map<String, Object> sectionEntity) {
    var enabled = true;

    try {
      if (sectionEntity.containsKey("enabled")) {
        enabled = (Boolean) sectionEntity.get("enabled");
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.isEnabled()", ex);
    }

    return enabled;
  }

  private void loadSecurity() {
    try {
      enabled = false;

      if (configEntity != null) {
        if (configEntity.containsKey("enabled")) {
          enabled = (Boolean) configEntity.get("enabled");
        }

        if (configEntity.containsKey("debug")) {
          debug = (Boolean) configEntity.get("debug");
        }
      } else {
        LogManager.instance()
            .debug(this, "DefaultServerSecurity.loadSecurity() jsonConfig is null");
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.loadSecurity()", ex);
    }
  }

  private void reloadServer() {
    try {
      storePasswords = true;

      if (serverEntity != null) {
        if (serverEntity.containsKey("createDefaultUsers")) {
          GlobalConfiguration.CREATE_DEFAULT_USERS.setValue(
              serverEntity.get("createDefaultUsers"));
        }

        if (serverEntity.containsKey("storePasswords")) {
          storePasswords = (Boolean) serverEntity.get("storePasswords");
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.loadServer()", ex);
    }
  }

  private void reloadAuthMethods(DatabaseSessionInternal session) {
    if (authEntity != null) {
      if (authEntity.containsKey("allowDefault")) {
        allowDefault = (Boolean) authEntity.get("allowDefault");
      }

      loadAuthenticators(session, authEntity);
    }
  }

  private void reloadPasswordValidator(DatabaseSessionInternal session) {
    try {
      synchronized (passwordValidatorSynch) {
        if (passwdValEntity != null && isEnabled(passwdValEntity)) {

          if (passwordValidator != null) {
            passwordValidator.dispose();
            passwordValidator = null;
          }

          var cls = getClass(passwdValEntity);

          if (cls != null) {
            if (PasswordValidator.class.isAssignableFrom(cls)) {
              passwordValidator = (PasswordValidator) cls.newInstance();
              passwordValidator.config(session, passwdValEntity, this);
              passwordValidator.active();
            } else {
              LogManager.instance()
                  .error(
                      this,
                      "DefaultServerSecurity.reloadPasswordValidator() class is not an"
                          + " PasswordValidator",
                      null);
            }
          } else {
            LogManager.instance()
                .error(
                    this,
                    "DefaultServerSecurity.reloadPasswordValidator() PasswordValidator class"
                        + " property is missing",
                    null);
          }
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.reloadPasswordValidator()", ex);
    }
  }

  private void reloadImportLDAP(DatabaseSessionInternal session) {
    try {
      synchronized (importLDAPSynch) {
        if (importLDAP != null) {
          importLDAP.dispose();
          importLDAP = null;
        }

        if (ldapImportEntity != null && isEnabled(ldapImportEntity)) {
          var cls = getClass(ldapImportEntity);

          if (cls != null) {
            if (SecurityComponent.class.isAssignableFrom(cls)) {
              importLDAP = (SecurityComponent) cls.newInstance();
              importLDAP.config(session, ldapImportEntity, this);
              importLDAP.active();
            } else {
              LogManager.instance()
                  .error(
                      this,
                      "DefaultServerSecurity.reloadImportLDAP() class is not an"
                          + " SecurityComponent",
                      null);
            }
          } else {
            LogManager.instance()
                .error(
                    this,
                    "DefaultServerSecurity.reloadImportLDAP() ImportLDAP class property is"
                        + " missing",
                    null);
          }
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.reloadImportLDAP()", ex);
    }
  }

  private void reloadAuditingService(DatabaseSessionInternal session) {
    try {
      synchronized (auditingSynch) {
        if (auditingService != null) {
          auditingService.dispose();
          auditingService = null;
        }

        if (auditingEntity != null && isEnabled(auditingEntity)) {
          var cls = getClass(auditingEntity);

          if (cls != null) {
            if (AuditingService.class.isAssignableFrom(cls)) {
              auditingService = (AuditingService) cls.newInstance();
              auditingService.config(session, auditingEntity, this);
              auditingService.active();
            } else {
              LogManager.instance()
                  .error(
                      this,
                      "DefaultServerSecurity.reloadAuditingService() class is not an"
                          + " AuditingService",
                      null);
            }
          } else {
            LogManager.instance()
                .error(
                    this,
                    "DefaultServerSecurity.reloadAuditingService() Auditing class property is"
                        + " missing",
                    null);
          }
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultServerSecurity.reloadAuditingService()", ex);
    }
  }

  public void close() {
    if (enabled) {

      synchronized (importLDAPSynch) {
        if (importLDAP != null) {
          importLDAP.dispose();
          importLDAP = null;
        }
      }

      synchronized (passwordValidatorSynch) {
        if (passwordValidator != null) {
          passwordValidator.dispose();
          passwordValidator = null;
        }
      }

      synchronized (auditingSynch) {
        if (auditingService != null) {
          auditingService.dispose();
          auditingService = null;
        }
      }

      setAuthenticatorList(Collections.emptyList());

      enabled = false;
    }
  }

  @Override
  public SecurityUser authenticateAndAuthorize(
      DatabaseSessionInternal session, String iUserName, String iPassword,
      String iResourceToCheck) {
    // Returns the authenticated username, if successful, otherwise null.
    var user = authenticate(null, iUserName, iPassword);

    // Authenticated, now see if the user is authorized.
    if (user != null) {
      if (isAuthorized(session, user.getName(session), iResourceToCheck)) {
        return user;
      }
    }
    return null;
  }

  public boolean existsUser(String user) {
    return configUsers.containsKey(user);
  }

  public void addTemporaryUser(String iName, String iPassword, String iPermissions) {
    var userCfg = new TemporaryGlobalUser(iName, iPassword, iPermissions);
    ephemeralUsers.put(iName, userCfg);
  }

  @Override
  public SecurityInternal newSecurity(String database) {
    return new SecurityShared(this);
  }

  public synchronized void setAuthenticatorList(List<SecurityAuthenticator> authenticators) {
    if (authenticatorsList != null) {
      for (var sa : authenticatorsList) {
        sa.dispose();
      }
    }
    this.authenticatorsList = Collections.unmodifiableList(authenticators);
    this.enabledAuthenticators =
        authenticators.stream().filter(SecurityComponent::isEnabled)
            .toList();
  }

  public synchronized List<SecurityAuthenticator> getEnabledAuthenticators() {
    return enabledAuthenticators;
  }

  public synchronized List<SecurityAuthenticator> getAuthenticatorsList() {
    return authenticatorsList;
  }

  public TokenSign getTokenSign() {
    return tokenSign;
  }
}
