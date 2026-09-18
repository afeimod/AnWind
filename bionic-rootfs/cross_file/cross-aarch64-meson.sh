#!/bin/bash
# Strip ccache prefix from compiler paths for Meson cross file
# Meson handles ccache via USE_CCACHE=1 environment variable
_cOnly="${CC#ccache }"
_cppOnly="${CXX#ccache }"

# Use PKG_CONFIG_SYSROOT_DIR or default prefix
_prefix="${PKG_CONFIG_SYSROOT_DIR:-/tmp/bionic-prefix}"

# [built-in options] are derived from the current environment,
# so flags appended to CFLAGS/CXXFLAGS/LDFLAGS (e.g. in pre_setup) land in the cross file
_cArgs="-I${_prefix}/usr/include -I${_prefix}/include ${CFLAGS:-}"
_cppArgs="-I${_prefix}/usr/include -I${_prefix}/include ${CXXFLAGS:-}"
_linkArgs="${LDFLAGS:--L${_prefix}/lib}"

# join words into a quoted meson array string, e.g. 'a', 'b'
meson_args() {
  local _s="" _w
  for _w in "$@"; do
    [[ -n "$_s" ]] && _s+=", "
    _s+="'${_w}'"
  done
  printf '%s' "$_s"
}

cat > /tmp/cross-aarch64-meson.txt << EOF
[binaries]
c = '${_cOnly}'
cpp = '${_cppOnly}'
ar = '${AR}'
strip = '${STRIP}'
ranlib = '${RANLIB}'
ld = '${LD}'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
c_args = [$(meson_args ${_cArgs})]
cpp_args = [$(meson_args ${_cppArgs})]
c_link_args = [$(meson_args ${_linkArgs})]
cpp_link_args = [$(meson_args ${_linkArgs})]

[properties]
needs_exe_wrapper = true
${extraMesonCrossProps:-}
EOF