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
package com.jetbrains.youtrack.db.internal.enterprise.channel;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class SocketFactory {

  private javax.net.SocketFactory socketFactory;
  private boolean useSSL = false;
  private SSLContext context = null;
  private final ContextConfiguration config;

  private String keyStorePath = null;
  private String keyStorePassword = null;
  private final String keyStoreType = KeyStore.getDefaultType();
  private String trustStorePath = null;
  private String trustStorePassword = null;
  private final String trustStoreType = KeyStore.getDefaultType();

  private SocketFactory(final ContextConfiguration iConfig) {
    config = iConfig;

    useSSL = iConfig.getValueAsBoolean(GlobalConfiguration.CLIENT_USE_SSL);
    keyStorePath = (String) iConfig.getValue(GlobalConfiguration.CLIENT_SSL_KEYSTORE);
    keyStorePassword = (String) iConfig.getValue(
        GlobalConfiguration.CLIENT_SSL_KEYSTORE_PASSWORD);
    trustStorePath = (String) iConfig.getValue(GlobalConfiguration.CLIENT_SSL_TRUSTSTORE);
    trustStorePassword =
        (String) iConfig.getValue(GlobalConfiguration.CLIENT_SSL_TRUSTSTORE_PASSWORD);
  }

  public static SocketFactory instance(final ContextConfiguration iConfig) {
    return new SocketFactory(iConfig);
  }

  private javax.net.SocketFactory getBackingFactory() {
    if (socketFactory == null) {
      if (useSSL) {
        socketFactory = getSSLContext().getSocketFactory();
      } else {
        socketFactory = javax.net.SocketFactory.getDefault();
      }
    }
    return socketFactory;
  }

  protected SSLContext getSSLContext() {
    if (context == null) {
      context = createSSLContext();
    }
    return context;
  }

  protected SSLContext createSSLContext() {
    try {
      if (keyStorePath != null && trustStorePath != null) {
        if (keyStorePassword == null || keyStorePassword.equals("")) {
          throw new ConfigurationException("Please provide a keystore password");
        }
        if (trustStorePassword == null || trustStorePassword.equals("")) {
          throw new ConfigurationException("Please provide a truststore password");
        }

        var context = SSLContext.getInstance("TLS");

        var kmf =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

        var keyStore = KeyStore.getInstance(keyStoreType);
        var keyStorePass = keyStorePassword.toCharArray();
        keyStore.load(getAsStream(keyStorePath), keyStorePass);

        kmf.init(keyStore, keyStorePass);

        TrustManagerFactory tmf = null;
        if (trustStorePath != null) {
          tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
          var trustStore = KeyStore.getInstance(trustStoreType);
          var trustStorePass = trustStorePassword.toCharArray();
          trustStore.load(getAsStream(trustStorePath), trustStorePass);
          tmf.init(trustStore);
        }

        context.init(kmf.getKeyManagers(), (tmf == null ? null : tmf.getTrustManagers()), null);

        return context;
      } else {
        return SSLContext.getDefault();
      }
    } catch (Exception e) {
      throw BaseException.wrapException(
          new ConfigurationException("Failed to create ssl context"), e, (String) null);
    }
  }

  protected InputStream getAsStream(String path) throws IOException {

    InputStream input = null;

    path = SystemVariableResolver.resolveSystemVariables(path);

    try {
      var url = new URL(path);
      input = url.openStream();
    } catch (MalformedURLException ignore) {
      input = null;
    }

    if (input == null) {
      input = getClass().getResourceAsStream(path);
    }

    if (input == null) {
      input = getClass().getClassLoader().getResourceAsStream(path);
    }

    if (input == null) {
      try {
        // This resolves an issue on Windows with relative paths not working correctly.
        path = new java.io.File(path).getAbsolutePath();
        input = new FileInputStream(path);
      } catch (FileNotFoundException ignore) {
        input = null;
      }
    }

    if (input == null) {
      throw new java.io.IOException("Could not load resource from path: " + path);
    }

    return input;
  }

  private Socket configureSocket(Socket socket) throws SocketException {

    // Add possible timeouts?
    return socket;
  }

  public Socket createSocket() throws IOException {
    return configureSocket(getBackingFactory().createSocket());
  }
}
