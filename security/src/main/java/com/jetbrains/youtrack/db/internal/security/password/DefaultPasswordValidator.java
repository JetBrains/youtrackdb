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
package com.jetbrains.youtrack.db.internal.security.password;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.security.InvalidPasswordException;
import com.jetbrains.youtrack.db.internal.core.security.PasswordValidator;
import com.jetbrains.youtrack.db.internal.core.security.SecuritySystem;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Provides a default implementation for validating passwords.
 */
public class DefaultPasswordValidator implements PasswordValidator {

  private boolean enabled = true;
  private boolean ignoreUUID = true;
  private int minLength = 0;
  private Pattern hasNumber;
  private Pattern hasSpecial;
  private Pattern hasUppercase;
  private final Pattern isUUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  // SecurityComponent
  public void active() {
    LogManager.instance().debug(this, "DefaultPasswordValidator is active");
  }

  // SecurityComponent
  public void config(DatabaseSessionInternal session, final Map<String, Object> jsonConfig,
      SecuritySystem security) {
    try {
      if (jsonConfig.containsKey("enabled")) {
        enabled = (Boolean) jsonConfig.get("enabled");
      }

      if (jsonConfig.containsKey("ignoreUUID")) {
        ignoreUUID = (Boolean) jsonConfig.get("ignoreUUID");
      }

      if (jsonConfig.containsKey("minimumLength")) {
        minLength = (Integer) jsonConfig.get("minimumLength");
      }

      if (jsonConfig.containsKey("numberRegEx")) {
        hasNumber = Pattern.compile(jsonConfig.get("numberRegEx").toString());
      }

      if (jsonConfig.containsKey("specialRegEx")) {
        hasSpecial = Pattern.compile(jsonConfig.get("specialRegEx").toString());
      }

      if (jsonConfig.containsKey("uppercaseRegEx")) {
        hasUppercase = Pattern.compile(jsonConfig.get("uppercaseRegEx").toString());
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultPasswordValidator.config()", ex);
    }
  }

  // SecurityComponent
  public void dispose() {
  }

  // SecurityComponent
  public boolean isEnabled() {
    return enabled;
  }

  // PasswordValidator
  public void validatePassword(final String username, final String password)
      throws InvalidPasswordException {
    if (!enabled) {
      return;
    }

    if (password != null && !password.isEmpty()) {
      if (ignoreUUID && isUUID(password)) {
        return;
      }

      if (password.length() < minLength) {
        LogManager.instance()
            .debug(
                this,
                "DefaultPasswordValidator.validatePassword() Password length (%d) is too short",
                password.length());
        throw new InvalidPasswordException(
            "Password length is too short.  Minimum password length is " + minLength);
      }

      if (hasNumber != null && !isValid(hasNumber, password)) {
        LogManager.instance()
            .debug(
                this,
                "DefaultPasswordValidator.validatePassword() Password requires a minimum count of"
                    + " numbers");
        throw new InvalidPasswordException("Password requires a minimum count of numbers");
      }

      if (hasSpecial != null && !isValid(hasSpecial, password)) {
        LogManager.instance()
            .debug(
                this,
                "DefaultPasswordValidator.validatePassword() Password requires a minimum count of"
                    + " special characters");
        throw new InvalidPasswordException(
            "Password requires a minimum count of special characters");
      }

      if (hasUppercase != null && !isValid(hasUppercase, password)) {
        LogManager.instance()
            .debug(
                this,
                "DefaultPasswordValidator.validatePassword() Password requires a minimum count of"
                    + " uppercase characters");
        throw new InvalidPasswordException(
            "Password requires a minimum count of uppercase characters");
      }
    } else {
      LogManager.instance()
          .debug(this, "DefaultPasswordValidator.validatePassword() Password is null or empty");
      throw new InvalidPasswordException(
          "DefaultPasswordValidator.validatePassword() Password is null or empty");
    }
  }

  private static boolean isValid(final Pattern pattern, final String password) {
    return pattern.matcher(password).find();
  }

  private boolean isUUID(final String password) {
    return isUUID.matcher(password).find();
  }
}
