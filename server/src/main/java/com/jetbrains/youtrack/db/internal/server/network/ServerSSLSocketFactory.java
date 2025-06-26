/*
 * Copyright 2014 Charles Baptiste (cbaptiste--at--blacksparkcorp.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.server.network;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class ServerSSLSocketFactory extends ServerSocketFactory {

  public static final String PARAM_NETWORK_SSL_CLIENT_AUTH = "network.ssl.clientAuth";
  public static final String PARAM_NETWORK_SSL_KEYSTORE = "network.ssl.keyStore";
  public static final String PARAM_NETWORK_SSL_KEYSTORE_TYPE = "network.ssl.keyStoreType";
  public static final String PARAM_NETWORK_SSL_KEYSTORE_PASSWORD = "network.ssl.keyStorePassword";
  public static final String PARAM_NETWORK_SSL_TRUSTSTORE = "network.ssl.trustStore";
  public static final String PARAM_NETWORK_SSL_TRUSTSTORE_TYPE = "network.ssl.trustStoreType";
  public static final String PARAM_NETWORK_SSL_TRUSTSTORE_PASSWORD =
      "network.ssl.trustStorePassword";

  private SSLServerSocketFactory sslServerSocketFactory = null;

  private String keyStorePath = null;
  private File keyStoreFile = null;
  private String keyStorePassword = null;
  private String keyStoreType = KeyStore.getDefaultType();
  private String trustStorePath = null;
  private File trustStoreFile = null;
  private String trustStorePassword = null;
  private String trustStoreType = KeyStore.getDefaultType();
  private boolean clientAuth = false;

  public ServerSSLSocketFactory() {
  }

  @Override
  public void config(String name, final ServerParameterConfiguration[] iParameters) {

    super.config(name, iParameters);
    for (var param : iParameters) {
      if (param.name.equalsIgnoreCase(PARAM_NETWORK_SSL_CLIENT_AUTH)) {
        clientAuth = Boolean.parseBoolean(param.value);
      } else if (param.name.equalsIgnoreCase(PARAM_NETWORK_SSL_KEYSTORE)) {
        keyStorePath = param.value;
      } else if (param.name.equalsIgnoreCase(PARAM_NETWORK_SSL_KEYSTORE_PASSWORD)) {
        keyStorePassword = param.value;
      } else if (param.name.equalsIgnoreCase(PARAM_NETWORK_SSL_KEYSTORE_TYPE)) {
        keyStoreType = param.value;
      } else if (param.name.equalsIgnoreCase(PARAM_NETWORK_SSL_TRUSTSTORE)) {
        trustStorePath = param.value;
      } else if (param.name.equalsIgnoreCase(PARAM_NETWORK_SSL_TRUSTSTORE_PASSWORD)) {
        trustStorePassword = param.value;
      } else if (param.name.equalsIgnoreCase(PARAM_NETWORK_SSL_TRUSTSTORE_TYPE)) {
        trustStoreType = param.value;
      }
    }

    if (keyStorePath == null) {
      throw new ConfigurationException("Missing parameter " + PARAM_NETWORK_SSL_KEYSTORE);
    } else if (keyStorePassword == null) {
      throw new ConfigurationException(
          "Missing parameter " + PARAM_NETWORK_SSL_KEYSTORE_PASSWORD);
    }

    keyStoreFile = new File(keyStorePath);
    if (!keyStoreFile.isAbsolute()) {
      keyStoreFile =
          new File(
              SystemVariableResolver.resolveSystemVariables("${YOUTRACKDB_HOME}"), keyStorePath);
    }

    if (trustStorePath != null) {
      trustStoreFile = new File(trustStorePath);
      if (!trustStoreFile.isAbsolute()) {
        trustStoreFile =
            new File(
                SystemVariableResolver.resolveSystemVariables("${YOUTRACKDB_HOME}"),
                trustStorePath);
      }
    }
  }

  private ServerSocket configureSocket(SSLServerSocket serverSocket) {

    serverSocket.setNeedClientAuth(clientAuth);

    return serverSocket;
  }

  private SSLServerSocketFactory getBackingFactory() {
    if (sslServerSocketFactory == null) {

      sslServerSocketFactory = getSSLContext().getServerSocketFactory();
    }
    return sslServerSocketFactory;
  }

  protected SSLContext getSSLContext() {

    try {
      var context = SSLContext.getInstance("TLS");

      var kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

      var keyStore = KeyStore.getInstance(keyStoreType);
      var keyStorePass = keyStorePassword.toCharArray();
      var serverSSLCertificateManager =
          ServerSSLCertificateManager.getInstance(this, keyStore, keyStoreFile, keyStorePass);
      serverSSLCertificateManager.loadKeyStoreForSSLSocket();
      kmf.init(keyStore, keyStorePass);

      TrustManagerFactory tmf = null;
      if (trustStoreFile != null) {
        tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        var trustStore = KeyStore.getInstance(trustStoreType);
        var trustStorePass = trustStorePassword.toCharArray();
        serverSSLCertificateManager.loadTrustStoreForSSLSocket(
            trustStore, trustStoreFile, trustStorePass);
        tmf.init(trustStore);
      }

      context.init(kmf.getKeyManagers(), (tmf == null ? null : tmf.getTrustManagers()), null);

      return context;

    } catch (Exception e) {
      throw BaseException.wrapException(
          new ConfigurationException("Failed to create SSL context"), e, (String) null);
    }
  }

  @Override
  public ServerSocket createServerSocket(int port) throws IOException {
    return configureSocket((SSLServerSocket) getBackingFactory().createServerSocket(port));
  }

  @Override
  public ServerSocket createServerSocket(int port, int backlog) throws IOException {
    return configureSocket((SSLServerSocket) getBackingFactory().createServerSocket(port, backlog));
  }

  @Override
  public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress)
      throws IOException {
    return configureSocket(
        (SSLServerSocket) getBackingFactory().createServerSocket(port, backlog, ifAddress));
  }

  public boolean isEncrypted() {
    return true;
  }
}
