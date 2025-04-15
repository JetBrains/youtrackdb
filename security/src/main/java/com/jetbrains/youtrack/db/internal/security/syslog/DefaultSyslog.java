/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrack.db.internal.security.syslog;

import com.cloudbees.syslog.Facility;
import com.cloudbees.syslog.MessageFormat;
import com.cloudbees.syslog.Severity;
import com.cloudbees.syslog.SyslogMessage;
import com.cloudbees.syslog.sender.UdpSyslogMessageSender;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.security.Syslog;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginAbstract;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;

/**
 * Provides a default implementation for syslog access.
 */
public class DefaultSyslog extends ServerPluginAbstract implements Syslog {

  private boolean debug = false;
  private String hostname = "localhost";
  private int port = 514; // Default syslog UDP port.
  private String appName = "YouTrackDB";

  private UdpSyslogMessageSender messageSender;

  // SecurityComponent

  @Override
  public void startup() {
    try {
      if (enabled) {
        messageSender = new UdpSyslogMessageSender();
        // _MessageSender.setDefaultMessageHostname("myhostname");
        // _MessageSender.setDefaultAppName(_AppName);
        // _MessageSender.setDefaultFacility(Facility.USER);
        // _MessageSender.setDefaultSeverity(Severity.INFORMATIONAL);
        messageSender.setSyslogServerHostname(hostname);
        messageSender.setSyslogServerPort(port);
        messageSender.setMessageFormat(MessageFormat.RFC_3164); // optional, default is RFC 3164
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultSyslog.active()", ex);
    }
  }

  @Override
  public void config(YouTrackDBServer youTrackDBServer, ServerParameterConfiguration[] iParams) {
    enabled = false;

    for (var param : iParams) {
      if (param.name.equalsIgnoreCase("enabled")) {
        enabled = Boolean.parseBoolean(param.value);
        if (!enabled)
        // IGNORE THE REST OF CFG
        {
          return;
        }
      } else if (param.name.equalsIgnoreCase("debug")) {
        debug = Boolean.parseBoolean(param.value);
      } else if (param.name.equalsIgnoreCase("hostname")) {
        hostname = param.value;
      } else if (param.name.equalsIgnoreCase("port")) {
        port = Integer.parseInt(param.value);
      } else if (param.name.equalsIgnoreCase("appName")) {
        appName = param.value;
      }
    }
  }

  @Override
  public void shutdown() {
    messageSender = null;
  }

  // SecurityComponent
  public boolean isEnabled() {
    return enabled;
  }

  // Syslog
  public void log(final String operation, final String message) {
    log(operation, null, null, message);
  }

  // Syslog
  public void log(final String operation, final String username, final String message) {
    log(operation, null, username, message);
  }

  // Syslog
  public void log(
      final String operation, final String dbName, final String username, final String message) {
    try {
      if (messageSender != null) {
        var sysMsg = new SyslogMessage();

        sysMsg.setFacility(Facility.USER);
        sysMsg.setSeverity(Severity.INFORMATIONAL);

        sysMsg.setAppName(appName);

        // Sylog ignores these settings.
        // if(operation != null) sysMsg.setMsgId(operation);
        // if(dbName != null) sysMsg.setProcId(dbName);

        var sb = new StringBuilder();

        if (operation != null) {
          sb.append("[");
          sb.append(operation);
          sb.append("] ");
        }

        if (dbName != null) {
          sb.append("Database: ");
          sb.append(dbName);
          sb.append(" ");
        }

        if (username != null) {
          sb.append("Username: ");
          sb.append(username);
          sb.append(" ");
        }

        if (message != null) {
          sb.append(message);
        }

        sysMsg.withMsg(sb.toString());

        messageSender.sendMessage(sysMsg);
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "DefaultSyslog.log()", ex);
    }
  }

  @Override
  public String getName() {
    return "syslog";
  }
}
