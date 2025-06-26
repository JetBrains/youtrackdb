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
package com.jetbrains.youtrack.db.internal.core.security.symmetrickey;

import com.jetbrains.youtrack.db.api.exception.SecurityException;
import java.util.Map;

/**
 * Implements the SymmetricKeyConfig interface for OUser records. The constructor looks for a
 * "properties" field on the OUser document. The "properties" field should be a JSON document
 * containing the SymmetricKey-specific fields.
 */
public class UserSymmetricKeyConfig implements SymmetricKeyConfig {

  private final String keyString;
  private String keyFile;
  private String keyAlgorithm;
  private String keystoreFile;
  private String keystorePassword;
  private String keystoreKeyAlias;
  private String keystoreKeyPassword;

  // SymmetricKeyConfig
  public String getKeyString() {
    return keyString;
  }

  public String getKeyFile() {
    return keyFile;
  }

  public String getKeyAlgorithm() {
    return keyAlgorithm;
  }

  public String getKeystoreFile() {
    return keystoreFile;
  }

  public String getKeystorePassword() {
    return keystorePassword;
  }

  public String getKeystoreKeyAlias() {
    return keystoreKeyAlias;
  }

  public String getKeystoreKeyPassword() {
    return keystoreKeyPassword;
  }

  // SymmetricKeyConfig
  public boolean usesKeyString() {
    return keyString != null
        && !keyString.isEmpty()
        && keyAlgorithm != null
        && !keyAlgorithm.isEmpty();
  }

  public boolean usesKeyFile() {
    return keyFile != null && !keyFile.isEmpty() && keyAlgorithm != null && !keyAlgorithm.isEmpty();
  }

  public boolean usesKeystore() {
    return keystoreFile != null
        && !keystoreFile.isEmpty()
        && keystoreKeyAlias != null
        && !keystoreKeyAlias.isEmpty();
  }

  public UserSymmetricKeyConfig(final Map<String, Object> config) {
    @SuppressWarnings("unchecked")
    var props = (Map<String, Object>) config.get("properties");

    if (props == null) {
      throw new SecurityException(
          "UserSymmetricKeyConfig() OUser properties is null");
    }

    this.keyString = (String) props.get("key");

    // "keyString" has priority over "keyFile" and "keystore".
    if (this.keyString != null) {
      // If "key" is used, "keyAlgorithm" is also required.
      this.keyAlgorithm = (String) props.get("keyAlgorithm");

      if (this.keyAlgorithm == null) {
        throw new SecurityException("UserSymmetricKeyConfig() keyAlgorithm is required with key");
      }
    } else {
      this.keyFile = (String) props.get("keyFile");

      // "keyFile" has priority over "keyStore".

      if (this.keyFile != null) {
        // If "keyFile" is used, "keyAlgorithm" is also required.
        this.keyAlgorithm = (String) props.get("keyAlgorithm");

        if (this.keyAlgorithm == null) {
          throw new SecurityException(
              "UserSymmetricKeyConfig() keyAlgorithm is required with keyFile");
        }
      } else {
        @SuppressWarnings("unchecked")
        var ksMap = (Map<String, Object>) props.get("keyStore");
        this.keystoreFile = (String) ksMap.get("file");
        this.keystorePassword = (String) ksMap.get("passsword");
        this.keystoreKeyAlias = (String) ksMap.get("keyAlias");
        this.keystoreKeyPassword = (String) ksMap.get("keyPassword");

        if (this.keystoreFile == null) {
          throw new SecurityException("UserSymmetricKeyConfig() keyStore.file is required");
        }
        if (this.keystoreKeyAlias == null) {
          throw new SecurityException("UserSymmetricKeyConfig() keyStore.keyAlias is required");
        }
      }
    }
  }
}
