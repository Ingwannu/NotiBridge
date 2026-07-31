#!/bin/sh

##############################################################################
# Gradle start up script for POSIX generated for NotiBridge.
##############################################################################

APP_HOME=$(cd "${0%/*}" >/dev/null 2>&1; pwd -P) || exit

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
