package com.jetbrains.youtrackdb.internal.core.security;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.exception.SystemException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.KeyProvider;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;

public class TokenSignImpl implements TokenSign {

  public static final String ENCRYPTION_ALGORITHM_DEFAULT = "HmacSHA256";

  private String algorithm = ENCRYPTION_ALGORITHM_DEFAULT;

  private static final ThreadLocal<Map<String, Mac>> threadLocalMac = new MacThreadLocal();

  private final KeyProvider keyProvider;

  private static class MacThreadLocal extends ThreadLocal<Map<String, Mac>> {

    @Override
    protected Map<String, Mac> initialValue() {
      return new HashMap<String, Mac>();
    }
  }

  public TokenSignImpl(ContextConfiguration config) {
    this(
        TokenSignImpl.readKeyFromConfig(config),
        config.getValueAsString(GlobalConfiguration.NETWORK_TOKEN_ENCRYPTION_ALGORITHM));
  }

  public TokenSignImpl(byte[] key, String algorithm) {
    this.keyProvider = new DefaultKeyProvider(key);
    if (algorithm != null) {
      this.algorithm = algorithm;
    }
    try {
      Mac.getInstance(this.algorithm);
    } catch (NoSuchAlgorithmException nsa) {
      throw new IllegalArgumentException(
          "Can't find encryption algorithm '" + algorithm + "'", nsa);
    }
  }

  private Mac getLocalMac() {
    var map = threadLocalMac.get();
    var mac = map.get(this.algorithm);
    if (mac == null) {
      try {
        mac = Mac.getInstance(this.algorithm);
      } catch (NoSuchAlgorithmException nsa) {
        throw new IllegalArgumentException(
            "Can't find encryption algorithm '" + algorithm + "'", nsa);
      }
      map.put(this.algorithm, mac);
    }
    return mac;
  }

  @Override
  public byte[] signToken(final TokenHeader header, final byte[] unsignedToken) {
    final var mac = getLocalMac();
    try {
      mac.init(keyProvider.getKey(header));
      return mac.doFinal(unsignedToken);
    } catch (Exception ex) {
      throw BaseException.wrapException(new SystemException("Error on token parsing"), ex,
          (String) null);
    } finally {
      mac.reset();
    }
  }

  @Override
  public boolean verifyTokenSign(ParsedToken parsed) {
    var token = parsed.getToken();
    var tokenBytes = parsed.getTokenBytes();
    var signature = parsed.getSignature();
    final var mac = getLocalMac();

    try {
      mac.init(keyProvider.getKey(token.getHeader()));
      mac.update(tokenBytes, 0, tokenBytes.length);
      final var calculatedSignature = mac.doFinal();
      var valid = MessageDigest.isEqual(calculatedSignature, signature);
      if (!valid) {
        LogManager.instance()
            .warn(
                this,
                "Token signature failure: %s",
                Base64.getEncoder().encodeToString(tokenBytes));
      }
      return valid;

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw BaseException.wrapException(new SystemException("Token signature cannot be verified"),
          e, (String) null);
    } finally {
      mac.reset();
    }
  }

  @Override
  public String getAlgorithm() {
    return algorithm;
  }

  @Override
  public String getDefaultKey() {
    return this.keyProvider.getDefaultKey();
  }

  @Override
  public String[] getKeys() {
    return this.keyProvider.getKeys();
  }

  public static byte[] readKeyFromConfig(ContextConfiguration config) {
    byte[] key = null;
    var configKey = config.getValueAsString(GlobalConfiguration.NETWORK_TOKEN_SECRETKEY);
    if (configKey == null || configKey.length() == 0) {
      if (configKey != null && configKey.length() > 0) {
        key = Base64.getUrlDecoder().decode(configKey);
      }
    }

    if (key == null) {
      key = SecurityManager.digestSHA256(String.valueOf(new SecureRandom().nextLong()));
    }
    return key;
  }
}
