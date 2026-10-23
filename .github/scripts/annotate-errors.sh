#!/usr/bin/env bash
# Re-emits compiler errors and test failures from a Gradle log as GitHub annotations,
# so they are visible on the run summary without opening the full log.
log="${1:-build.log}"
[ -f "$log" ] || exit 0
{
  grep -E '^e: |^\s*error: |FAILED$|AssertionError|Exception|expected:<|What went wrong' -A2 "$log"
  for f in app/build/test-results/*/*.xml; do
    [ -f "$f" ] && grep -oE '<failure message="[^"]{0,400}' "$f" | sed 's/<failure message=/test failure: /'
  done
} | grep -v '^--$' | head -40 | while IFS= read -r line; do
  echo "::error::${line//$'\r'/}"
done
exit 0
