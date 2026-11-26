#!/bin/sh
set -eu
project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project"
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
javac --release 17 -Xlint:all -Werror -encoding UTF-8 -d "$test_dir" \
    app/src/main/java/local/cmcc/usage/AccountPhone.java \
    app/src/main/java/local/cmcc/usage/Carrier.java \
    app/src/main/java/local/cmcc/usage/QueryVisit.java \
    app/src/main/java/local/cmcc/usage/CarrierReadings.java tests/CarrierReadingsTest.java
java -cp "$test_dir" local.cmcc.usage.CarrierReadingsTest
for script in module/*.sh; do sh -n "$script"; done
sh tests/CustomizeTest.sh
sh tests/RootToolsTest.sh
sh tests/ReaderRestoreTest.sh
