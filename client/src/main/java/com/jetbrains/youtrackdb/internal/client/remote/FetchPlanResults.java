package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.api.record.DBRecord;
import java.util.Set;

public interface FetchPlanResults {

  Set<DBRecord> getFetchedRecordsToSend();
}
