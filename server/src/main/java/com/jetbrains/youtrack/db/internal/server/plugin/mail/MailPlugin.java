/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.server.plugin.mail;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.script.ScriptInjection;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginAbstract;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginConfigurable;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.script.Bindings;
import javax.script.ScriptEngine;

public class MailPlugin extends ServerPluginAbstract
    implements ScriptInjection, ServerPluginConfigurable {

  private static final String CONFIG_PROFILE_PREFIX = "profile.";
  private static final String CONFIG_MAIL_PREFIX = "mail.";

  private EntityImpl configuration;

  private final Map<String, MailProfile> profiles = new HashMap<String, MailProfile>();

  private final String configFile = "${YOUTRACKDB_HOME}/config/mail.json";

  public MailPlugin() {
  }

  @Override
  public void config(final YouTrackDBServer youTrackDBServer,
      final ServerParameterConfiguration[] iParams) {
    YouTrackDBInternal.extract(youTrackDBServer.getContext()).getScriptManager()
        .registerInjection(this);
  }

  public void writeConfiguration() throws IOException {
  }

  /**
   * Sends an email. Supports the following configuration: subject, message, to, cc, bcc, date,
   * attachments
   *
   * @param iMessage Configuration as Map<String,Object>
   * @throws ParseException
   */
  public void send(final Map<String, Object> iMessage) {
    LogManager.instance().warn(this, "Mail send is non available in this YouTrackDB version");
  }

  @Override
  public void bind(ScriptEngine engine, Bindings binding, DatabaseSession database) {
    binding.put("mail", this);
  }

  @Override
  public void unbind(ScriptEngine engine, Bindings binding) {
    binding.put("mail", null);
  }

  @Override
  public String getName() {
    return "mail";
  }

  public Set<String> getProfileNames() {
    return profiles.keySet();
  }

  public MailProfile getProfile(final String iName) {
    return profiles.get(iName);
  }

  public MailPlugin registerProfile(final String iName, final MailProfile iProfile) {
    return this;
  }

  @Override
  public EntityImpl getConfig() {
    return configuration;
  }

  @Override
  public void changeConfig(EntityImpl entity) {
  }
}
