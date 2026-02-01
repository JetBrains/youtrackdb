/*
 *
 *  *  Copyright 2016 YouTrackDB LTD
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
package com.jetbrains.youtrackdb.internal.core.security.symmetrickey;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.security.CredentialInterceptor;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import java.util.Map;

/**
 * Provides a symmetric key credential interceptor.
 *
 * <p>The "password" parameter should be a JSON document specifying "keyAlgorithm" and "key",
 * "keyFile", or "keyStore".
 *
 * <p>The method getPassword() will return a Base64-encoded JSON document with the encrypted
 * "username" as its payload.
 */
public class SymmetricKeyCI implements CredentialInterceptor {

  private String username;
  private String encodedJSON = "";

  @Override
  public String getUsername() {
    return this.username;
  }

  @Override
  public String getPassword() {
    return this.encodedJSON;
  }

  /**
   * The usual password field should be a JSON representation.
   */
  @Override
  public void intercept(final String url, final String username, final String password)
      throws SecurityException {
    if (username == null || username.isEmpty()) {
      throw new SecurityException((String) null, "SymmetricKeyCI username is not valid!");
    }
    if (password == null || password.isEmpty()) {
      throw new SecurityException((String) null, "SymmetricKeyCI password is not valid!");
    }

    this.username = username;

    // These are all used as defaults if the JSON document is missing any fields.

    // Defaults to "AES".
    var algorithm = GlobalConfiguration.CLIENT_CI_KEYALGORITHM.getValueAsString();
    // Defaults to "AES/CBC/PKCS5Padding".
    var transform = GlobalConfiguration.CLIENT_CI_CIPHERTRANSFORM.getValueAsString();
    var keystoreFile = GlobalConfiguration.CLIENT_CI_KEYSTORE_FILE.getValueAsString();
    var keystorePassword = GlobalConfiguration.CLIENT_CI_KEYSTORE_PASSWORD.getValueAsString();

    Map<String, Object> metadata = null;

    try {
      metadata = JSONSerializerJackson.INSTANCE.mapFromJson(password);
    } catch (Exception ex) {
      throw BaseException.wrapException(
          new SecurityException((String) null,
              "SymmetricKeyCI.intercept() Exception: " + ex.getMessage()),
          ex, (String) null);
    }

    // Override algorithm and transform, if they exist in the JSON document.
    if (metadata.containsKey("algorithm")) {
      algorithm = metadata.get("algorithm").toString();
    }
    if (metadata.containsKey("transform")) {
      transform = metadata.get("transform").toString();
    }

    // Just in case the default configuration gets changed, check it.
    if (transform == null || transform.isEmpty()) {
      throw new SecurityException((String) null,
          "SymmetricKeyCI.intercept() cipher transformation is required");
    }

    // If the algorithm is not set, either as a default in the global configuration or in the JSON
    // document,
    // then determine the algorithm from the cipher transformation.
    if (algorithm == null) {
      algorithm = SymmetricKey.separateAlgorithm(transform);
    }

    SymmetricKey key = null;

    // "key" has priority over "keyFile" and "keyStore".
    if (metadata.containsKey("key")) {
      final var base64Key = metadata.get("key").toString();

      key = SymmetricKey.fromString(algorithm, base64Key);
      key.setDefaultCipherTransform(transform);
    } else // "keyFile" has priority over "keyStore".
      if (metadata.containsKey("keyFile")) {
        key = SymmetricKey.fromFile(algorithm, metadata.get("keyFile").toString());
        key.setDefaultCipherTransform(transform);
      } else {
        if (metadata.containsKey("keyStore")) {
          @SuppressWarnings("unchecked")
          var ksMap = (Map<String, Object>) metadata.get("keyStore");

          if (ksMap.containsKey("file")) {
            keystoreFile = ksMap.get("file").toString();
          }

          if (keystoreFile == null || keystoreFile.isEmpty()) {
            throw new SecurityException((String) null,
                "SymmetricKeyCI.intercept() keystore file is required");
          }

          // Specific to Keystore, but override if present in the JSON document.
          if (ksMap.containsKey("password")) {
            keystorePassword = ksMap.get("password").toString();
          }

          var keyAlias = (String) ksMap.get("keyAlias");

          if (keyAlias == null || keyAlias.isEmpty()) {
            throw new SecurityException((String) null,
                "SymmetricKeyCI.intercept() keystore key alias is required");
          }

          // keyPassword may be null.
          var keyPassword = (String) ksMap.get("keyPassword");

          // keystorePassword may be null.
          key = SymmetricKey.fromKeystore(keystoreFile, keystorePassword, keyAlias, keyPassword);
          key.setDefaultCipherTransform(transform);
        } else {
          throw new SecurityException((String) null,
              "SymmetricKeyCI.intercept() No suitable symmetric key property exists");
        }
      }

    encodedJSON = key.encrypt(transform, username);
  }
}
