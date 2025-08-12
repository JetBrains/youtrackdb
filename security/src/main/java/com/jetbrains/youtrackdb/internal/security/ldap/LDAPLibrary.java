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
package com.jetbrains.youtrackdb.internal.security.ldap;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.PrivilegedAction;
import java.util.Hashtable;
import java.util.List;
import javax.annotation.Nullable;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.security.auth.Subject;

/**
 * LDAP Library
 */
public class LDAPLibrary {

  public static DirContext openContext(
      final Subject subject, final List<LDAPServer> ldapServers, final boolean debug) {
    return Subject.doAs(
        subject,
        new PrivilegedAction<DirContext>() {
          public DirContext run() {
            DirContext dc = null;

            // Set up environment for creating initial context
            var env = new Hashtable<String, String>();

            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");

            // Request the use of the "GSSAPI" SASL mechanism
            // Authenticate by using already established Kerberos credentials
            env.put(Context.SECURITY_AUTHENTICATION, "GSSAPI");

            for (var ldap : ldapServers) {
              try {
                var url = ldap.getURL();

                // If the LDAPServer info is marked as an alias, then the real hostname needs to be
                // acquired.
                if (ldap.isAlias()) {
                  url = getRealURL(ldap, debug);
                }

                // Must use fully qualified hostname
                env.put(Context.PROVIDER_URL, url);

                if (debug) {
                  LogManager.instance()
                      .info(
                          LDAPLibrary.class,
                          "LDAPLibrary.openContext() Trying ProviderURL: " + url);
                }

                // Create initial context
                dc = new InitialDirContext(env);
                break;
              } catch (Exception ex) {
                LogManager.instance()
                    .error(LDAPLibrary.class, "LDAPLibrary.openContext() Exception: ", ex);
              }
            }

            return dc;
          }
        });
  }

  // If the LDAPServer's isAlias() returns true, then the specified hostname is an alias, requiring
  // a reverse
  // look-up of its IP address to resolve the real hostname to use.  This is often used with DNS
  // round-robin.
  private static String getRealURL(LDAPServer ldap, final boolean debug)
      throws UnknownHostException {
    var realURL = ldap.getURL();

    if (ldap.isAlias()) {
      if (debug) {
        LogManager.instance()
            .info(
                LDAPLibrary.class,
                "LDAPLibrary.getRealURL() Alias hostname = " + ldap.getHostname());
      }

      // Get the returned IP address from the alias.
      // May throw an UnknownHostException
      var ipAddress = InetAddress.getByName(ldap.getHostname());

      if (debug) {
        LogManager.instance()
            .info(
                LDAPLibrary.class,
                "LDAPLibrary.getRealURL() IP Address = " + ipAddress.getHostAddress());
      }

      // Now that we have the IP address, use it to get the real hostname.
      // We create a new InetAddress object, because hostnames are cached.
      var realAddress = InetAddress.getByName(ipAddress.getHostAddress());

      if (debug) {
        LogManager.instance()
            .info(
                LDAPLibrary.class,
                "LDAPLibrary.getRealURL() Real hostname = " + realAddress.getHostName());
      }

      realURL = ldap.getURL(realAddress.getHostName());

      if (debug) {
        LogManager.instance()
            .info(LDAPLibrary.class, "LDAPLibrary.getRealURL() Real URL = " + realURL);
      }
    }

    return realURL;
  }

  public static void retrieveUsers(
      DirContext ctx,
      final String baseDN,
      final String filter,
      final List<String> principalList,
      final boolean debug) {
    try {
      if (ctx != null) {
        // If we're just obtaining users matching a filterDN, switch to a SearchControl.
        //				traverse(ctx, startingDN, filterDN, principalList, debug);

        var sctls = new SearchControls();
        sctls.setSearchScope(SearchControls.SUBTREE_SCOPE); // Recursive

        var attribFilter = new String[]{"userPrincipalName", "altSecurityIdentities"};
        sctls.setReturningAttributes(attribFilter);

        NamingEnumeration ne = ctx.search(baseDN, filter, sctls); // "(userPrincipalName=*)"

        while (ne.hasMore()) {
          var sr = (SearchResult) ne.next();

          addPrincipal(sr, principalList, debug);
        }
      } else {
        if (debug) {
          LogManager.instance()
              .error(LDAPLibrary.class, "LDAPLibrary.retrieveUsers() DirContext is null", null);
        }
      }
    } catch (Exception ex) {
      LogManager.instance()
          .error(LDAPLibrary.class, "LDAPLibrary.retrieveUsers() Exception: ", ex);
    }
  }

  private static void addPrincipal(
      SearchResult sr, List<String> principalList, final boolean debug) {
    try {
      var attrs = sr.getAttributes();

      if (attrs != null) {
        /*
        			// userPrincipalName
        			String upn = getUserPrincipalName(attrs);

        			if(debug) LogManager.instance().info(null, "LDAPLibrary.addPrincipal() userPrincipalName: " + upn);

        			if(upn != null)
        			{
        				// Some UPNs, especially in 'altSecurityIdentities' will store the UPNs as such "Kerberos: user@realm.com"
        				upn = removeKerberos(upn, debug);

        				principalList.add(upn); //upn.toLowerCase());
        			}
        */
        fillAttributeList(attrs, "userPrincipalName", principalList, debug);

        fillAttributeList(attrs, "altSecurityIdentities", principalList, debug);
      }
    } catch (Exception ex) {
      LogManager.instance()
          .error(LDAPLibrary.class, "LDAPLibrary.addPrincipal() Exception: ", ex);
    }
  }

  private static void traverse(
      DirContext ctx,
      String startingDN,
      String memberOfFilter,
      List<String> principalList,
      final boolean debug) {
    try {
      if (debug) {
        LogManager.instance()
            .info(
                LDAPLibrary.class,
                "LDAPLibrary.traverse() startingDN: %s, memberOfFilter: %s",
                startingDN,
                memberOfFilter);
      }

      var attrs = ctx.getAttributes(startingDN);

      if (attrs != null) {
        if (debug) {
          LogManager.instance()
              .info(
                  LDAPLibrary.class,
                  "LDAPLibrary.traverse() Found attributes for startingDN: %s",
                  startingDN);
        }

        var member = attrs.get("member");

        if (member != null) {
          for (NamingEnumeration ae = member.getAll(); ae.hasMore(); ) {
            var path = (String) ae.next();

            findMembers(ctx, path, memberOfFilter, principalList, debug);
          }
        } else {
          if (debug) {
            LogManager.instance()
                .info(
                    LDAPLibrary.class,
                    "LDAPLibrary.traverse() startingDN: %s has no \"member\" attributes.",
                    startingDN);
          }
        }
      } else {
        if (debug) {
          LogManager.instance()
              .error(
                  LDAPLibrary.class,
                  "LDAPLibrary.traverse() Unable to find attributes for startingDN: %s",
                  null,
                  startingDN);
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(LDAPLibrary.class, "LDAPLibrary.traverse() Exception: ", ex);
    }
  }

  private static void findMembers(
      DirContext ctx,
      String startingDN,
      String memberOfFilter,
      List<String> principalList,
      final boolean debug) {
    try {
      var attrs = ctx.getAttributes(startingDN);

      if (attrs != null) {
        if (debug) {
          LogManager.instance()
              .info(
                  LDAPLibrary.class,
                  "LDAPLibrary.findMembers() Found attributes for startingDN: %s",
                  startingDN);
        }

        if (isGroup(attrs)) {
          if (debug) {
            LogManager.instance()
                .info(
                    LDAPLibrary.class,
                    "LDAPLibrary.findMembers() Found group for startingDN: %s",
                    startingDN);
          }

          var member = attrs.get("member");

          if (member != null) {
            for (NamingEnumeration ae = member.getAll(); ae.hasMore(); ) {
              var path = (String) ae.next();
              findMembers(ctx, path, memberOfFilter, principalList, debug);
            }
          }
        } else if (isUser(attrs)) {
          if (debug) {
            LogManager.instance()
                .info(
                    LDAPLibrary.class,
                    "LDAPLibrary.findMembers() Found user for startingDN: %s",
                    startingDN);
          }

          if (isMemberOf(attrs, memberOfFilter)) {
            // userPrincipalName
            var upn = getUserPrincipalName(attrs);

            if (debug) {
              LogManager.instance()
                  .info(
                      LDAPLibrary.class,
                      "LDAPLibrary.findMembers() StartingDN: "
                          + startingDN
                          + ", userPrincipalName: "
                          + upn);
            }

            if (upn != null) {
              // Some UPNs, especially in 'altSecurityIdentities' will store the UPNs as such
              // "Kerberos: user@realm.com"
              upn = removeKerberos(upn, debug);
              principalList.add(upn); // upn.toLowerCase());
            }

            fillAttributeList(attrs, "altSecurityIdentities", principalList, debug);
          }
        }
      } else {
        LogManager.instance()
            .error(
                LDAPLibrary.class,
                "LDAPLibrary.findMembers() Unable to find attributes for startingDN: %s",
                null,
                startingDN);
      }
    } catch (Exception ex) {
      LogManager.instance()
          .error(LDAPLibrary.class, "LDAPLibrary.findMembers() Exception: ", ex);
    }
  }

  // Separates the distinguished name and returns the top-level name.
  private static String getName(final String dn) {
    String name = null;

    var names = dn.split(",");

    if (names.length >= 1) {
      // >= 4 because "CN=" is 3
      if (names[0].length() >= 4) {
        name = names[0].substring(3);
      }
    }

    return name;
  }

  private static void fillAttributeList(
      Attributes attrs, String name, List<String> list, final boolean debug) {
    try {
      var attribute = attrs.get(name);

      if (attribute != null && attribute.size() > 0) {
        var ne = attribute.getAll();
        while (ne.hasMore()) {
          var value = (String) ne.next();

          // Some UPNs, especially in 'altSecurityIdentities' will store the UPNs as such "Kerberos:
          // user@realm.com"
          value = removeKerberos(value, debug);

          list.add(value); // value.toLowerCase());
        }
      }
    } catch (Exception ex) {
      LogManager.instance()
          .error(LDAPLibrary.class, "LDAPLibrary fillAttributeList(" + name + ")", ex);
    }
  }

  @Nullable
  private static String getFirstValue(Attributes attrs, String name) {
    try {
      var attribute = attrs.get(name);

      if (attribute != null && attribute.size() > 0) {
        return (String) attribute.get(0);
      }
    } catch (Exception ex) {
      LogManager.instance()
          .error(LDAPLibrary.class, "LDAPLibrary.getFirstValue(" + name + ") ", ex);
    }

    return null;
  }

  private static String getUserPrincipalName(Attributes attrs) {
    return getFirstValue(attrs, "userPrincipalName");
  }

  private static boolean isGroup(Attributes attrs) {
    var objCategoryDN = getFirstValue(attrs, "objectCategory");

    if (objCategoryDN != null) {
      var objCategory = getName(objCategoryDN);

      return objCategory.equalsIgnoreCase("Group");
    }

    return false;
  }

  private static boolean isUser(Attributes attrs) {
    var objCategoryDN = getFirstValue(attrs, "objectCategory");

    if (objCategoryDN != null) {
      var objCategory = getName(objCategoryDN);

      return objCategory.equalsIgnoreCase("User") || objCategory.equalsIgnoreCase("Person");
    }

    return false;
  }

  private static boolean isMemberOf(Attributes attrs, String memberOfFilter) {
    try {
      var memberOfAttr = attrs.get("memberOf");

      if (memberOfAttr != null) {
        for (NamingEnumeration mo = memberOfAttr.getAll(); mo.hasMore(); ) {
          var value = (String) mo.next();

          if (value.equalsIgnoreCase(memberOfFilter)) {
            return true;
          }
        }
      } else {
        LogManager.instance()
            .error(
                LDAPLibrary.class, "LDAPLibrary.isMemberOf() Has no 'memberOf' attribute.", null);
      }
    } catch (Exception ex) {
      LogManager.instance().error(LDAPLibrary.class, "LDAPLibrary.isMemberOf()", ex);
    }

    return false;
  }

  // Some UPNs, especially in 'altSecurityIdentities' will store the UPNs as such "Kerberos:
  // user@realm.com"
  private static String removeKerberos(String upn, final boolean debug) {
    if ((upn.startsWith("kerberos:") || upn.startsWith("Kerberos:")) && upn.length() > 9) {
      if (debug) {
        LogManager.instance()
            .info(LDAPLibrary.class, "LDAPLibrary.removeKerberos() upn before: %s", upn);
      }

      upn = upn.substring(9);
      upn.trim();

      if (debug) {
        LogManager.instance()
            .info(LDAPLibrary.class, "LDAPLibrary.removeKerberos() upn after: %s", upn);
      }
    }

    return upn;
  }
}
