#!/bin/sh
# Maven Wrapper script

set -e

# Find the project base directory
MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd -P)}"
export MAVEN_PROJECTBASEDIR

# Download Maven if not present
MAVEN_HOME="${MAVEN_HOME:-$HOME/.m2/wrapper/dists/apache-maven-3.9.6}"

if [ ! -d "$MAVEN_HOME" ]; then
    echo "Downloading Maven..."
    mkdir -p "$MAVEN_HOME"
    curl -L -o /tmp/maven.zip https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip
    unzip -q /tmp/maven.zip -d "$HOME/.m2/wrapper/dists/"
    rm /tmp/maven.zip
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
