# 1.3.0：下架 winfusion_game_controller_ipc（手柄改走 controller.shm 文件映射 + PE 侧直读，socket 通道没有消费方）。
# 1.2.0：下架 winfusion_task_manager_ipc（任务管理器改为文件映射 + 容器侧 winfusionhelper.exe 直通）。
# 1.1.0：加入 winfusion_open_ipc（归档 + open_driver.h + .pc）。
# ⚠ 本工程内容一变就要动 revision —— 框架只按 pkgs/<名>-<revision>-<arch>.tar 是否存在判断
# "要不要重编"，包名不变就会直接把旧包解到 prefix，新头/新归档一个都不会出现。
revision="1.3.0"

# 源码就在本目录（include/ + src/，与 winfusion 仓的 app/src/main/cpp/winfusion 下同名文件一致）
urlType="local"
pkgSrcDir="${wsDir}/projects/winfusion-shm"

arch="aarch64 x86_64"
buildSys="others"
license="LGPL-2.1-or-later"

# WinFusion 共享内存 IPC 客户端（纯 POSIX，只依赖 libc），供容器内程序静态链接：
#   winfusion_open_ipc -> 本仓的 winfusion-open 工程（usr/bin/open）
# winfusion_shm_ring / winfusion_shm_client 是它的依赖闭包。
# 手柄已不在这里：通道换成「controller.shm 文件映射 + 容器内 PE 侧直读」，容器侧只用 kernel32 的
# 映射 API，没有可链的 C 客户端，所以手柄的归档/头文件/.pc 一并下架（socket + 传 fd 那条路没有消费方）。
# 任务管理器同理，走「helper.shm + winfusionhelper.exe」。
# 服务端（shm-server.c 与各 *_driver.c，ashmem + JNI）留在 winfusion 仓的 app 侧，不属本工程。

extra_fuction() {
  local _lib="${destDir}${prefix}/lib"
  local _inc="${pkgSrcDir}/include"
  # -fPIC 与 app 侧 CMake 保持一致（同一份源在那里也编成 PIC）；-std=gnu99 对齐 c_std_99。
  # 去掉 -flto=thin：消费者是独立构建，留成普通 ELF 归档，免掉 LTO 的跨构建依赖。
  local _cFlags="${CFLAGS//-flto=thin/} -std=gnu99 -fPIC -I${_inc}"
  local _h

  mkdir -p "${_lib}/pkgconfig"

  # 中间 .o 就放在本工程目录里（buildSys=others 时 cwd 就是这里），装完删掉，
  # 不往 /tmp 丢东西，也不让它混进包里。开头这一句是清上一轮中途失败留下的。
  rm -f ./*.o

  # 归档名与 winfusion 的 CMake 目标同名，且一归档只含一个 .o，符号布局与 app 侧一致
  ${CC} ${_cFlags} -c "${pkgSrcDir}/src/shm/shm-client.c" -o winfusion_shm_client.o || { err "编译失败 => shm-client.c"; exit 1; }
  ${AR} rcu "${_lib}/libwinfusion_shm_client.a" winfusion_shm_client.o || { err "归档失败 => winfusion_shm_client"; exit 1; }

  ${CC} ${_cFlags} -c "${pkgSrcDir}/src/shm/shm-ring.c" -o winfusion_shm_ring.o || { err "编译失败 => shm-ring.c"; exit 1; }
  ${AR} rcu "${_lib}/libwinfusion_shm_ring.a" winfusion_shm_ring.o || { err "归档失败 => winfusion_shm_ring"; exit 1; }

  ${CC} ${_cFlags} -c "${pkgSrcDir}/src/shm/drivers/open-ipc.c" -o winfusion_open_ipc.o || { err "编译失败 => open-ipc.c"; exit 1; }
  ${AR} rcu "${_lib}/libwinfusion_open_ipc.a" winfusion_open_ipc.o || { err "归档失败 => winfusion_open_ipc"; exit 1; }

  rm -f ./*.o

  # 头文件保持 <shm/...> 路径；服务端头 shm-server.h 不装
  for _h in shm-client.h shm-ring.h drivers/open_driver.h; do
    install -Dm644 "${_inc}/shm/${_h}" "${destDir}${prefix}/include/shm/${_h}"
  done

  # .pc 三份逐份写。pkg-config --libs（不带 --static）取的是 Libs:，
  # 静态依赖闭包必须写进 Libs:，否则调用方的 AC_CHECK_LIB 会判定"库不存在"；
  # 依赖者在前，无依赖的那份不留尾空格。
  cat > "${_lib}/pkgconfig/winfusion_shm_client.pc" << EOF
prefix=${prefix}
exec_prefix=\${prefix}
includedir=\${prefix}/include
libdir=\${prefix}/lib

Name: winfusion_shm_client
Description: WinFusion SHM client
Version: ${revision}
Cflags: -I\${includedir}
Libs: -L\${libdir} -lwinfusion_shm_client
Libs.private: -lpthread
EOF

  cat > "${_lib}/pkgconfig/winfusion_shm_ring.pc" << EOF
prefix=${prefix}
exec_prefix=\${prefix}
includedir=\${prefix}/include
libdir=\${prefix}/lib

Name: winfusion_shm_ring
Description: WinFusion SHM ring buffer
Version: ${revision}
Cflags: -I\${includedir}
Libs: -L\${libdir} -lwinfusion_shm_ring -lwinfusion_shm_client
Libs.private: -lpthread
EOF

  cat > "${_lib}/pkgconfig/winfusion_open_ipc.pc" << EOF
prefix=${prefix}
exec_prefix=\${prefix}
includedir=\${prefix}/include
libdir=\${prefix}/lib

Name: winfusion_open_ipc
Description: WinFusion SHM open IPC client
Version: ${revision}
Cflags: -I\${includedir}
Libs: -L\${libdir} -lwinfusion_open_ipc -lwinfusion_shm_ring -lwinfusion_shm_client
Libs.private: -lpthread
EOF
}
