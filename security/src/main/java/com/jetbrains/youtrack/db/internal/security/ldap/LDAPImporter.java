/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrack.db.internal.security.ldap;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrack.db.internal.core.security.SecurityAuthenticator;
import com.jetbrains.youtrack.db.internal.core.security.SecurityComponent;
import com.jetbrains.youtrack.db.internal.core.security.SecuritySystem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.Subject;

/**
 * Provides an LDAP importer.
 */
public class LDAPImporter implements SecurityComponent {

  private final String oldapUserClass = "_OLDAPUser";

  private boolean debug = false;
  private boolean enabled = true;

  private YouTrackDBInternalEmbedded context;

  private int importPeriod = 60; // Default to 60
  // seconds.
  private Timer importTimer;

  // Used to track what roles are assigned to each database user.
  // Holds a map of the import databases and their corresponding dbGroups.
  private final ConcurrentHashMap<String, Database> databaseMap =
      new ConcurrentHashMap<String, Database>();

  private SecuritySystem security;

  // SecurityComponent
  @Override
  public void active() {
    // Go through each database entry and check the _OLDAPUsers schema.
    for (var dbEntry : databaseMap.entrySet()) {
      var db = dbEntry.getValue();

      try (var odb = context.openNoAuthenticate(db.getName(), "internal")) {
        verifySchema(odb);
      } catch (Exception ex) {
        LogManager.instance().error(this, "LDAPImporter.active() Database: %s", ex, db.getName());
      }
    }

    var importTask = new ImportTask();
    importTimer = new Timer(true);
    importTimer.scheduleAtFixedRate(
        importTask, 30000, importPeriod * 1000L); // Wait 30 seconds before starting

    LogManager.instance().info(this, "**************************************");
    LogManager.instance().info(this, "** YouTrackDB LDAP Importer Is Active **");
    LogManager.instance().info(this, "**************************************");
  }

  // SecurityComponent
  @Override
  public void config(DatabaseSessionEmbedded session, final Map<String, Object> importDoc,
      SecuritySystem security) {
    try {
      context = security.getContext();
      this.security = security;

      databaseMap.clear();

      if (importDoc.containsKey("debug")) {
        debug = (Boolean) importDoc.get("debug");
      }

      if (importDoc.containsKey("enabled")) {
        enabled = (Boolean) importDoc.get("enabled");
      }

      if (importDoc.containsKey("period")) {
        importPeriod = (Integer) importDoc.get("period");

        if (debug) {
          LogManager.instance().info(this, "Import Period = " + importPeriod);
        }
      }

      if (importDoc.containsKey("databases")) {
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) importDoc.get("databases");

        for (var dbDoc : list) {
          if (dbDoc.containsKey("database")) {
            var dbName = dbDoc.get("database").toString();
            if (debug) {
              LogManager.instance().info(this, "config() database: %s", dbName);
            }

            var ignoreLocal = true;

            if (dbDoc.containsKey("ignoreLocal")) {
              ignoreLocal = (Boolean) dbDoc.get("ignoreLocal");
            }

            if (dbDoc.containsKey("domains")) {
              final List<DatabaseDomain> dbDomainsList = new ArrayList<DatabaseDomain>();

              @SuppressWarnings("unchecked")
              var dbdList = (List<Map<String, Object>>) dbDoc.get("domains");

              for (var dbDomainDoc : dbdList) {
                String domain;

                // "domain" is mandatory.
                if (dbDomainDoc.containsKey("domain")) {
                  domain = dbDomainDoc.get("domain").toString();

                  // If authenticator is null, it defaults to LDAPImporter's primary
                  // SecurityAuthenticator.
                  String authenticator = null;

                  if (dbDomainDoc.containsKey("authenticator")) {
                    authenticator = dbDomainDoc.get("authenticator").toString();
                  }

                  if (dbDomainDoc.containsKey("servers")) {
                    final List<LDAPServer> ldapServerList = new ArrayList<LDAPServer>();

                    @SuppressWarnings("unchecked") final var ldapServers = (List<Map<String, Object>>) dbDomainDoc.get(
                        "servers");

                    for (var ldapServerDoc : ldapServers) {
                      final var url = (String) ldapServerDoc.get("url");

                      var isAlias = false;

                      if (ldapServerDoc.containsKey("isAlias")) {
                        isAlias = (Boolean) ldapServerDoc.get("isAlias");
                      }

                      var server = LDAPServer.validateURL(url, isAlias);

                      if (server != null) {
                        ldapServerList.add(server);
                      } else {
                        LogManager.instance()
                            .error(
                                this,
                                "Import LDAP Invalid server URL for database: %s, domain: %s, URL:"
                                    + " %s",
                                null,
                                dbName,
                                domain,
                                url);
                      }
                    }

                    //
                    final List<User> userList = new ArrayList<User>();

                    @SuppressWarnings("unchecked") final var userDocList = (List<Map<String, Object>>) dbDomainDoc.get(
                        "users");

                    // userDocList can be null if only the oldapUserClass is used instead
                    // security.json.
                    if (userDocList != null) {
                      for (var userDoc : userDocList) {
                        if (userDoc.containsKey("baseDN") && userDoc.containsKey("filter")) {
                          if (userDoc.containsKey("roles")) {
                            final var baseDN = userDoc.get("baseDN").toString();
                            final var filter = userDoc.get("filter").toString();

                            if (debug) {
                              LogManager.instance()
                                  .info(
                                      this,
                                      "config() database: %s, baseDN: %s, filter: %s",
                                      dbName,
                                      baseDN,
                                      filter);
                            }

                            @SuppressWarnings("unchecked") final var roleList = (List<String>) userDoc.get(
                                "roles");
                            final var User = new User(baseDN, filter, roleList);

                            userList.add(User);
                          } else {
                            LogManager.instance()
                                .error(
                                    this,
                                    "Import LDAP The User's \"roles\" property is missing for"
                                        + " database %s",
                                    null);
                          }
                        } else {
                          LogManager.instance()
                              .error(
                                  this,
                                  "Import LDAP The User's \"baseDN\" or \"filter\" property is"
                                      + " missing for database %s",
                                  null);
                        }
                      }
                    }

                    var dbd =
                        new DatabaseDomain(domain, ldapServerList, userList, authenticator);

                    dbDomainsList.add(dbd);
                  } else {
                    LogManager.instance()
                        .error(
                            this,
                            "Import LDAP database %s \"domain\" is missing its \"servers\""
                                + " property",
                            null);
                  }
                } else {
                  LogManager.instance()
                      .error(
                          this,
                          "Import LDAP database %s \"domain\" object is missing its \"domain\""
                              + " property",
                          null);
                }
              }

              if (dbName != null) {
                var db = new Database(dbName, ignoreLocal, dbDomainsList);
                databaseMap.put(dbName, db);
              }
            } else {
              LogManager.instance()
                  .error(this, "Import LDAP database %s contains no \"domains\" property", null);
            }
          } else {
            LogManager.instance()
                .error(this, "Import LDAP databases contains no \"database\" property", null);
          }
        }
      } else {
        LogManager.instance().error(this, "Import LDAP contains no \"databases\" property", null);
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "LDAPImporter.config()", ex);
    }
  }

  // SecurityComponent
  @Override
  public void dispose() {
    if (importTimer != null) {
      importTimer.cancel();
      importTimer = null;
    }
  }

  // SecurityComponent
  @Override
  public boolean isEnabled() {
    return enabled;
  }

  private void verifySchema(DatabaseSessionInternal odb) {
    try {
      System.out.println("calling existsClass odb = " + odb);

      if (!odb.getMetadata().getSchema().existsClass(oldapUserClass)) {
        System.out.println("calling createClass");

        var ldapUser = odb.getMetadata().getSchema().createClass(oldapUserClass);

        System.out.println("calling createProperty");

        var prop = ldapUser.createProperty("Domain", PropertyType.STRING);

        System.out.println("calling setMandatory");

        prop.setMandatory(true);
        prop.setNotNull(true);

        prop = ldapUser.createProperty("BaseDN", PropertyType.STRING);
        prop.setMandatory(true);
        prop.setNotNull(true);

        prop = ldapUser.createProperty("Filter", PropertyType.STRING);
        prop.setMandatory(true);
        prop.setNotNull(true);

        prop = ldapUser.createProperty("Roles", PropertyType.STRING);
        prop.setMandatory(true);
        prop.setNotNull(true);
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "LDAPImporter.verifySchema()", ex);
    }
  }

  private static class Database {

    private final String name;

    public String getName() {
      return name;
    }

    private final boolean ignoreLocal;

    public boolean ignoreLocal() {
      return ignoreLocal;
    }

    private final List<DatabaseDomain> databaseDomains;

    public List<DatabaseDomain> getDatabaseDomains() {
      return databaseDomains;
    }

    public Database(
        final String name, final boolean ignoreLocal, final List<DatabaseDomain> dbDomains) {
      this.name = name;
      this.ignoreLocal = ignoreLocal;
      databaseDomains = dbDomains;
    }
  }

  private static class DatabaseDomain {

    private final String domain;

    public String getDomain() {
      return domain;
    }

    private final String authenticator;
    private final List<LDAPServer> ldapServers;
    private final List<User> users;

    public String getAuthenticator() {
      return authenticator;
    }

    public List<LDAPServer> getLDAPServers() {
      return ldapServers;
    }

    public List<User> getUsers() {
      return users;
    }

    public DatabaseDomain(
        final String domain,
        final List<LDAPServer> ldapServers,
        final List<User> userList,
        String authenticator) {
      this.domain = domain;
      this.ldapServers = ldapServers;
      users = userList;
      this.authenticator = authenticator;
    }
  }

  private static class DatabaseUser {

    private final String user;
    private final Set<String> roles = new LinkedHashSet<String>();

    private String getUser() {
      return user;
    }

    public Set<String> getRoles() {
      return roles;
    }

    public void addRoles(Set<String> roles) {
      if (roles != null) {
        this.roles.addAll(roles);
      }
    }

    public DatabaseUser(final String user) {
      this.user = user;
    }
  }

  private static class User {

    private final String baseDN;
    private final String filter;
    private final Set<String> roles = new LinkedHashSet<String>();

    public String getBaseDN() {
      return baseDN;
    }

    public String getFilter() {
      return filter;
    }

    public Set<String> getRoles() {
      return roles;
    }

    public User(final String baseDN, final String filter, final List<String> roleList) {
      this.baseDN = baseDN;
      this.filter = filter;

      // Convert the list into a set, for convenience.
      roles.addAll(roleList);
    }
  }

  // OSecuritySystemAccess
  public Subject getLDAPSubject(final String authName) {
    Subject subject = null;

    SecurityAuthenticator authMethod = null;

    // If authName is null, use the primary authentication method.
    if (authName == null) {
      authMethod = security.getPrimaryAuthenticator();
    } else {
      authMethod = security.getAuthenticator(authName);
    }

    if (authMethod != null) {
      subject = authMethod.getClientSubject();
    }

    return subject;
  }

  /**
   * LDAP Import *
   */
  private synchronized void importLDAP() {
    if (security == null) {
      LogManager.instance().error(this, "LDAPImporter.importLDAP() ServerSecurity is null", null);
      return;
    }

    if (debug) {
      LogManager.instance().info(this, "LDAPImporter.importLDAP() \n");
    }

    for (var dbEntry : databaseMap.entrySet()) {
      try {
        var db = dbEntry.getValue();

        var odb = context.openNoAuthenticate(db.getName(), "internal");

        // This set will be filled with all users from the database (unless ignoreLocal is true).
        // As each usersRetrieved list is filled, any matching user will be removed.
        // Once all the DatabaseGroups have been procesed, any remaining users in the set will be
        // deleted from the Database.
        Set<String> usersToBeDeleted = new LinkedHashSet<String>();

        Map<String, DatabaseUser> usersMap = new ConcurrentHashMap<String, DatabaseUser>();

        try {
          // We use this flag to determine whether to proceed with the call to deleteUsers() below.
          // If one or more LDAP servers cannot be reached (perhaps temporarily), we don't want to
          // delete all the database users, locking everyone out until the LDAP server is available
          // again.
          var deleteUsers = false;

          // Retrieves all the current YouTrackDB users from the specified ODatabase and stores them
          // in usersToBeDeleted.
          retrieveAllUsers(odb, db.ignoreLocal(), usersToBeDeleted);

          for (var dd : db.getDatabaseDomains()) {
            try {
              var ldapSubject = getLDAPSubject(dd.getAuthenticator());

              if (ldapSubject != null) {
                var dc = LDAPLibrary.openContext(ldapSubject, dd.getLDAPServers(), debug);

                if (dc != null) {
                  deleteUsers = true;

                  try {
                    // Combine the "users" from security.json's "ldapImporter" and the class
                    // oldapUserClass.
                    List<User> userList = new ArrayList<User>(dd.getUsers());

                    retrieveLDAPUsers(odb, dd.getDomain(), userList);

                    for (var user : userList) {
                      List<String> usersRetrieved = new ArrayList<String>();

                      LogManager.instance()
                          .info(
                              this,
                              "LDAPImporter.importLDAP() Calling retrieveUsers for Database: %s,"
                                  + " Filter: %s",
                              db.getName(),
                              user.getFilter());

                      LDAPLibrary.retrieveUsers(
                          dc, user.getBaseDN(), user.getFilter(), usersRetrieved, debug);

                      if (!usersRetrieved.isEmpty()) {
                        for (var upn : usersRetrieved) {
                          usersToBeDeleted.remove(upn);

                          LogManager.instance()
                              .info(
                                  this,
                                  "LDAPImporter.importLDAP() Database: %s, Filter: %s, UPN: %s",
                                  db.getName(),
                                  user.getFilter(),
                                  upn);

                          DatabaseUser dbUser = null;

                          if (usersMap.containsKey(upn)) {
                            dbUser = usersMap.get(upn);
                          } else {
                            dbUser = new DatabaseUser(upn);
                            usersMap.put(upn, dbUser);
                          }

                          if (dbUser != null) {
                            dbUser.addRoles(user.getRoles());
                          }
                        }
                      } else {
                        LogManager.instance()
                            .info(
                                this,
                                "LDAPImporter.importLDAP() No users found at BaseDN: %s, Filter:"
                                    + " %s, for Database: %s",
                                user.getBaseDN(),
                                user.getFilter(),
                                db.getName());
                      }
                    }
                  } finally {
                    dc.close();
                  }
                } else {
                  LogManager.instance()
                      .error(
                          this,
                          "LDAPImporter.importLDAP() Could not obtain an LDAP DirContext for"
                              + " Database %s",
                          null,
                          db.getName());
                }
              } else {
                LogManager.instance()
                    .error(
                        this,
                        "LDAPImporter.importLDAP() Could not obtain an LDAP Subject for Database"
                            + " %s",
                        null,
                        db.getName());
              }
            } catch (Exception ex) {
              LogManager.instance()
                  .error(this, "LDAPImporter.importLDAP() Database: %s", ex, db.getName());
            }
          }

          // Imports the LDAP users into the specified database, if it exists.
          importUsers(odb, usersMap);

          if (deleteUsers) {
            deleteUsers(odb, usersToBeDeleted);
          }
        } finally {
          usersMap.clear();
          usersToBeDeleted.clear();

          if (odb != null) {
            odb.close();
          }
        }
      } catch (Exception ex) {
        LogManager.instance().error(this, "LDAPImporter.importLDAP()", ex);
      }
    }
  }

  // Loads the User object from the oldapUserClass class for each domain.
  // This is equivalent to the "users" objects in "ldapImporter" of security.json.
  private void retrieveLDAPUsers(
      final DatabaseSession session, final String domain, final List<User> userList) {
    session.executeInTx(transaction -> {
      try {
        var sql = String.format("SELECT FROM `%s` WHERE Domain = ?", oldapUserClass);

        try (var users = transaction.query(sql, domain)) {
          while (users.hasNext()) {
            var userDoc = users.next();
            String roles = userDoc.getProperty("Roles");

            if (roles != null) {
              List<String> roleList = new ArrayList<String>();

              var roleArray = roles.split(",");

              for (var role : roleArray) {
                roleList.add(role.trim());
              }

              var user =
                  new User(userDoc.getProperty("BaseDN"), userDoc.getProperty("Filter"), roleList);
              userList.add(user);
            } else {
              LogManager.instance()
                  .error(
                      this,
                      "LDAPImporter.retrieveLDAPUsers() Roles is missing for entry Database: %s,"
                          + " Domain: %s",
                      null,
                      session.getDatabaseName(),
                      domain);
            }
          }
        }
      } catch (Exception ex) {
        LogManager.instance()
            .error(
                this,
                "LDAPImporter.retrieveLDAPUsers() Database: %s, Domain: %s",
                ex,
                session.getDatabaseName(),
                domain);
      }
    });
  }

  private void retrieveAllUsers(
      final DatabaseSession odb, final boolean ignoreLocal, final Set<String> usersToBeDeleted) {
    odb.executeInTx(transaction -> {
      try {
        var sql = "SELECT FROM OUser";

        if (ignoreLocal) {
          sql = "SELECT FROM OUser WHERE _externalUser = true";
        }
        try (var users = transaction.query(sql)) {

          while (users.hasNext()) {
            var user = users.next();
            String name = user.getProperty("name");

            if (name != null) {
              if (!(name.equals("admin") || name.equals("reader") || name.equals("writer"))) {
                usersToBeDeleted.add(name);

                LogManager.instance()
                    .info(
                        this,
                        "LDAPImporter.retrieveAllUsers() Database: %s, User: %s",
                        odb.getDatabaseName(),
                        name);
              }
            }
          }
        }
      } catch (Exception ex) {
        LogManager.instance()
            .error(this, "LDAPImporter.retrieveAllUsers() Database: %s", ex, odb.getDatabaseName());
      }
    });
  }

  private void deleteUsers(final DatabaseSession session, final Set<String> usersToBeDeleted) {
    session.executeInTx(transaction -> {
      try {
        for (var user : usersToBeDeleted) {
          transaction.command("DELETE FROM OUser WHERE name = ?", user);

          LogManager.instance()
              .info(
                  this,
                  "LDAPImporter.deleteUsers() Deleted User: %s from Database: %s",
                  user,
                  session.getDatabaseName());
        }
      } catch (Exception ex) {
        LogManager.instance()
            .error(this, "LDAPImporter.deleteUsers() Database: %s", ex, session.getDatabaseName());
      }
    });
  }

  private void importUsers(final DatabaseSession odb, final Map<String, DatabaseUser> usersMap) {
    try {
      for (var entry : usersMap.entrySet()) {
        var upn = entry.getKey();

        if (upsertDbUser(odb, upn, entry.getValue().getRoles())) {
          LogManager.instance()
              .info(this, "Added/Modified Database User %s in Database %s", upn,
                  odb.getDatabaseName());
        } else {
          LogManager.instance()
              .error(
                  this,
                  "Failed to add/update Database User %s in Database %s",
                  null,
                  upn,
                  odb.getDatabaseName());
        }
      }
    } catch (Exception ex) {
      LogManager.instance()
          .error(this, "LDAPImporter.importUsers() Database: %s", ex, odb.getDatabaseName());
    }
  }

  /*
   * private boolean dbUserExists(ODatabase<?> db, String upn) { try { List<EntityImpl> list = new SQLSynchQuery<EntityImpl>(
   * "SELECT FROM OUser WHERE name = ?").run(upn);
   *
   * return !list.isEmpty(); } catch(Exception ex) { LogManager.instance().debug(this, "dbUserExists() Exception: ", ex); }
   *
   * return true; // Better to not add a user than to overwrite one. }
   */
  private boolean upsertDbUser(DatabaseSession db, String upn, Set<String> roles) {
    try {
      // Create a random password to set for each imported user in case allowDefault is set to true.
      // We don't want blank or simple passwords set on the imported users, just in case.
      // final String password = SecurityManager.instance().createSHA256(String.valueOf(new
      // java.util.Random().nextLong()));

      final var password = UUID.randomUUID().toString();

      var sb = new StringBuilder();
      sb.append(
          "UPDATE OUser SET name = ?, password = ?, status = \"ACTIVE\", _externalUser = true,"
              + " roles = (SELECT FROM ORole WHERE name in [");

      var roleParams = new String[roles.size()];

      var it = roles.iterator();

      var cnt = 0;

      while (it.hasNext()) {
        var role = it.next();

        sb.append("'");
        sb.append(role);
        sb.append("'");

        if (it.hasNext()) {
          sb.append(", ");
        }

        roleParams[cnt] = role;

        cnt++;
      }

      sb.append("]) UPSERT WHERE name = ?");

      db.executeInTx(transaction -> {
        transaction.command(sb.toString(), upn, password, upn);
      });

      return true;
    } catch (Exception ex) {
      LogManager.instance().error(this, "LDAPImporter.upsertDbUser()", ex);
    }

    return false;
  }

  private class ImportTask extends TimerTask {

    @Override
    public void run() {
      importLDAP();
    }
  }
}
