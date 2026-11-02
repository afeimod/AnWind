revision="0.1"
urlType="local"
pkgSrcDir="${wsDir}/projects/libandroid-sysv-semaphore"
arch="aarch64 x86_64"
buildSys="others"
license="BSD-2-Clause"
doNotApplyPatch="1"
extra_fuction() {
  rm -f *.o *.so *.a

  $CC $CFLAGS $CPPFLAGS -D__USE_GNU -fPIC -I${pkgSrcDir} -c ${pkgSrcDir}/sys_sem.c
  $CC $CFLAGS sys_sem.o -shared $LDFLAGS -o libandroid-sysv-semaphore.so
  $AR cru libandroid-sysv-semaphore.a sys_sem.o
  mkdir -p "${destDir}${prefix}/include/sys" "${destDir}${prefix}/lib"
  install -Dm600 sys_sem.h "${destDir}${prefix}/include/sys/sem.h"
  install -Dm600 libandroid-sysv-semaphore.a "${destDir}${prefix}/lib/libandroid-sysv-semaphore.a"
  install -Dm600 libandroid-sysv-semaphore.so "${destDir}${prefix}/lib/libandroid-sysv-semaphore.so"
  rm -f *.o *.so *.a
}
