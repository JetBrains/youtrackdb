@echo off
rem
rem Guess YOUTRACKDB_HOME if not defined
set CURRENT_DIR=%cd%

if exist "%JAVA_HOME%\bin\java.exe" goto setJavaHome
set JAVA="java"
goto okJava

:setJavaHome
set JAVA="%JAVA_HOME%\bin\java"

:okJava
if not "%YOUTRACKDB_HOME%" == "" goto gotHome
set YOUTRACKDB_HOME=%CURRENT_DIR%
if exist "%YOUTRACKDB_HOME%\bin\console.bat" goto okHome
cd ..
set YOUTRACKDB_HOME=%cd%
cd %CURRENT_DIR%

:gotHome
if exist "%YOUTRACKDB_HOME%\bin\console.bat" goto okHome
echo The YOUTRACKDB_HOME environment variable is not defined correctly
echo This environment variable is needed to run this program
goto end

:okHome
rem Get remaining unshifted command line arguments and save them in the
set CMD_LINE_ARGS=

:setArgs
if ""%1""=="""" goto doneSetArgs
set CMD_LINE_ARGS=%CMD_LINE_ARGS% %1
shift
goto setArgs

:doneSetArgs

set KEYSTORE=%YOUTRACKDB_HOME%\config\cert\youtrackdb-console.ks
set KEYSTORE_PASS=password
set TRUSTSTORE=%YOUTRACKDB_HOME%\config\cert\youtrackdb-console.ts
set TRUSTSTORE_PASS=password
set SSL_OPTS="-Dyoutrackdb.client.ssl.enabled=false -Djavax.net.ssl.keyStore=%KEYSTORE% -Djavax.net.ssl.keyStorePassword=%KEYSTORE_PASS% -Djavax.net.ssl.trustStore=%TRUSTSTORE% -Djavax.net.ssl.trustStorePassword=%TRUSTSTORE_PASS%"

set YOUTRACKDB_SETTINGS=-Xmx1024m -Djna.nosys=true -Djava.util.logging.config.file="%YOUTRACKDB_HOME%\config\youtrackdb-client-log.properties" -Djava.awt.headless=true

call %JAVA% -client %SSL_OPTS% %YOUTRACKDB_SETTINGS% -Dfile.encoding=utf-8 -Dyoutrackdb.build.number="@BUILD@" -cp "%YOUTRACKDB_HOME%\lib\*;%YOUTRACKDB_HOME%\plugins\*" com.jetbrains.youtrack.db.internal.tools.console.ConsoleDatabaseApp %CMD_LINE_ARGS%

:end
