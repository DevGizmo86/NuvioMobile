#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
PROXY_TEST_CACHE="${XDG_CACHE_HOME:-${TMPDIR:-/tmp}}/nuvio-proxy-tests"
mkdir -p "$PROXY_TEST_CACHE"
fetch() {
  local group="$1" artifact="$2" version="$3"
  local jar="$PROXY_TEST_CACHE/$artifact-$version.jar"
  if [ ! -s "$jar" ]; then
    curl --fail --silent --show-error --location --retry 2 \
      "https://repo.maven.apache.org/maven2/$group/$artifact/$version/$artifact-$version.jar" \
      -o "$jar.part"
    mv "$jar.part" "$jar"
  fi
  PROXY_TEST_CLASSPATH="${PROXY_TEST_CLASSPATH:+$PROXY_TEST_CLASSPATH:}$jar"
}
PROXY_TEST_CLASSPATH=""
fetch org/jetbrains/kotlin kotlin-compiler-embeddable 2.3.10
fetch org/jetbrains/kotlin kotlin-stdlib 2.3.10
fetch org/jetbrains/kotlin kotlin-script-runtime 2.3.10
fetch org/jetbrains/kotlin kotlin-reflect 1.6.10
fetch org/jetbrains/kotlin kotlin-daemon-embeddable 2.3.10
fetch org/jetbrains/kotlinx kotlinx-coroutines-core-jvm 1.8.0
fetch org/jetbrains annotations 13.0
fetch com/squareup/okhttp3 okhttp 4.12.0
fetch com/squareup/okio okio-jvm 3.6.0
fetch junit junit 4.13.2
fetch org/hamcrest hamcrest-core 1.3
java -cp "$PROXY_TEST_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$PROXY_TEST_CLASSPATH" -d "$PROXY_TEST_CACHE/tests.jar" \
  composeApp/src/androidMain/kotlin/com/nuvio/app/features/proxy/HlsManifestRewriter.kt \
  composeApp/src/androidMain/kotlin/com/nuvio/app/features/proxy/LocalStreamProxy.kt \
  composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/proxy/LocalStreamProxyTest.kt
java -cp "$PROXY_TEST_CLASSPATH:$PROXY_TEST_CACHE/tests.jar" org.junit.runner.JUnitCore \
  com.nuvio.app.features.proxy.LocalStreamProxyTest
