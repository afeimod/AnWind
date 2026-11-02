revision="1.10.8"
url="https://github.com/ebassi/graphene.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="MIT"
agreementTargetFile="${srcDir}/graphene/LICENSES/MIT.txt"
args="
  -Dgtk_doc=false
  -Dtests=false
  -Dinstalled_tests=false
  -Dintrospection=disabled
"
deps="glib"