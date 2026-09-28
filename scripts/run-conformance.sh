#!/usr/bin/env bash
#
# Build the Java conformance harness and run the official Avo Inspector conformance suite
# (avohq/spec-first-inspector-server-sdk) against it.
#
# Usage:
#   ./scripts/run-conformance.sh
#
# Environment overrides:
#   SPEC_DIR       local checkout of the spec repo to use as-is (no fetch)
#   SPEC_REPO_URL  git URL of the spec repo (default: the public avohq repo)
#   SPEC_REF       commit to fetch into .spec-repo/ (default: the spec 3.0.1 commit below)
#   JAVA_HOME      JDK used for the build and the harness (8 or newer)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPEC_REPO_URL="${SPEC_REPO_URL:-https://github.com/avohq/spec-first-inspector-server-sdk.git}"
# Spec 3.0.1 (runner contract 1.1.0, 36 fixtures).
SPEC_REF="${SPEC_REF:-7b79318f8cf1fa6de0142c698e37bb5d18e2d678}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
HARNESS_JAR="$ROOT/build/conformance/avo-inspector-conformance.jar"

echo "==> Building conformance harness"
"$ROOT/gradlew" -p "$ROOT" --quiet conformanceJar

if [ -n "${SPEC_DIR:-}" ]; then
  echo "==> Using spec checkout at $SPEC_DIR"
else
  SPEC_DIR="$ROOT/.spec-repo"
  echo "==> Fetching spec repo @ $SPEC_REF"
  # Fail closed: fetch exactly SPEC_REF and hard-checkout it, discarding any local drift.
  if [ ! -d "$SPEC_DIR/.git" ]; then
    git init --quiet "$SPEC_DIR"
    git -C "$SPEC_DIR" remote add origin "$SPEC_REPO_URL"
  fi
  # An existing checkout may point elsewhere (an old URL, or another SPEC_REPO_URL).
  git -C "$SPEC_DIR" remote set-url origin "$SPEC_REPO_URL"
  git -C "$SPEC_DIR" fetch --quiet --depth 1 origin "$SPEC_REF"
  git -C "$SPEC_DIR" -c advice.detachedHead=false checkout --quiet --force FETCH_HEAD
  echo "    spec @ $(git -C "$SPEC_DIR" rev-parse --short HEAD)"
fi

# The suite runner splits --harness on whitespace and does not honor quotes, so a path with
# spaces (the checkout or JAVA_HOME) is reached through a symlink in a space-free temp directory.
case "$JAVA$HARNESS_JAR" in
  *[[:space:]]*)
    LINK_DIR="$(mktemp -d /tmp/avo-harness.XXXXXX)"
    trap 'rm -rf "$LINK_DIR"' EXIT
    case "$JAVA" in
      *[[:space:]]*) ln -s "$JAVA" "$LINK_DIR/java"; JAVA="$LINK_DIR/java" ;;
    esac
    case "$HARNESS_JAR" in
      *[[:space:]]*) ln -s "$HARNESS_JAR" "$LINK_DIR/avo-inspector-conformance.jar"; HARNESS_JAR="$LINK_DIR/avo-inspector-conformance.jar" ;;
    esac
    ;;
esac

echo "==> Running conformance suite"
node "$SPEC_DIR/conformance/runner/suite-runner.mjs" --harness "$JAVA -jar $HARNESS_JAR"
