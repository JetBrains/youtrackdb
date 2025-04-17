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
package com.jetbrains.youtrack.db.internal.security.kerberos;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.ImmutableUser;
import com.jetbrains.youtrack.db.internal.core.security.SecuritySystem;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.core.security.authenticator.SecurityAuthenticatorAbstract;
import com.jetbrains.youtrack.db.internal.core.security.kerberos.Krb5ClientLoginModuleConfig;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.security.SecurityAuthenticatorException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.Subject;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Temporary, for Java 7 support.
// import sun.misc.BASE64Decoder;

/**
 * Implements the Kerberos authenticator module.
 */
public class KerberosAuthenticator extends SecurityAuthenticatorAbstract {

  private static final Logger logger = LoggerFactory.getLogger(KerberosAuthenticator.class);

  private final String kerberosPluginVersion = "0.15";
  private final long ticketRelayExpiration = 600000L; // 10 minutes, do not change
  private final ConcurrentHashMap<String, TicketItem> ticketRelayMap =
      new ConcurrentHashMap<String, TicketItem>();
  private String clientCCName = System.getenv("KRB5CCNAME");
  private String clientKTName = System.getenv("KRB5_CLIENT_KTNAME");
  private String clientPrincipal;
  private boolean clientUseTicketCache = false;
  private int clientPeriod = 300; // Default to 5 hours (300 minutes).
  private Timer renewalTimer; // Timer used to renew the LDAP client service ticket.
  private String krb5Config = System.getenv("KRB5_CONFIG");
  private String serviceKTName = System.getenv("KRB5_KTNAME");
  private String servicePrincipal;
  private String spnegoKTName = System.getenv("KRB5_KTNAME");
  private String spnegoPrincipal;
  private final Object authenticateSync = new Object();
  private Subject clientSubject; // Used in dbImport() for communicating with LDAP.
  private Subject
      serviceSubject; // Used in authenticate() for decrypting service tickets from binary clients.
  private Subject
      spnegoSubject; // Used in authenticate() for decrypting service tickets from REST clients.
  private Timer expirationTimer;

  /**
   * SecurityAuthenticator Interface *
   */
  // Called once the Server is running.
  public void active() {
    var task = new ExpirationTask();
    expirationTimer = new Timer(true);
    expirationTimer.scheduleAtFixedRate(
        task, 30000, ticketRelayExpiration); // Wait 30 seconds before starting

    var renewalTask = new RenewalTask();
    renewalTimer = new Timer(true);
    renewalTimer.scheduleAtFixedRate(
        renewalTask,
        (long) clientPeriod * 1000 * 60,
        (long) clientPeriod * 1000 * 60); // Wait 30 seconds before starting

    LogManager.instance()
        .debug(this,
            "YouTrackDB Kerberos Authenticator Is Active Version: " + kerberosPluginVersion,
            logger);
  }

  // SecurityAuthenticator
  // Kerberos magic happens here.
  public SecurityUser authenticate(
      DatabaseSessionInternal session, final String username, final String password) {
    // username will contain either the principal or be null.
    // password will contain either a Kerberos 5 service ticket or a SPNEGO ticket.
    String principal = null;

    try {
      if (isDebug()) {
        LogManager.instance().info(this, "** Authenticating username: %s", username);

        if (KerberosLibrary.isServiceTicket(password)) {
          LogManager.instance().info(this, "** Authenticating password: SERVICE TICKET");
        } else {
          LogManager.instance().info(this, "** Authenticating password: %s", password);
        }
      }

      if (password != null) {
        if (KerberosLibrary.isServiceTicket(password)) {
          // We can't call KerberosLibrary.authenticate() twice with the same service ticket.
          // If we do, the call to context.acceptSecContext() will think it's a replay attack.
          // YouTrackDBServer.openDatabase() will end up calling this method twice if its call to
          // database.open() fails.
          // So, we store the hash of the service ticket and the principal retrieved from the
          // service ticket in an TicketItem,
          // and we use a HashMap to store the ticket for five minutes.
          // If this authenticate() is called more than once, we retrieve the TicketItem for the
          // username, and we compare
          // the service ticket's hash code.  If they match, we return the principal.

          var ti = getTicket(Integer.toString(password.hashCode()));

          if (ti != null && ti.getHashCode() == password.hashCode()) {
            if (isDebug()) {
              LogManager.instance()
                  .info(
                      this,
                      "KerberosAuthenticator.authenticate() TicketHash and password Hash are"
                          + " equal, return principal: "
                          + ti.getPrincipal());
            }
            if (isDebug()) {
              LogManager.instance()
                  .info(
                      this,
                      "KerberosAuthenticator.authenticate() principal: " + ti.getPrincipal());
            }

            principal = ti.getPrincipal();
          } else {
            var ticket = Base64.getDecoder().decode(password.getBytes(StandardCharsets.UTF_8));

            // Temporary, for Java 7 support.
            //						byte[] ticket = new BASE64Decoder().decodeBuffer(password);

            //						byte [] ticket = java.util.Base64.getDecoder().decode(password);

            //						principal = KerberosLibrary.authenticate(serviceSubject, servicePrincipal,
            // username, ticket);

            try {
              synchronized (authenticateSync) {
                if (KerberosLibrary.isSPNegoTicket(ticket)) {
                  principal =
                      KerberosLibrary.getSPNegoSource(spnegoSubject, spnegoPrincipal, ticket);
                } else {
                  principal =
                      KerberosLibrary.getKerberosSource(serviceSubject, servicePrincipal, ticket);
                }
              }
            } catch (Exception e) {
              LogManager.instance()
                  .error(this, "KerberosAuthenticator.authenticate() Exception: ", e);
            }

            if (isDebug()) {
              LogManager.instance()
                  .info(
                      this,
                      "KerberosAuthenticator.authenticate() KerberosLibrary.authenticate()"
                          + " returned "
                          + principal);
            }

            //							LogManager.instance().info(this, "KerberosAuthenticator.authenticate()
            // addTicket hashCode: " + password.hashCode());

            // null is an acceptable principal to store so that subsequent calls using the same
            // ticket will immediately return null
            addTicket(Integer.toString(password.hashCode()), password.hashCode(), principal);
          }
        }
      }
    } catch (Exception ex) {
      LogManager.instance().debug(this, "KerberosAuthenticator.authenticate() Exception: ", logger,
          ex);
    }

    return new ImmutableUser(session, principal,
        SecurityUser.SERVER_USER_TYPE);
  }

  // SecurityAuthenticator
  public void config(DatabaseSessionInternal session, final Map<String, Object> kerbConfig,
      SecuritySystem security) {
    super.config(session, kerbConfig, security);

    if (kerbConfig.containsKey("krb5_config")) {
      krb5Config = SystemVariableResolver.resolveSystemVariables(
          kerbConfig.get("krb5_config").toString());
      LogManager.instance().info(this, "Krb5Config = " + krb5Config);
    }

    // service
    if (kerbConfig.containsKey("service")) {
      @SuppressWarnings("unchecked")
      var serviceDoc = (Map<String, Object>) kerbConfig.get("service");

      if (serviceDoc.containsKey("ktname")) {
        serviceKTName = SystemVariableResolver.resolveSystemVariables(
            serviceDoc.get("ktname").toString());
        LogManager.instance().info(this, "Svc ktname = " + serviceKTName);
      }

      if (serviceDoc.containsKey("principal")) {
        servicePrincipal = serviceDoc.get("principal").toString();
        LogManager.instance().info(this, "Svc princ = " + servicePrincipal);
      }
    }

    // SPNEGO
    if (kerbConfig.containsKey("spnego")) {
      @SuppressWarnings("unchecked")
      var spnegoDoc = (Map<String, Object>) kerbConfig.get("spnego");

      if (spnegoDoc.containsKey("ktname")) {
        spnegoKTName = SystemVariableResolver.resolveSystemVariables(
            spnegoDoc.get("ktname").toString());
        LogManager.instance().info(this, "SPNEGO ktname = " + spnegoKTName);
      }

      if (spnegoDoc.containsKey("principal")) {
        spnegoPrincipal = spnegoDoc.get("principal").toString();
        LogManager.instance().info(this, "SPNEGO princ = " + spnegoPrincipal);
      }
    }

    // client
    if (kerbConfig.containsKey("client")) {
      @SuppressWarnings("unchecked")
      var clientDoc = (Map<String, Object>) kerbConfig.get("client");

      if (clientDoc.containsKey("useTicketCache")) {
        clientUseTicketCache = (Boolean) clientDoc.get("useTicketCache");
        LogManager.instance().info(this, "Client useTicketCache = " + clientUseTicketCache);
      }

      if (clientDoc.containsKey("principal")) {
        clientPrincipal = clientDoc.get("principal").toString();
        LogManager.instance().info(this, "Client princ = " + clientPrincipal);
      }

      if (clientDoc.containsKey("ccname")) {
        clientCCName = SystemVariableResolver.resolveSystemVariables(
            clientDoc.get("ccname").toString());
        LogManager.instance().info(this, "Client ccname = " + clientCCName);
      }

      if (clientDoc.containsKey("ktname")) {
        clientKTName = SystemVariableResolver.resolveSystemVariables(
            clientDoc.get("ktname").toString());
        LogManager.instance().info(this, "Client ktname = " + clientKTName);
      }

      if (clientDoc.containsKey("renewalPeriod")) {
        clientPeriod = (Integer) clientDoc.get("renewalPeriod");
      }
    }

    // Initialize Kerberos
    initializeKerberos();

    synchronized (authenticateSync) {
      createServiceSubject();
      createSpnegoSubject();
    }

    createClientSubject();
  }

  // SecurityAuthenticator
  // Called on removal of the authenticator.
  public void dispose() {
    if (expirationTimer != null) {
      expirationTimer.cancel();
      expirationTimer = null;
    }

    if (renewalTimer != null) {
      renewalTimer.cancel();
      renewalTimer = null;
    }

    synchronized (ticketRelayMap) {
      ticketRelayMap.clear();
    }
  }

  // SecurityAuthenticator
  public String getAuthenticationHeader(final String databaseName) {
    String header = null;

    // SPNEGO support.
    //		if(databaseName != null) header = "WWW-Authenticate: Negotiate realm=\"YouTrackDB db-" +
    // databaseName + "\"";
    //		else header = "WWW-Authenticate: Negotiate realm=\"YouTrackDB Server\"";

    header = HttpUtils.HEADER_AUTHENTICATE_NEGOTIATE; // "WWW-Authenticate: Negotiate";

    //		if(databaseName != null) header = "WWW-Authenticate: Negotiate\nWWW-Authenticate: Basic
    // realm=\"YouTrackDB db-" + databaseName + "\"";
    //		else header = "WWW-Authenticate: Negotiate\nWWW-Authenticate: Basic realm=\"YouTrackDB
    // Server\"";

    return header;
  }

  // SecurityAuthenticator
  public Subject getClientSubject() {
    return clientSubject;
  }

  // SecurityAuthenticator
  public boolean isSingleSignOnSupported() {
    return true;
  }

  /**
   * Kerberos *
   */
  private void initializeKerberos() {
    if (krb5Config == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator KRB5 Config cannot be null");
    }

    System.setProperty("sun.security.krb5.debug", Boolean.toString(isDebug()));
    System.setProperty("sun.security.spnego.debug", Boolean.toString(isDebug()));

    System.setProperty("java.security.krb5.conf", krb5Config);

    System.setProperty("javax.security.auth.useSubjectCredsOnly", "true");
  }

  private void createServiceSubject() {
    if (servicePrincipal == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator.createServiceSubject() Service Principal cannot be null");
    }
    if (serviceKTName == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator.createServiceSubject() Service KeyTab cannot be null");
    }

    try {
      Configuration cfg = new Krb5LoginModuleConfig(servicePrincipal, serviceKTName);

      LogManager.instance()
          .info(this, "createServiceSubject() Service Principal: " + servicePrincipal);

      var lc = new LoginContext("ignore", null, null, cfg);
      lc.login();

      serviceSubject = lc.getSubject();

      if (serviceSubject != null) {
        KerberosLibrary.checkNativeJGSS(serviceSubject, servicePrincipal, false);

        LogManager.instance().info(this, "** Created Kerberos Service Subject **");
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "createServiceSubject() Exception: ", ex);
    }

    if (serviceSubject == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator could not create service Subject");
    }
  }

  private void createSpnegoSubject() {
    if (spnegoPrincipal == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator.createSpnegoSubject() SPNEGO Principal cannot be null");
    }
    if (spnegoKTName == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator.createSpnegoSubject() SPNEGO KeyTab cannot be null");
    }

    try {
      Configuration cfg = new Krb5LoginModuleConfig(spnegoPrincipal, spnegoKTName);

      LogManager.instance()
          .info(this, "createSpnegoSubject() SPNEGO Principal: " + spnegoPrincipal);

      var lc = new LoginContext("ignore", null, null, cfg);
      lc.login();

      spnegoSubject = lc.getSubject();

      if (spnegoSubject != null) {
        KerberosLibrary.checkNativeJGSS(spnegoSubject, spnegoPrincipal, false);

        LogManager.instance().info(this, "** Created Kerberos SPNEGO Subject **");
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "createSpnegoSubject() Exception: ", ex);
    }

    if (spnegoSubject == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator could not create SPNEGO Subject");
    }
  }

  private void createClientSubject() {
    if (clientPrincipal == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator.createClientSubject() Client Principal cannot be null");
    }
    if (clientUseTicketCache && clientCCName == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator.createClientSubject() Client UseTicketCache cannot be true while"
              + " Credential Cache is null");
    }
    if (clientCCName == null && clientKTName == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator.createClientSubject() Client Credential Cache and Client KeyTab"
              + " cannot both be null");
    }

    try {
      Configuration cfg =
          new Krb5ClientLoginModuleConfig(
              clientPrincipal, clientUseTicketCache, clientCCName, clientKTName);

      LogManager.instance()
          .info(this, "createClientSubject() Client Principal: " + clientPrincipal);

      var lc = new LoginContext("ignore", null, null, cfg);
      lc.login();

      clientSubject = lc.getSubject();

      if (clientSubject != null) {
        KerberosLibrary.checkNativeJGSS(clientSubject, clientPrincipal, true);

        LogManager.instance().info(this, "** Created Kerberos Client Subject **");
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "createClientSubject() Exception: ", ex);
    }

    if (clientSubject == null) {
      throw new SecurityAuthenticatorException(
          "KerberosAuthenticator could not create client Subject");
    }
  }

  // If the TicketItem already exists for id it is replaced.
  private void addTicket(String id, int hashCode, String principal) {
    synchronized (ticketRelayMap) {
      ticketRelayMap.put(id, new TicketItem(hashCode, principal));
    }
  }

  private TicketItem getTicket(String id) {
    TicketItem ti = null;

    synchronized (ticketRelayMap) {
      ti = ticketRelayMap.get(id);
    }

    return ti;
  }

  private void removeTicket(String id) {
    synchronized (ticketRelayMap) {
      ticketRelayMap.remove(id);
    }
  }

  private void checkTicketExpirations() {
    synchronized (ticketRelayMap) {
      var currTime = System.currentTimeMillis();

      for (var entry : ticketRelayMap.entrySet()) {
        if (entry.getValue().hasExpired(currTime)) {
          //					LogManager.instance().info(this, "~~~~~~~~ checkTicketExpirations() Ticket has
          // expired: " + entry.getValue().getHashCode() + "\n");

          ticketRelayMap.remove(entry.getKey());
        }
      }
    }
  }

  /**
   * Ticket Cache *
   */
  private class TicketItem {

    private final int hashCode;
    private final String principal;
    private final long time;

    public TicketItem(int hashCode, String principal) {
      this.hashCode = hashCode;
      this.principal = principal;
      time = System.currentTimeMillis();
    }

    public int getHashCode() {
      return hashCode;
    }

    public String getPrincipal() {
      return principal;
    }

    public boolean hasExpired(long currTime) {
      return (currTime - time) >= ticketRelayExpiration;
    }
  }

  private class ExpirationTask extends TimerTask {

    @Override
    public void run() {
      checkTicketExpirations();
    }
  }

  private class RenewalTask extends TimerTask {

    @Override
    public void run() {
      createClientSubject();
    }
  }
}
