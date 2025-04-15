package com.jetbrains.youtrack.db.internal.server.monitoring;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("com.jetbrains.youtrack.db.server.BinaryProtocolRequest")
@Category({"YouTrackDB", "Server"})
@Label("Binary protocol request")
@Description("Binary protocol request")
@Enabled(false)
public class BinaryProtocolRequestEvent extends jdk.jfr.Event {

}