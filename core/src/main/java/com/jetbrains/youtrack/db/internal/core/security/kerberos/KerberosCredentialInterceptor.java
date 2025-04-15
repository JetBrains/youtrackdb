/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrack.db.internal.core.security.kerberos;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.internal.core.security.CredentialInterceptor;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedAction;
import java.util.Base64;
import javax.annotation.Nullable;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * Provides a Kerberos credential interceptor.
 */
public class KerberosCredentialInterceptor implements CredentialInterceptor {

  private String principal;
  private String serviceTicket;

  public String getUsername() {
    return this.principal;
  }

  public String getPassword() {
    return this.serviceTicket;
  }

  public void intercept(final String url, final String principal, final String spn)
      throws SecurityException {
    // While the principal can be determined from the ticket cache, if a client keytab is used
    // instead,
    // it may contain multiple principals.
    if (principal == null || principal.isEmpty()) {
      throw new SecurityException("KerberosCredentialInterceptor Principal cannot be null!");
    }

    this.principal = principal;

    var actualSPN = spn;

    // spn should be the SPN of the service.
    if (spn == null || spn.isEmpty()) {
      // If spn is null or an empty string, the SPN will be generated from the URL like this:
      //		YouTrackDB/host
      if (url == null || url.isEmpty()) {
        throw new SecurityException(
            "KerberosCredentialInterceptor URL and SPN cannot both be null!");
      }

      try {
        var tempURL = url;

        // Without the // URI can't parse URLs correctly, so we add //.
        if (tempURL.startsWith("remote:") && !tempURL.startsWith("remote://")) {
          tempURL = tempURL.replace("remote:", "remote://");
        }

        var remoteURI = new URI(tempURL);

        var host = remoteURI.getHost();

        if (host == null) {
          throw new SecurityException(
              "KerberosCredentialInterceptor Could not create SPN from URL: " + url);
        }

        actualSPN = "YouTrackDB/" + host;
      } catch (URISyntaxException ex) {
        throw BaseException.wrapException(
            new SecurityException(url,
                "KerberosCredentialInterceptor Could not create SPN from URL: " + url),
            ex, url);
      }
    }

    // Defaults to the environment variable.
    var config = System.getenv("KRB5_CONFIG");
    var ckc = GlobalConfiguration.CLIENT_KRB5_CONFIG.getValueAsString();
    if (ckc != null) {
      config = ckc;
    }

    // Defaults to the environment variable.
    var ccname = System.getenv("KRB5CCNAME");
    var ccn = GlobalConfiguration.CLIENT_KRB5_CCNAME.getValueAsString();
    if (ccn != null) {
      ccname = ccn;
    }

    // Defaults to the environment variable.
    var ktname = System.getenv("KRB5_CLIENT_KTNAME");
    var ckn = GlobalConfiguration.CLIENT_KRB5_KTNAME.getValueAsString();
    if (ckn != null) {
      ktname = ckn;
    }

    if (config == null) {
      throw new SecurityException(url, "KerberosCredentialInterceptor KRB5 Config cannot be null!");
    }
    if (ccname == null && ktname == null) {
      throw new SecurityException(url,
          "KerberosCredentialInterceptor KRB5 Credential Cache and KeyTab cannot both be null!");
    }

    LoginContext lc = null;

    try {
      System.setProperty("java.security.krb5.conf", config);

      var cfg =
          new Krb5ClientLoginModuleConfig(principal, ccname, ktname);

      lc = new LoginContext("ignore", null, null, cfg);
      lc.login();
    } catch (LoginException lie) {
      LogManager.instance().debug(this, "intercept() LoginException", lie);

      throw BaseException.wrapException(
          new SecurityException(url, "KerberosCredentialInterceptor Client Validation Exception!"),
          lie, url);
    }

    var subject = lc.getSubject();

    // Assign the client's principal name.
    //		this.principal = getFirstPrincipal(subject);

    //		if(this.principal == null) throw new SecurityException("KerberosCredentialInterceptor
    // Cannot obtain client principal!");

    this.serviceTicket = getServiceTicket(subject, principal, actualSPN);

    try {
      lc.logout();
    } catch (LoginException loe) {
      LogManager.instance().debug(this, "intercept() LogoutException", loe);
    }

    if (this.serviceTicket == null) {
      throw new SecurityException(url,
          "KerberosCredentialInterceptor Cannot obtain the service ticket!");
    }
  }

  @Nullable
  private String getServiceTicket(
      final Subject subject, final String principal, final String servicePrincipalName) {
    try {
      var manager = GSSManager.getInstance();
      var serviceName = manager.createName(servicePrincipalName, GSSName.NT_USER_NAME);

      var krb5Oid = new Oid("1.2.840.113554.1.2.2");

      // Initiator.
      final var context =
          manager.createContext(serviceName, krb5Oid, null, GSSContext.DEFAULT_LIFETIME);

      if (context != null) {
        // http://docs.oracle.com/javase/6/docs/technotes/guides/security/jgss/jgss-features.html
        // When performing operations as a particular Subject, e.g. Subject.doAs(...) or
        // Subject.doAsPrivileged(...),
        // the to-be-used GSSCredential should be added to Subject's private credential set.
        // Otherwise,
        // the GSS operations will fail since no credential is found.
        var useNativeJgss = Boolean.getBoolean("sun.security.jgss.native");

        if (useNativeJgss) {
          LogManager.instance().info(this, "getServiceTicket() Using Native JGSS");

          try {
            var clientName = manager.createName(principal, GSSName.NT_USER_NAME);

            // null: indicates using the default principal.
            var cred =
                manager.createCredential(
                    clientName, GSSContext.DEFAULT_LIFETIME, krb5Oid, GSSCredential.INITIATE_ONLY);

            subject.getPrivateCredentials().add(cred);
          } catch (GSSException gssEx) {
            LogManager.instance()
                .error(this, "getServiceTicket() Use Native JGSS GSSException", gssEx);
          }
        }

        // The GSS context initiation has to be performed as a privileged action.
        var serviceTicket =
            Subject.doAs(
                subject,
                new PrivilegedAction<byte[]>() {
                  @Nullable
                  public byte[] run() {
                    try {
                      var token = new byte[0];

                      // This is a one pass context initialisation.
                      context.requestMutualAuth(false);
                      context.requestCredDeleg(false);
                      return context.initSecContext(token, 0, token.length);
                    } catch (Exception inner) {
                      LogManager.instance()
                          .debug(this, "getServiceTicket() doAs() Exception", inner);
                    }

                    return null;
                  }
                });

        if (serviceTicket != null) {
          return Base64.getEncoder().encodeToString(serviceTicket);
        }

        context.dispose();
      } else {
        LogManager.instance().debug(this, "getServiceTicket() GSSContext is null!");
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "getServiceTicket() Exception", ex);
    }

    return null;
  }
}
