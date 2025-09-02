package com.jetbrains.youtrackdb.internal.core.metadata.security.auth;

import java.util.Optional;

public interface AuthenticationInfo {

  Optional<String> getDatabase();
}
