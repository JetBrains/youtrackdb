package com.jetbrains.youtrack.db.internal.server.http;

import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.File;
import java.io.IOException;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;

/**
 * Base test class for HTTP protocol.
 */
public abstract class BaseHttpTest {

  protected String serverDirectory;

  private static YouTrackDBServer server;

  private String serverCfg =
      "/com/jetbrains/youtrack/db/internal/server/network/youtrackdb-server-config-httponly.xml";
  private final String protocol = "http";
  private final String host = "localhost";
  private final int port = 2499;
  private String realm = "YouTrackDB-";
  private String userName = "admin";
  private String userPassword = "admin";
  private String databaseName;
  private Boolean keepAlive = null;

  private ClassicHttpRequest request;
  private AbstractHttpEntity payload;
  private ClassicHttpResponse response;

  private CloseableHttpClient client;
  private int retry = 1;

  public enum CONTENT {
    TEXT,
    JSON
  }

  public BaseHttpTest payload(final String content, final CONTENT contentType) {
    payload =
        new StringEntity(
            content,
            ContentType.create(contentType == CONTENT.JSON ? "application/json" : "plain/text"));
    return this;
  }

  protected void startServer() throws Exception {
    if (server == null) {
      server = new YouTrackDBServer(false);
      if (serverDirectory != null) {
        server.setServerRootDirectory(serverDirectory);
      }
      server.startup(getClass().getResourceAsStream(getServerCfg()));
      server.activate();
    }
  }

  protected void stopServer() throws Exception {
    if (client != null) {
      client.close();
    }

    if (server != null) {
      server.shutdown();
      server = null;

      YouTrackDBEnginesManager.instance().shutdown();
      if (serverDirectory != null) {
        FileUtils.deleteRecursively(new File(serverDirectory));
      }
      YouTrackDBEnginesManager.instance().startup();
    }
  }

  protected boolean isInDevelopmentMode() {
    final var env = System.getProperty("youtrackdb.test.env");
    return env == null || env.equals("dev");
  }

  protected BaseHttpTest exec() throws IOException {
    final var targetHost = new HttpHost(protocol, host, port);

    var credsProvider = new BasicCredentialsProvider();
    credsProvider.setCredentials(
        new AuthScope(targetHost),
        new UsernamePasswordCredentials(userName, userPassword.toCharArray()));

    final var provider = new BasicCredentialsProvider();
    var authScope = new AuthScope(targetHost);
    provider.setCredentials(
        authScope, new UsernamePasswordCredentials(userName, userPassword.toCharArray()));

    if (keepAlive != null) {
      request.addHeader("Connection", keepAlive ? "Keep-Alive" : "Close");
    }

    if (payload != null && request instanceof HttpUriRequestBase httpUriRequestBase) {
      httpUriRequestBase.setEntity(payload);
    }

    client = HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build();
    // DefaultHttpMethodRetryHandler retryhandler = new DefaultHttpMethodRetryHandler(retry,
    // false);
    // context.setAttribute(HttpMethodParams.RETRY_HANDLER, retryhandler);
    response = client.execute(request);

    return this;
  }

  protected BaseHttpTest get(final String url, Header[] headers) throws IOException {
    request = new HttpGet(getBaseURL() + "/" + url);
    request.setHeaders(headers);

    response = null;
    return this;
  }

  protected BaseHttpTest get(final String url) throws IOException {
    request = new HttpGet(getBaseURL() + "/" + url);
    response = null;
    return this;
  }

  protected BaseHttpTest post(final String url) throws IOException {
    request = new HttpPost(getBaseURL() + "/" + url);
    response = null;
    return this;
  }

  protected BaseHttpTest put(final String url) throws IOException {
    request = new HttpPut(getBaseURL() + "/" + url);
    response = null;
    return this;
  }

  protected BaseHttpTest delete(final String url) throws IOException {
    request = new HttpDelete(getBaseURL() + "/" + url);
    response = null;
    return this;
  }

  protected BaseHttpTest patch(final String url) throws IOException {
    request = new HttpPatch(getBaseURL() + "/" + url);
    response = null;
    return this;
  }

  protected ClassicHttpResponse getResponse() throws IOException {
    if (response == null) {
      exec();
    }
    return response;
  }

  protected BaseHttpTest setKeepAlive(final boolean iValue) {
    keepAlive = iValue;
    return this;
  }

  protected String getBaseURL() {
    return protocol + "://" + host + ":" + port;
  }

  public String getUserName() {
    return userName;
  }

  protected BaseHttpTest setUserName(final String userName) {
    this.userName = userName;
    return this;
  }

  public BaseHttpTest setRetry(final int iRetry) {
    retry = iRetry;
    return this;
  }

  protected String getUserPassword() {
    return userPassword;
  }

  protected BaseHttpTest setUserPassword(final String userPassword) {
    this.userPassword = userPassword;
    return this;
  }

  protected String getProtocol() {
    return protocol;
  }

  protected String getHost() {
    return host;
  }

  protected int getPort() {
    return port;
  }

  protected String getServerCfg() {
    return serverCfg;
  }

  protected BaseHttpTest setServerCfg(String serverCfg) {
    this.serverCfg = serverCfg;
    return this;
  }

  protected String getDatabaseName() {
    return databaseName;
  }

  protected String getRealm() {
    return realm;
  }

  protected BaseHttpTest setRealm(String realm) {
    this.realm = realm;
    return this;
  }

  public static YouTrackDBServer getServer() {
    return server;
  }
}
