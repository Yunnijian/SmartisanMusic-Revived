#!/usr/bin/env bash
#
# Verifies the prebuilt libflacJNI.so binaries shipped in src/main/jniLibs.
#
# Per ABI the script asserts:
#   1. the file is an ELF shared object with the expected machine type;
#   2. every PT_LOAD segment has p_align >= 0x4000 (16 KB). Android 15+
#      devices may use 16 KB memory pages; a 4 KB-aligned .so fails to dlopen
#      there and LibflacAudioRenderer silently degrades to the platform FLAC
#      decoder. See tools/build-flac.sh for how the binaries are produced;
#   3. all 14 Java_androidx_media3_decoder_flac_FlacDecoderJni_* JNI entry
#      points declared in FlacDecoderJni.java are exported.
#
# Usage:
#   tools/check-flac-jni.sh                  # check src/main/jniLibs/*/libflacJNI.so
#   tools/check-flac-jni.sh FILE.so...       # check specific shared objects
#   tools/check-flac-jni.sh --apk FILE.apk   # check lib/*/libflacJNI.so inside an APK
#
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MODULE_DIR=$(cd "${SCRIPT_DIR}/.." && pwd)
JNILIBS_DIR="${MODULE_DIR}/src/main/jniLibs"

ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
# p_align of every PT_LOAD segment must be at least this value (0x4000 = 16 KB).
MIN_ALIGN=0x4000
JNI_PREFIX='Java_androidx_media3_decoder_flac_FlacDecoderJni_'
# Must stay in sync with the native declarations in FlacDecoderJni.java.
JNI_METHODS=(
  flacInit
  flacDecodeMetadata
  flacDecodeToBuffer
  flacDecodeToArray
  flacGetDecodePosition
  flacGetLastFrameTimestamp
  flacGetLastFrameFirstSampleIndex
  flacGetNextFrameFirstSampleIndex
  flacGetSeekPoints
  flacGetStateString
  flacIsDecoderAtEndOfStream
  flacFlush
  flacReset
  flacRelease
)

die() {
  echo "error: $*" >&2
  exit 1
}

# --- toolchain discovery -----------------------------------------------------

# Prints the newest existing directory among the arguments, or nothing.
newest_dir() {
  local candidate best=''
  for candidate in "$@"; do
    [ -d "${candidate}" ] || continue
    if [ -z "${best}" ] || [ "${candidate}" \> "${best}" ]; then
      best="${candidate}"
    fi
  done
  printf '%s' "${best}"
}

ndk_root() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_NDK_HOME}" ]; then
    printf '%s' "${ANDROID_NDK_HOME}"
    return 0
  fi
  if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "${ANDROID_NDK_ROOT}" ]; then
    printf '%s' "${ANDROID_NDK_ROOT}"
    return 0
  fi
  local sdk_dirs=()
  [ -n "${ANDROID_HOME:-}" ] && sdk_dirs+=("${ANDROID_HOME}")
  [ -n "${ANDROID_SDK_ROOT:-}" ] && sdk_dirs+=("${ANDROID_SDK_ROOT}")
  sdk_dirs+=("${HOME}/Library/Android/sdk" "${HOME}/Android/Sdk")
  local project_sdk
  project_sdk=$(sed -n 's/^sdk\.dir=//p' "${MODULE_DIR}/../local.properties" 2>/dev/null || true)
  [ -n "${project_sdk}" ] && sdk_dirs+=("${project_sdk}")

  local sdk candidates=()
  for sdk in "${sdk_dirs[@]}"; do
    candidates+=("${sdk}"/ndk/*)
    candidates+=("${sdk}"/android-ndk-*)
  done
  newest_dir "${candidates[@]}"
}

# Locates an LLVM binaryutil: $1 = tool name (e.g. llvm-readelf).
llvm_tool() {
  local name="$1" candidate
  if command -v "${name}" >/dev/null 2>&1; then
    command -v "${name}"
    return 0
  fi
  local ndk
  ndk=$(ndk_root)
  if [ -n "${ndk}" ]; then
    for candidate in "${ndk}"/toolchains/llvm/prebuilt/*/bin/"${name}"; do
      [ -x "${candidate}" ] && { printf '%s' "${candidate}"; return 0; }
    done
  fi
  return 1
}

READELF=$(llvm_tool llvm-readelf) || die "llvm-readelf not found (set ANDROID_NDK_HOME or install an NDK)"
NM=$(llvm_tool llvm-nm) || die "llvm-nm not found (set ANDROID_NDK_HOME or install an NDK)"

# --- checks ------------------------------------------------------------------

# Maps an ABI to the "Machine:" value llvm-readelf reports for it.
expected_machine() {
  case "$1" in
    arm64-v8a) echo 'AArch64' ;;
    armeabi-v7a) echo 'ARM' ;;
    x86) echo 'Intel 80386' ;;
    x86_64) echo 'Advanced Micro Devices X86-64' ;;
    *) die "unknown ABI '$1'" ;;
  esac
}

# Basename of the parent directory, used to infer the ABI of a path.
abi_of_path() {
  local path="$1" abi
  for abi in "${ABIS[@]}"; do
    case "${path}" in
      */"${abi}"/libflacJNI.so|*/"${abi}"/*.so) printf '%s' "${abi}"; return 0 ;;
    esac
  done
  return 1
}

# Checks one .so. $1 = path, $2 = expected ABI (may be empty).
check_so() {
  local so="$1" abi="$2" failed=0
  local label="${so}"
  [ -n "${abi}" ] && label="${abi} (${so})"

  if [ ! -f "${so}" ]; then
    echo "FAIL ${label}: file not found"
    return 1
  fi

  # 1. ELF shared object + machine type.
  local machine
  machine=$("${READELF}" -hW "${so}" 2>/dev/null | sed -n 's/.*Machine: *//p' | head -1) || true
  if [ -z "${machine}" ]; then
    echo "FAIL ${label}: not a readable ELF file"
    return 1
  fi
  if [ -n "${abi}" ]; then
    local want
    want=$(expected_machine "${abi}")
    if [ "${machine}" != "${want}" ]; then
      echo "FAIL ${label}: machine '${machine}', expected '${want}'"
      failed=1
    fi
  fi

  # 2. PT_LOAD p_align >= 16 KB.
  local aligns align
  aligns=$("${READELF}" -lW "${so}" | awk '/^ *LOAD/{print $NF}' | sort -u)
  if [ -z "${aligns}" ]; then
    echo "FAIL ${label}: no PT_LOAD segments found"
    failed=1
  else
    while read -r align; do
      [ -n "${align}" ] || continue
      if [ "$((align))" -lt "$((MIN_ALIGN))" ]; then
        echo "FAIL ${label}: PT_LOAD p_align ${align} < ${MIN_ALIGN} (16 KB pages unsupported)"
        failed=1
      fi
    done <<<"${aligns}"
  fi

  # 3. All expected JNI entry points are exported.
  local symbols method missing=0 found=0
  symbols=$("${NM}" -D --defined-only "${so}" | awk '{print $NF}')
  for method in "${JNI_METHODS[@]}"; do
    if grep -qx "${JNI_PREFIX}${method}" <<<"${symbols}"; then
      found=$((found + 1))
    else
      echo "FAIL ${label}: missing JNI symbol ${JNI_PREFIX}${method}"
      missing=1
    fi
  done
  [ "${missing}" -eq 1 ] && failed=1

  if [ "${failed}" -eq 0 ]; then
    printf 'OK   %s: %s, p_align %s, %d/%d JNI symbols\n' \
      "${label}" "${machine}" "$(tr '\n' ',' <<<"${aligns}" | sed 's/,$//')" \
      "${found}" "${#JNI_METHODS[@]}"
  fi
  return "${failed}"
}

main() {
  local apk='' tmpdir='' target='' failures=0 checked=0

  if [ "${1:-}" = '--apk' ]; then
    apk="${2:-}"
    [ -n "${apk}" ] || die "--apk requires a path"
    [ -f "${apk}" ] || die "APK not found: ${apk}"
    command -v unzip >/dev/null 2>&1 || die "unzip not found (required for --apk)"
    tmpdir=$(mktemp -d)
    trap "rm -rf '${tmpdir}'" EXIT
    local abi
    for abi in "${ABIS[@]}"; do
      if unzip -o -q "${apk}" "lib/${abi}/libflacJNI.so" -d "${tmpdir}" 2>/dev/null &&
        [ -f "${tmpdir}/lib/${abi}/libflacJNI.so" ]; then
        checked=$((checked + 1))
        check_so "${tmpdir}/lib/${abi}/libflacJNI.so" "${abi}" || failures=$((failures + 1))
      else
        echo "FAIL APK ${apk}: lib/${abi}/libflacJNI.so is missing"
        failures=$((failures + 1))
      fi
    done
  elif [ "$#" -gt 0 ]; then
    local so abi
    for so in "$@"; do
      abi=$(abi_of_path "${so}") || abi=''
      checked=$((checked + 1))
      check_so "${so}" "${abi}" || failures=$((failures + 1))
    done
  else
    local so abi
    for abi in "${ABIS[@]}"; do
      so="${JNILIBS_DIR}/${abi}/libflacJNI.so"
      checked=$((checked + 1))
      check_so "${so}" "${abi}" || failures=$((failures + 1))
    done
  fi

  echo
  if [ "${failures}" -eq 0 ]; then
    echo "PASS: ${checked}/${checked} checked binaries are 16 KB page aligned with the full JNI surface."
  else
    echo "FAIL: ${failures} of ${checked} checked binaries failed validation."
    exit 1
  fi
}

main "$@"
