package com.jetbrains.youtrackdb.internal.core.metadata.security.jwt;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.io.DataOutputStream;
import java.io.IOException;

public interface TokenPayload {

  String getDatabase();

  long getExpiry();

  RID getUserRid();

  String getDatabaseType();

  String getUserName();

  void setExpiry(long expiry);

  String getPayloadType();

  void serialize(DataOutputStream output, TokenMetaInfo serializer) throws IOException;
}
