package com.jetbrains.youtrack.db.internal.enterprise;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;

public interface EnterpriseEndpoint {

  void haSetDbStatus(BasicDatabaseSession db, String nodeName, String status);

  void haSetRole(BasicDatabaseSession db, String nodeName, String role);

  void haSetOwner(BasicDatabaseSession db, String collectionName, String owner);
}
