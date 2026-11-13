#!/bin/zsh
# Builds the "play" variant (talks to staging.mildcrowd.com) and publishes it to Google Play's internal test track –
# the counterpart of oasis-ios/scripts/testflight.sh. Runs on this Mac by hand; CI does the same in
# .github/workflows/play-internal.yml.
#
# Needs, all outside the repository:
#   - the upload key under ~/.android-keys/ (oasis-upload.jks and oasis-upload.properties, see docs/play-console.md),
#   - the JSON key of the service account invited in the Play Console with "Apps in Test-Tracks veröffentlichen":
#     ~/.android-keys/play-service-account.json, or PLAY_SERVICE_ACCOUNT_JSON pointing at another file,
#   - OASIS_GATE_KEY in secrets.properties or the environment (the staging gate; the script stops without it).
# The version code is the commit time (app/build.gradle.kts), so commit first: Play refuses a code it already has.
#
# Usage: scripts/play-internal.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export PLAY_SERVICE_ACCOUNT_JSON="${PLAY_SERVICE_ACCOUNT_JSON:-$HOME/.android-keys/play-service-account.json}"
if [[ ! -f "$PLAY_SERVICE_ACCOUNT_JSON" ]]; then
    echo "Service account key missing: $PLAY_SERVICE_ACCOUNT_JSON (see docs/play-console.md)." >&2
    exit 1
fi
# The staging gate key, looked up like app/build.gradle.kts does (the environment, otherwise secrets.properties). A build
# without it sends no X-Oasis-Gate header: the staging server refuses every call and testers cannot sign in.
gate_key=${OASIS_GATE_KEY-}
if (( ! ${+OASIS_GATE_KEY} )) && [[ -f secrets.properties ]]; then
    gate_key=$(sed -n 's/^[[:space:]]*OASIS_GATE_KEY[[:space:]]*[=:][[:space:]]*//p' secrets.properties | tail -n 1)
fi
if [[ -z "$gate_key" ]]; then
    echo "OASIS_GATE_KEY missing: set it in secrets.properties or the environment (the staging gate, see secrets.properties.example)." >&2
    exit 1
fi
unset gate_key
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21 ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi

echo "Building and publishing the play variant to the internal test track …"
./gradlew :app:publishPlayBundle --console=plain
echo "Published; testers get it once Google Play has processed it."
