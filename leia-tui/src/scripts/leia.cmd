@echo off
rem Starts leia: chooses the JVM and runs the leia.jar next to this script (ADR 0034, section 8).
rem
rem The JVM is -j/--java <PATH> (a java.exe or a JDK/JRE directory), then %JAVA_HOME%\bin\java.exe,
rem then java.exe on PATH. LEIA_OPTS holds options for the JVM, such as -Dleia.log.level=FINE.
rem leia.jar checks the Java version and parses the other arguments.
rem
rem -Dsun.misc.unsafe.memory.access=allow comes first: it silences the warning Java 25 prints to the
rem JVM's own stderr when the old runtimes unmap, and lets them unmap (ADR 0047). LEIA_OPTS can
rem override it.
rem
rem Unlike the sh script, this one doesn't resolve symlinks. Limits of cmd: an argument with & ^ or %
rem needs quotes, and so does one with = , or ; which cmd splits at (--java=X arrives as --java X).
rem An empty argument ("") ends the arguments. If the JVM ends from Ctrl+C before the TUI has the
rem terminal, cmd asks "Terminate batch job (Y/N)?".
setlocal EnableExtensions DisableDelayedExpansion

rem Before any shift, which shifts %0 too.
set "jar=%~dp0leia.jar"
set "java="
set "args="

rem Takes -j/--java out of the arguments and keeps the others in order. The values of --jar and
rem --directory, and everything after --, are passed on as they are, even if they look like -j.
:parse
if "%~1"=="" goto parsed
if "%~1"=="-j" goto java
if "%~1"=="--java" goto java
if "%~1"=="--jar" goto value
if "%~1"=="--directory" goto value
if "%~1"=="--" goto rest
set args=%args% %1
shift
goto parse

:java
if "%~2"=="" goto novalue
set "java=%~2"
shift
shift
goto parse

:novalue
>&2 echo leia: %~1 needs a value ^<PATH^>
>&2 echo Usage: leia [OPTIONS] [INDEX_PATH]
>&2 echo For more information, try '--help'.
exit /b 2

:value
set args=%args% %1 %2
shift
shift
goto parse

:rest
set args=%args% %1
shift
if not "%~1"=="" goto rest

:parsed
if defined java goto option
if not defined JAVA_HOME goto path
if not exist "%JAVA_HOME%\bin\java.exe" goto path
set "exe=%JAVA_HOME%\bin\java.exe"
goto run

:path
set "exe="
for %%J in (java.exe) do set "exe=%%~$PATH:J"
if defined exe goto run
>&2 echo leia: no java found: pass -j/--java, set JAVA_HOME or put java.exe on PATH
exit /b 1

:option
set "attributes="
for %%A in ("%java%") do set "attributes=%%~aA"
if not defined attributes goto nojava
if not "%attributes:~0,1%"=="d" goto file
set "exe=%java%\bin\java.exe"
if exist "%exe%" goto run
>&2 echo leia: --java %java%: the directory has no bin\java.exe; pass a JDK/JRE directory or a java executable
exit /b 1

:file
set "exe=%java%"
goto run

:nojava
>&2 echo leia: --java %java%: no such executable; pass a JDK/JRE directory or a java executable
exit /b 1

:run
if exist "%jar%" goto launch
>&2 echo leia: leia.jar not found next to leia.cmd (looked for %jar%)
exit /b 1

:launch
"%exe%" -Dsun.misc.unsafe.memory.access=allow %LEIA_OPTS% -jar "%jar%" %args%
exit /b %ERRORLEVEL%
