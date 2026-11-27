#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    printf '%s\n' 'Usage: sh prepare-engine.sh NEW_BUILD_DIRECTORY' >&2
    exit 2
fi

adapter=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
mkdir -- "$1"
build=$(CDPATH='' cd -- "$1" && pwd)
mcclient_revision=12179b06709e4d2161483e87065b35f292898783
baritone_revision=41e1b720dc2028d06618cb8f7cf55f2697bba83c

fetch_source() {
    repo=$1
    revision=$2
    directory=$3
    mkdir -- "$directory"
    curl --fail --silent --show-error --location "https://codeload.github.com/$repo/tar.gz/$revision" \
        --output "$directory/source.tar.gz"
    tar -xzf "$directory/source.tar.gz" --strip-components=1 -C "$directory"
    rm -- "$directory/source.tar.gz"
}

fetch_source MVRer/mcclient "$mcclient_revision" "$build/mcclient"
fetch_source luhnox/horizon-baritone "$baritone_revision" "$build/horizon-baritone"
git -C "$build/mcclient" apply "$adapter/../mcclient-26.3-state.patch"
git -C "$build/mcclient" apply "$adapter/engine.patch"
git -C "$build/mcclient" apply "$adapter/../mcclient-26.3-furnace.patch"
cp -- "$adapter/native_baritone.rs" "$build/mcclient/src/native_baritone.rs"
cargo build --locked --release --manifest-path "$build/mcclient/Cargo.toml"
printf 'Headless Baritone engine: %s\n' "$build/mcclient/target/release/mcclient"
