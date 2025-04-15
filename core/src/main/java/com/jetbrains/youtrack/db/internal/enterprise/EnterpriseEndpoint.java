package com.jetbrains.youtrack.db.internal.enterprise;

import com.jetbrains.youtrack.db.api.DatabaseSession;

public interface EnterpriseEndpoint {

  void haSetDbStatus(DatabaseSession db, String nodeName, String status);

  void haSetRole(DatabaseSession db, String nodeName, String role);

  void haSetOwner(DatabaseSession db, String collectionName, String owner);
}
