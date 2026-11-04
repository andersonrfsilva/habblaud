#!/usr/bin/env bash
set -euo pipefail
fail() { printf 'iOS build: %s\n' "$*" >&2; exit 1; }
[[ $(uname -s) == Darwin ]] || fail 'macOS with Xcode is required.'
command -v xcodebuild >/dev/null || fail 'Install Xcode and select it with xcode-select.'
xcrun --find swift >/dev/null || fail 'Selected developer directory has no Swift compiler.'
root=$(cd "$(dirname "$0")/.." && pwd)
mode=${1:-device}
case "$mode" in
  simulator) sdk=iphonesimulator; destination='generic/platform=iOS Simulator'; configuration=Debug; platform=IOSSIMULATOR ;;
  device) sdk=iphoneos; destination='generic/platform=iOS'; configuration=Release; platform=IOS ;;
  *) fail 'Usage: bash scripts/ios-build.sh [simulator|device]' ;;
esac
out="$root/ios/build"
mkdir -p "$out"
{
  xcodebuild -version
  xcrun --sdk "$sdk" --show-sdk-version
  xcrun swift --version
  java -version 2>&1
  "$root/android/gradlew" -p "$root/android" --version
} | tee "$out/toolchain-$mode.txt"
xcodebuild -project "$root/ios/Zaplaud.xcodeproj" -scheme Zaplaud \
  -configuration "$configuration" -sdk "$sdk" -destination "$destination" \
  -derivedDataPath "$out/DerivedData" ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
app="$out/DerivedData/Build/Products/$configuration-$sdk/Zaplaud.app"
[[ -d "$app" ]] || fail "Missing app: $app"
plutil -lint "$app/Info.plist"
[[ $(/usr/libexec/PlistBuddy -c 'Print :UIDeviceFamily:0' "$app/Info.plist") == 2 ]] || fail 'App is not iPad-only.'
if /usr/libexec/PlistBuddy -c 'Print :UIBackgroundModes' "$app/Info.plist" >/dev/null 2>&1; then fail 'Background modes are forbidden.'; fi
[[ $(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist") == br.com.xlinx.zaplaud ]] || fail 'Unexpected bundle identity.'
xcrun lipo "$app/Zaplaud" -verify_arch arm64
xcrun vtool -show-build "$app/Zaplaud" | tee "$out/platform-$mode.txt"
grep -Eq "platform[[:space:]]+$platform$" "$out/platform-$mode.txt" || fail "Binary is not $platform."
# Compose's Xcode integration copies common resources into this directory.
[[ -d "$app/compose-resources" ]] || fail 'Compose resources were not packaged.'
[[ -n $(find "$app/compose-resources" -type f -print -quit) ]] || fail 'Compose resources are empty.'
# A static Kotlin framework must not leak into the embedded Frameworks directory.
[[ ! -e "$app/Frameworks/ZaplaudShared.framework" ]] || fail 'Expected a static Kotlin framework.'
xcrun otool -L "$app/Zaplaud" | tee "$out/linkage-$mode.txt"
if [[ -d "$app/Frameworks" ]]; then
  while IFS= read -r -d '' framework; do
    binary="$framework/$(basename "$framework" .framework)"
    [[ -f "$binary" ]] || fail "Embedded framework executable missing: $framework"
    xcrun vtool -show-build "$binary" | tee -a "$out/linkage-$mode.txt" |
      grep -Eq "platform[[:space:]]+$platform$" || fail "Wrong embedded framework platform: $framework"
  done < <(find "$app/Frameworks" -type d -name '*.framework' -print0)
fi
if [[ "$mode" == device ]]; then
  payload=$(mktemp -d "$out/package.XXXXXX")
  trap 'rm -rf "$payload"' EXIT
  mkdir "$payload/Payload"
  ditto "$app" "$payload/Payload/Zaplaud.app"
  rm -f "$out/Zaplaud.ipa"
  (cd "$payload" && /usr/bin/zip -qry "$out/Zaplaud.ipa" Payload)
  unzip -t "$out/Zaplaud.ipa"
  unzip -Z1 "$out/Zaplaud.ipa" | grep -qx 'Payload/Zaplaud.app/Zaplaud' || fail 'IPA executable missing.'
  shasum -a 256 "$out/Zaplaud.ipa" | tee "$out/Zaplaud.ipa.sha256"
  printf 'Unsigned device IPA for SideStore re-signing: %s\n' "$out/Zaplaud.ipa"
else
  shasum -a 256 "$app/Zaplaud" | tee "$out/simulator.sha256"
  printf 'Simulator app: %s\n' "$app"
fi
