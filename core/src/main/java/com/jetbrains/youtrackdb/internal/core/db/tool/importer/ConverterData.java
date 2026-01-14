package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Set;

/**
 *
 */
public class ConverterData {

  protected DatabaseSession session;
  protected Set<RID> brokenRids;

  public ConverterData(DatabaseSession session, Set<RID> brokenRids) {
    this.session = session;
    this.brokenRids = brokenRids;
  }
}
