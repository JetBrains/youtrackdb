package com.jetbrains.youtrackdb.internal.core.storage.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** Fixed-width keys group name hashes while keeping colliding owners distinct. */
final class CollectionNameKey {
  private static final String DIGITS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

  private CollectionNameKey() {
  }

  static String normalize(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  static String hash(String normalizedName) {
    try {
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(normalizedName.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding()
          .encodeToString(Arrays.copyOf(digest, 15));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  static String prefix(String hash) {
    return "cnh_" + hash;
  }

  static String key(String hash, int id) {
    if (id < 0) {
      throw new IllegalArgumentException("A stored owner needs a nonnegative collection id");
    }
    var suffix = new char[6];
    for (var i = 5; i >= 0; i--) {
      suffix[i] = DIGITS.charAt(id & 63);
      id >>>= 6;
    }
    return prefix(hash) + new String(suffix);
  }

  static boolean isWellFormed(String key) {
    if (key.length() != 30 || !key.startsWith("cnh_")) {
      return false;
    }
    for (var i = 4; i < 30; i++) {
      if (DIGITS.indexOf(key.charAt(i)) < 0) {
        return false;
      }
    }
    // Six base64 digits encode 36 bits. Collection ids use only 31.
    return DIGITS.indexOf(key.charAt(24)) <= 1;
  }

  static int owner(String key) {
    long id = 0;
    for (var i = 24; i < 30; i++) {
      id = (id << 6) | DIGITS.indexOf(key.charAt(i));
    }
    return Math.toIntExact(id);
  }
}
