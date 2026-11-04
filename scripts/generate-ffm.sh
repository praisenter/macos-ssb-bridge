#!/bin/bash
set -euo pipefail

usage() {
    printf '%s\n' 'Usage: bash scripts/generate-ffm.sh [--jextract PATH] [--sdk PATH] [--check]'
    printf '%s\n' 'Requires macOS, jextract 22, and a JDK 22+ (JAVA_HOME or javac on PATH).'
}

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

jextract=${JEXTRACT:-jextract}
sdk=''
check=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --jextract|--sdk)
            [ "$#" -ge 2 ] && [ -n "$2" ] || fail "$1 requires a path"
            if [ "$1" = '--jextract' ]; then jextract=$2; else sdk=$2; fi
            shift 2
            ;;
        --check) check=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; fail "Unknown argument: $1" ;;
    esac
done

[ "$(uname -s)" = 'Darwin' ] || fail 'Run this script on macOS; cross-platform normalization is not performed.'
architecture=$(uname -m)
case "$architecture" in arm64|x86_64) ;; *) fail "Unsupported macOS architecture: $architecture" ;; esac
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
jextract=$(command -v "$jextract") || fail 'jextract not found; supply --jextract or set JEXTRACT.'
case "$jextract" in
    /*) ;;
    *) jextract="$(cd "$(dirname "$jextract")" && pwd -P)/$(basename "$jextract")" ;;
esac
version=$("$jextract" --version 2>&1)
printf '%s\n' "$version" | grep -Eq '^jextract 22([[:space:]]|$)' ||
    fail 'Use jextract 22 to generate Java 22-compatible code without post-processing.'
if [ -z "$sdk" ]; then sdk=$(xcrun --sdk macosx --show-sdk-path); fi
sdk=$(cd "$sdk" && pwd -P)
frameworks="$sdk/System/Library/Frameworks"
[ -f "$frameworks/CoreFoundation.framework/Headers/CFURL.h" ] || fail "Core Foundation headers not found in $sdk"
if [ -n "${JAVA_HOME:-}" ]; then
    javac="$JAVA_HOME/bin/javac"
else
    javac=$(command -v javac) || fail 'Set JAVA_HOME or put javac on PATH.'
fi
[ -x "$javac" ] || fail "javac is not executable: $javac"

work=$(mktemp -d "${TMPDIR:-/tmp}/ssb-jextract.XXXXXX")
trap 'rm -rf "$work"' EXIT
printf '%s\n' '#include <CoreFoundation/CFURL.h>' > "$work/bookmarks.h"
printf '%s\n' '-isysroot' "$sdk" '-F' "$frameworks" '-I' "$sdk/usr/include" > "$work/compile_flags.txt"
package='org.praisenter.macos.ssb.internal.generated'
package_path=${package//./\/}
functions=(
    CFStringCreateWithBytes CFStringGetLength CFStringGetMaximumSizeForEncoding
    CFStringGetCString CFURLCreateWithFileSystemPath CFURLCreateBookmarkData
    CFURLCreateByResolvingBookmarkData CFURLGetString CFDataCreate
    CFDataGetLength CFDataGetBytePtr CFURLStartAccessingSecurityScopedResource
    CFURLStopAccessingSecurityScopedResource CFRelease CFErrorGetDomain
    CFErrorGetCode CFErrorCopyDescription
)
constants=(
    kCFURLPOSIXPathStyle kCFURLBookmarkCreationWithSecurityScope
    kCFURLBookmarkResolutionWithSecurityScope kCFStringEncodingUTF8
)
arguments=(
    --output "$work/sources" --target-package "$package"
    --header-class-name CoreFoundationBindings
    --library ':/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation'
)
for function in "${functions[@]}"; do arguments+=(--include-function "$function"); done
for constant in "${constants[@]}"; do arguments+=(--include-constant "$constant"); done
(
    cd "$work"
    "$jextract" "${arguments[@]}" "$work/bookmarks.h"
)
generated="$work/sources/$package_path"
root="$generated/CoreFoundationBindings.java"
[ -f "$root" ] || fail 'jextract did not generate CoreFoundationBindings.java.'
for function in "${functions[@]}"; do
    grep -Eq "public static .* ${function}\(" "$root" || fail "Required function not generated: $function"
done
for constant in "${constants[@]}"; do
    grep -Eq "public static .* ${constant}\(" "$root" || fail "Required constant not generated: $constant"
done
"$javac" --release 22 -d "$work/classes" "$generated"/*.java

destination="$project_root/src/main/java/$package_path"
if "$check"; then
    diff -ru "$destination" "$generated" || fail 'Regenerated sources differ from the checked-in bindings.'
else
    if [ -d "$destination" ]; then
        for existing in "$destination"/*; do
            [ -e "$existing" ] || continue
            [ -f "$existing" ] && [ -f "$generated/$(basename "$existing")" ] ||
                fail "Unexpected existing file; refusing to overwrite: $existing"
        done
    fi
    mkdir -p "$destination"
    cp "$generated"/*.java "$destination/"
fi
printf 'Generated and compiled unmodified macOS bindings (%s).\n' "$architecture"
printf 'SDK: %s\n' "$sdk"
printf '%s\n' "$version"
wc -l -c "$generated"/*.java
printf '%s\n' 'Record the tool/SDK versions and measured output in scripts/generation.json after regeneration.'