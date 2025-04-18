package com.jetbrains.youtrack.db.internal.client.remote;

import static com.jetbrains.youtrack.db.api.config.GlobalConfiguration.CLIENT_CONNECTION_FETCH_HOST_LIST;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.client.remote.RemoteCommandsOrchestratorImpl.CONNECTION_STRATEGY;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import javax.annotation.Nullable;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteURLs {

  private static final Logger logger = LoggerFactory.getLogger(RemoteURLs.class);

  private static final int DEFAULT_PORT = 2424;
  private static final int DEFAULT_SSL_PORT = 2434;

  private final List<String> serverURLs = new ArrayList<String>();
  private List<String> initialServerURLs;
  private int nextServerToConnect;

  public RemoteURLs(String[] hosts, ContextConfiguration config) {
    for (var host : hosts) {
      addHost(host, config);
    }
    this.initialServerURLs = new ArrayList<String>(serverURLs);
    this.nextServerToConnect = 0;
  }

  public synchronized void remove(String serverUrl) {
    serverURLs.remove(serverUrl);
    this.nextServerToConnect = 0;
  }

  public synchronized List<String> getUrls() {
    return Collections.unmodifiableList(serverURLs);
  }

  @Nullable
  public synchronized String removeAndGet(String url) {
    remove(url);
    LogManager.instance().debug(this, "Updated server list: %s...", logger, serverURLs);

    if (!serverURLs.isEmpty()) {
      return serverURLs.get(0);
    } else {
      return null;
    }
  }

  public synchronized void addAll(List<String> toAdd, ContextConfiguration clientConfiguration) {
    if (toAdd.size() > 0) {
      serverURLs.clear();
      this.nextServerToConnect = 0;
      for (var host : toAdd) {
        addHost(host, clientConfiguration);
      }
    }
  }

  /**
   * Registers the remote server with port.
   */
  protected String addHost(String host, ContextConfiguration clientConfiguration) {

    if (host.contains("/")) {
      host = host.substring(0, host.indexOf('/'));
    }

    // REGISTER THE REMOTE SERVER+PORT
    if (!host.contains(":")) {
      if (clientConfiguration.getValueAsBoolean(GlobalConfiguration.CLIENT_USE_SSL)) {
        host += ":" + DEFAULT_SSL_PORT;
      } else {
        host += ":" + DEFAULT_PORT;
      }
    } else if (host.split(":").length < 2 || host.split(":")[1].trim().length() == 0) {
      if (clientConfiguration.getValueAsBoolean(GlobalConfiguration.CLIENT_USE_SSL)) {
        host += DEFAULT_SSL_PORT;
      } else {
        host += DEFAULT_PORT;
      }
    }

    if (!serverURLs.contains(host)) {
      serverURLs.add(host);
      LogManager.instance().debug(this, "Registered the new available server '%s'", logger, host);
    }

    return host;
  }

  protected int getDefaultPort() {
    return DEFAULT_PORT;
  }

  protected int getDefaultSSLPort() {
    return DEFAULT_SSL_PORT;
  }

  private static List<String> parseAddressesFromUrl(String url) {
    List<String> addresses = new ArrayList<>();
    var dbPos = url.indexOf('/');
    if (dbPos == -1) {
      // SHORT FORM
      addresses.add(url);
    } else {
      Collections.addAll(
          addresses, url.substring(0, dbPos).split(RemoteCommandsOrchestratorImpl.ADDRESS_SEPARATOR));
    }
    return addresses;
  }

  public synchronized String parseServerUrls(
      String url, ContextConfiguration contextConfiguration) {
    var dbPos = url.indexOf('/');
    String name;
    if (dbPos == -1) {
      // SHORT FORM
      name = url;
    } else {
      name = url.substring(url.lastIndexOf('/') + 1);
    }
    String lastHost = null;
    var hosts = parseAddressesFromUrl(url);
    for (var host : hosts) {
      lastHost = host;
      addHost(host, contextConfiguration);
    }

    if (serverURLs.size() == 1
        && contextConfiguration.getValueAsBoolean(
        GlobalConfiguration.NETWORK_BINARY_DNS_LOADBALANCING_ENABLED)) {
      var toAdd = fetchHostsFromDns(lastHost, contextConfiguration);
      serverURLs.addAll(toAdd);
    }
    this.initialServerURLs = new ArrayList<String>(serverURLs);
    return name;
  }

  public synchronized void reloadOriginalURLs() {
    this.serverURLs.clear();
    this.serverURLs.addAll(this.initialServerURLs);
  }

  private List<String> fetchHostsFromDns(
      final String primaryServer, ContextConfiguration contextConfiguration) {
    LogManager.instance()
        .debug(
            this,
            "Retrieving URLs from DNS '%s' (timeout=%d)...",
            logger, primaryServer,
            contextConfiguration.getValueAsInteger(
                GlobalConfiguration.NETWORK_BINARY_DNS_LOADBALANCING_TIMEOUT));

    List<String> toAdd = new ArrayList<>();
    try {
      final var env = new Hashtable<String, String>();
      env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
      env.put(
          "com.sun.jndi.ldap.connect.timeout",
          contextConfiguration.getValueAsString(
              GlobalConfiguration.NETWORK_BINARY_DNS_LOADBALANCING_TIMEOUT));

      final DirContext ictx = new InitialDirContext(env);
      final var hostName =
          !primaryServer.contains(":")
              ? primaryServer
              : primaryServer.substring(0, primaryServer.indexOf(':'));
      final var attrs = ictx.getAttributes(hostName, new String[]{"TXT"});
      final var attr = attrs.get("TXT");
      if (attr != null) {
        for (var i = 0; i < attr.size(); ++i) {
          var configuration = (String) attr.get(i);
          if (configuration.startsWith("\"")) {
            configuration = configuration.substring(1, configuration.length() - 1);
          }
          if (configuration != null) {
            final var parts = configuration.split(" ");
            for (var part : parts) {
              if (part.startsWith("s=")) {
                toAdd.add(part.substring("s=".length()));
              }
            }
          }
        }
      }
    } catch (NamingException ignore) {
    }
    return toAdd;
  }

  private synchronized String getNextConnectUrl(
      StorageRemoteSession session, ContextConfiguration contextConfiguration) {
    if (serverURLs.isEmpty()) {
      reloadOriginalURLs();
      if (serverURLs.isEmpty()) {
        throw new StorageException(null,
            "Cannot create a connection to remote server because url list is empty");
      }
    }

    this.nextServerToConnect++;
    if (this.nextServerToConnect >= serverURLs.size())
    // RESET INDEX
    {
      this.nextServerToConnect = 0;
    }

    final var serverURL = serverURLs.get(this.nextServerToConnect);
    if (session != null) {
      session.serverURLIndex = this.nextServerToConnect;
      session.currentUrl = serverURL;
    }

    return serverURL;
  }

  public synchronized String getServerURFromList(
      boolean iNextAvailable,
      StorageRemoteSession session,
      ContextConfiguration contextConfiguration) {
    if (session != null && session.getCurrentUrl() != null && !iNextAvailable) {
      return session.getCurrentUrl();
    }
    if (serverURLs.isEmpty()) {
      reloadOriginalURLs();
      if (serverURLs.isEmpty()) {
        throw new StorageException(null,
            "Cannot create a connection to remote server because url list is empty");
      }
    }

    // GET CURRENT THREAD INDEX
    int serverURLIndex;
    if (session != null) {
      serverURLIndex = session.serverURLIndex;
    } else {
      serverURLIndex = 0;
    }

    if (iNextAvailable) {
      serverURLIndex++;
    }

    if (serverURLIndex < 0 || serverURLIndex >= serverURLs.size())
    // RESET INDEX
    {
      serverURLIndex = 0;
    }

    final var serverURL = serverURLs.get(serverURLIndex);

    if (session != null) {
      session.serverURLIndex = serverURLIndex;
      session.currentUrl = serverURL;
    }

    return serverURL;
  }

  public synchronized String getNextAvailableServerURL(
      boolean iIsConnectOperation,
      StorageRemoteSession session,
      ContextConfiguration contextConfiguration,
      CONNECTION_STRATEGY strategy) {
    String url = null;
    if (session.isStickToSession()) {
      strategy = CONNECTION_STRATEGY.STICKY;
    }
    switch (strategy) {
      case STICKY:
        url = session.getServerUrl();
        if (url == null) {
          url = getServerURFromList(false, session, contextConfiguration);
        }
        break;

      case ROUND_ROBIN_CONNECT:
        if (iIsConnectOperation || session.getServerUrl() == null) {
          url = getNextConnectUrl(session, contextConfiguration);
        } else {
          url = session.getServerUrl();
        }
        LogManager.instance()
            .debug(
                this,
                "ROUND_ROBIN_CONNECT: Next remote operation will be executed on server: %s"
                    + " (isConnectOperation=%s)",
                logger, url,
                iIsConnectOperation);
        break;

      case ROUND_ROBIN_REQUEST:
        url = getServerURFromList(true, session, contextConfiguration);
        LogManager.instance()
            .debug(
                this,
                "ROUND_ROBIN_REQUEST: Next remote operation will be executed on server: %s"
                    + " (isConnectOperation=%s)",
                logger, url,
                iIsConnectOperation);
        break;

      default:
        throw new ConfigurationException("Connection mode " + strategy + " is not supported");
    }

    return url;
  }

  public synchronized void updateDistributedNodes(
      List<String> hosts, ContextConfiguration clientConfiguration) {
    if (!clientConfiguration.getValueAsBoolean(CLIENT_CONNECTION_FETCH_HOST_LIST)) {
      var definedHosts = initialServerURLs;
      for (var host : definedHosts) {
        addHost(host, clientConfiguration);
      }
      return;
    }
    // UPDATE IT
    for (var host : hosts) {
      addHost(host, clientConfiguration);
    }
  }
}
