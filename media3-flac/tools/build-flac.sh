#!/usr/bin/env bash
#
# Builds libflacJNI.so for every ABI this module ships, with ELF PT_LOAD
# segments aligned to 16 KB for Android 15+ devices that use 16 KB memory
# pages. A 4 KB-aligned binary fails to dlopen() there, and
# LibflacAudioRenderer then silently degrades to the platform FLAC decoder.
#
# Provenance (pinned for reproducibility):
#
#   JNI wrapper  androidx.media3 1.10.1
#                libraries/decoder_flac/src/main/jni/{CMakeLists.txt,
#                flac_jni.cc, flac_parser.cc, include/data_source.h,
#                include/flac_parser.h}, vendored verbatim in src/main/jni/
#                (Apache-2.0, see THIRD_PARTY_NOTICES.md).
#
#   libFLAC      xiph/flac @ e94ff9f68b8e7dbd3e9f8b1ac18a8eca1914f181
#                (2026-07-19). Built with FLAC__VENDOR_STRING
#                "reference libFLAC git-e94ff9f 20260719", which is derived
#                from git metadata -- so the checkout must keep its .git dir.
#                Fetched into src/main/jni/libflac/ (BSD-3-Clause).
#
#   toolchain    Android NDK r26b (clang 17.0.2) + CMake 3.31.6 with Ninja,
#                ANDROID_PLATFORM=android-27 to match minSdk 27.
#
# NDK r26 links with a 4 KB maximum page size by default, so the 16 KB
# alignment is passed explicitly through CMAKE_SHARED_LINKER_FLAGS rather than
# patched into the vendored CMakeLists.txt. This keeps the vendored media3
# sources byte-identical to upstream and makes the Android-specific flag
# visible in one place.
#
# Usage:
#   tools/build-flac.sh                     # build all ABIs, validate, install
#   tools/build-flac.sh --abis "arm64-v8a"  # build a subset
#   tools/build-flac.sh --no-install        # build + validate, keep jniLibs
#   tools/build-flac.sh --clean             # discard previous build output
#   tools/build-flac.sh --refetch           # re-download the libFLAC checkout
#
# Validation runs automatically (tools/check-flac-jni.sh): every PT_LOAD
# segment must be 16 KB aligned and all 14 JNI entry points declared in
# FlacDecoderJni.java must be exported. Nothing is installed unless it passes.
#
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MODULE_DIR=$(cd "${SCRIPT_DIR}/.." && pwd)
JNI_DIR="${MODULE_DIR}/src/main/jni"
JNILIBS_DIR="${MODULE_DIR}/src/main/jniLibs"
BUILD_DIR="${MODULE_DIR}/build/flac-native"
LIBFLAC_DIR="${JNI_DIR}/libflac"
CHECK_SCRIPT="${SCRIPT_DIR}/check-flac-jni.sh"

MEDIA3_VERSION=1.10.1
LIBFLAC_COMMIT=e94ff9f68b8e7dbd3e9f8b1ac18a8eca1914f181
LIBFLAC_REPO=https://github.com/xiph/flac.git
ANDROID_PLATFORM=android-27
ALL_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
# Required so the shipped binaries load on 16 KB-page devices.
MAX_PAGE_SIZE_FLAG='-Wl,-z,max-page-size=16384'

# sha256 of the vendored media3 1.10.1 sources; used to flag local divergence.
VENDORED_SOURCES=(
  "CMakeLists.txt:62e7170dcf0f71d4dabe6cf397e9e199d51d43e7f885f6b730b89935568f3ae9"
  "flac_jni.cc:ee4892c707fde0152ac36160580364014227aeff1f17e33d8db3c7ca850aea86"
  "flac_parser.cc:0914a0b08866ddc95e4cf114a47a46f3924e2897533cc85f6048748e4e282c0b"
  "include/data_source.h:ec1561e097797a13db9d448f09175b364f71af7462600ee54dd09b854786d085"
  "include/flac_parser.h:efbcf80e09b94ef7bd927f9842c9548dedb482e9f78b8dfee6d749a76068ac7f"
)

ABIS=("${ALL_ABIS[@]}")
INSTALL=1
CLEAN=0
REFETCH=0

die() {
  echo "error: $*" >&2
  exit 1
}

info() { echo "==> $*"; }

usage() {
  sed -n '3,45p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --abis)
      [ -n "${2:-}" ] || die "--abis requires a value"
      read -r -a ABIS <<<"$2"
      shift 2
      ;;
    --no-install) INSTALL=0; shift ;;
    --clean) CLEAN=1; shift ;;
    --refetch) REFETCH=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument '$1' (try --help)" ;;
  esac
done

for abi in "${ABIS[@]}"; do
  case " ${ALL_ABIS[*]} " in
    *" ${abi} "*) ;;
    *) die "unsupported ABI '${abi}' (expected one of: ${ALL_ABIS[*]})" ;;
  esac
done

# --- toolchain discovery -----------------------------------------------------

newest_path() {
  local candidate best=''
  for candidate in "$@"; do
    [ -e "${candidate}" ] || continue
    if [ -z "${best}" ] || [ "${candidate}" \> "${best}" ]; then
      best="${candidate}"
    fi
  done
  printf '%s' "${best}"
}

sdk_root() {
  local sdk_dirs=()
  [ -n "${ANDROID_HOME:-}" ] && sdk_dirs+=("${ANDROID_HOME}")
  [ -n "${ANDROID_SDK_ROOT:-}" ] && sdk_dirs+=("${ANDROID_SDK_ROOT}")
  sdk_dirs+=("${HOME}/Library/Android/sdk" "${HOME}/Android/Sdk")
  local project_sdk
  project_sdk=$(sed -n 's/^sdk\.dir=//p' "${MODULE_DIR}/../local.properties" 2>/dev/null || true)
  [ -n "${project_sdk}" ] && sdk_dirs+=("${project_sdk}")
  local sdk
  for sdk in "${sdk_dirs[@]}"; do
    [ -d "${sdk}" ] && { printf '%s' "${sdk}"; return 0; }
  done
  return 1
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
  local sdk ndk
  sdk=$(sdk_root) || return 1
  ndk=$(newest_path "${sdk}"/ndk/* "${sdk}"/android-ndk-*)
  [ -n "${ndk}" ] || return 1
  printf '%s' "${ndk}"
}

NDK=$(ndk_root) || die "no Android NDK found (set ANDROID_NDK_HOME, or install one under \$ANDROID_HOME/ndk)"
[ -f "${NDK}/build/cmake/android.toolchain.cmake" ] ||
  die "NDK at ${NDK} has no build/cmake/android.toolchain.cmake"

# CMake and Ninja ship with the Android SDK; fall back to whatever is on PATH.
SDK=$(sdk_root || true)
CMAKE=''
NINJA=''
if [ -n "${SDK}" ]; then
  CMAKE=$(newest_path "${SDK}"/cmake/*/bin/cmake)
  NINJA=$(newest_path "${SDK}"/cmake/*/bin/ninja)
fi
[ -n "${CMAKE}" ] || CMAKE=$(command -v cmake || true)
[ -n "${NINJA}" ] || NINJA=$(command -v ninja || true)
[ -n "${CMAKE}" ] || die "cmake not found (install it via the SDK manager, or put it on PATH)"
[ -n "${NINJA}" ] || die "ninja not found (install it via the SDK manager, or put it on PATH)"

# llvm-strip from the NDK keeps the build self-contained on macOS.
STRIP=''
for candidate in "${NDK}"/toolchains/llvm/prebuilt/*/bin/llvm-strip; do
  [ -x "${candidate}" ] && STRIP="${candidate}"
done
[ -n "${STRIP}" ] || die "llvm-strip not found under ${NDK}/toolchains/llvm/prebuilt/*/bin"
command -v git >/dev/null 2>&1 || die "git not found (required to fetch the libFLAC checkout)"

info "NDK      ${NDK} ($(sed -n 's/^Pkg\.Revision *= *//p' "${NDK}/source.properties" 2>/dev/null || echo 'unknown'))"
info "CMake    ${CMAKE}"
info "Ninja    ${NINJA}"
info "ABIs     ${ABIS[*]}"
echo

# --- sources -----------------------------------------------------------------

check_vendored_sources() {
  local entry path expected actual rc=0
  for entry in "${VENDORED_SOURCES[@]}"; do
    path="${JNI_DIR}/${entry%%:*}"
    expected="${entry##*:}"
    if [ ! -f "${path}" ]; then
      die "missing vendored media3 source ${path#"${MODULE_DIR}/"}"
    fi
    actual=$(shasum -a 256 "${path}" | awk '{print $1}')
    if [ "${actual}" != "${expected}" ]; then
      echo "warning: ${path#"${MODULE_DIR}/"} differs from media3 ${MEDIA3_VERSION} (sha256 ${actual})" >&2
      rc=1
    fi
  done
  if [ "${rc}" -eq 1 ]; then
    echo "warning: vendored sources were modified locally; the build will use the local copies." >&2
    echo
  fi
}

fetch_libflac() {
  local head
  if [ -d "${LIBFLAC_DIR}/.git" ]; then
    head=$(git -C "${LIBFLAC_DIR}" rev-parse HEAD 2>/dev/null || true)
    if [ "${head}" = "${LIBFLAC_COMMIT}" ] && [ "${REFETCH}" -eq 0 ]; then
      info "libFLAC checkout already at ${LIBFLAC_COMMIT:0:7}"
      return 0
    fi
    info "replacing libFLAC checkout (have ${head:0:7}, want ${LIBFLAC_COMMIT:0:7})"
    rm -rf "${LIBFLAC_DIR}"
  fi

  info "fetching libFLAC @ ${LIBFLAC_COMMIT:0:7}"
  mkdir -p "${LIBFLAC_DIR}"
  git -C "${LIBFLAC_DIR}" init -q
  git -C "${LIBFLAC_DIR}" remote add origin "${LIBFLAC_REPO}" 2>/dev/null || true
  # The full 40-char SHA is required: a short hash is not a fetchable ref.
  # A shallow checkout still carries the commit hash/date that libFLAC embeds
  # in FLAC__VENDOR_STRING, and no tag, matching the shipped binaries.
  git -C "${LIBFLAC_DIR}" fetch -q --depth=1 origin "${LIBFLAC_COMMIT}"
  git -C "${LIBFLAC_DIR}" checkout -q FETCH_HEAD
  head=$(git -C "${LIBFLAC_DIR}" rev-parse HEAD)
  [ "${head}" = "${LIBFLAC_COMMIT}" ] || die "libFLAC checkout landed on ${head}, expected ${LIBFLAC_COMMIT}"
}

# --- build -------------------------------------------------------------------

build_abi() {
  local abi="$1"
  local build_path="${BUILD_DIR}/${abi}"
  local raw_so="${build_path}/libflacJNI.so"

  info "configuring ${abi}"
  "${CMAKE}" -G Ninja \
    -S "${JNI_DIR}" \
    -B "${build_path}" \
    -DCMAKE_MAKE_PROGRAM="${NINJA}" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
    -DANDROID_NDK="${NDK}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DWITH_OGG=OFF \
    -DINSTALL_MANPAGES=OFF \
    -DCMAKE_SHARED_LINKER_FLAGS="${MAX_PAGE_SIZE_FLAG}" \
    >"${build_path}.configure.log" 2>&1 ||
    die "cmake configure failed for ${abi} (see ${build_path}.configure.log)"

  info "building ${abi}"
  "${CMAKE}" --build "${build_path}" --target flacJNI -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)" \
    >"${build_path}.build.log" 2>&1 ||
    die "build failed for ${abi} (see ${build_path}.build.log)"

  [ -f "${raw_so}" ] || die "expected artifact ${raw_so} was not produced"

  # Strip debug info and the symbol table, matching the shipped binaries.
  "${STRIP}" --strip-unneeded "${raw_so}"
}

# --- main --------------------------------------------------------------------

check_vendored_sources
fetch_libflac

if [ "${CLEAN}" -eq 1 ]; then
  info "removing ${BUILD_DIR}"
  rm -rf "${BUILD_DIR}"
fi
mkdir -p "${BUILD_DIR}"

built=()
for abi in "${ABIS[@]}"; do
  build_abi "${abi}"
  built+=("${BUILD_DIR}/${abi}/libflacJNI.so")
done
echo

info "validating build output"
bash "${CHECK_SCRIPT}" "${built[@]}"
echo

if [ "${INSTALL}" -eq 0 ]; then
  info "--no-install: leaving ${JNILIBS_DIR#"${MODULE_DIR}/"} untouched"
  echo
  info "built artifacts:"
  for so in "${built[@]}"; do printf '     %s\n' "${so}"; done
  exit 0
fi

info "installing into ${JNILIBS_DIR#"${MODULE_DIR}/"}"
for abi in "${ABIS[@]}"; do
  install -m 755 "${BUILD_DIR}/${abi}/libflacJNI.so" "${JNILIBS_DIR}/${abi}/libflacJNI.so"
  printf '     %-14s %s bytes\n' "${abi}" "$(wc -c <"${JNILIBS_DIR}/${abi}/libflacJNI.so" | tr -d ' ')"
done
echo

info "re-checking installed binaries"
bash "${CHECK_SCRIPT}"
