# 源码版本/URL 可用环境变量覆盖（CI 工作流用下拉选择传入）：
#   ANWIND_HANGOVER_TAG / ANWIND_WINE_URL / ANWIND_WINE_LABEL
# 源码支持三种来源（本配方即 arm64ec 形态配方，任意 wine 树皆可构建）：
#   hangover  AndreRH/wine        tag    hangover-<ver>   （默认）
#   official  wine-mirror/wine    tag    wine-<ver>       （WineHQ 官方源码 GitHub 镜像）
#   proton    ValveSoftware/wine  branch proton_<ver>     （Valve Proton 的 wine 源码树）
# 本目录的 bionic 补丁针对 hangover 树生成；official/proton 树结构相同
# （均为上游 wine 树），个别 hunk 可能因版本漂移失败——pre_setup 对此
# 容错（打印告警继续构建），失败 hunk 落 *.rej 供人工核查。
revision="${ANWIND_HANGOVER_TAG:-11.16}"
url="${ANWIND_WINE_URL:-https://github.com/AndreRH/wine/archive/refs/tags/hangover-${revision}.tar.gz}"
urlType="tar"
arch="aarch64"
buildSys="autotools"
license="LGPL-2.1"
doNotApplyPatch=1

# ============================================================
# AnWind bionic Wine（arm64ec 形态 / X11 / 新 WoW64）
# ------------------------------------------------------------
# 与 wine（x86_64，box64 加载）互补的第二个 Wine 运行时：
#   * 目标架构 aarch64（bionic），PE 侧 arm64ec+aarch64+i386（新 WoW64）
#     —— aarch64 设备原生运行，无需 box64 整机模拟
#   * 显示：仅 X11（--with-x 全套 X 扩展；无 wayland）
#   * 安装路径 $prefix/opt（启动器 WINE_HANGOVER 分支识别）
# llvm-mingw 必须用 20251202+：arm64ec PE 目标需要较新的 llvm/mingw-w64
# （与用户 glibc 流水线 wine-Arm.yml 的验证组合一致）
# ============================================================

args="
  ac_cv_header_linux_userfaultfd_h=no
  ac_cv_path_GRADLE=no
  --prefix=$prefix/opt
  --exec-prefix=$prefix/opt/
  --includedir=$prefix/opt/include
  --libdir=$prefix/opt/lib
  --enable-archs=arm64ec,aarch64,i386
  --enable-nls
  --disable-tests
  --disable-wineandroid-drv
  --with-alsa
  --without-capi
  --without-coreaudio
  --without-cups
  --without-dbus
  --with-ffmpeg
  --with-fontconfig
  --with-freetype
  --without-gettextpo
  --without-gphoto
  --with-gnutls
  --without-gssapi
  --with-gstreamer
  --without-inotify
  --without-krb5
  --without-mingw
  --without-netapi
  --without-opencl
  --with-opengl
  --without-osmesa
  --without-oss
  --without-pcap
  --without-pcsclite
  --with-pthread
  --with-pulse
  --without-sane
  --without-sdl
  --without-udev
  --without-unwind
  --without-usb
  --without-v4l2
  --with-vulkan
  --without-wayland
  --with-x
  --with-xcomposite
  --with-xcursor
  --with-xfixes
  --with-xinerama
  --with-xinput
  --with-xinput2
  --with-xrandr
  --with-xrender
  --with-xshape
  --with-xshm
  --with-xxf86vm
"

deps="pthread-stub alsa-lib fontconfig freetype gnutls gstreamer ffmpeg pulseaudio xkeyboard-config libxkbcommon mesa vulkan-headers vulkan-icd-loader xorgproto libxcb xtrans libX11 libXext libXrender libXfixes libXi libXrandr libXcursor libXinerama libXcomposite libXxf86vm libandroid-shmem"

# 静默编译：wine 的 make 会回显每条完整编译/链接命令（ccache 全路径），
# 单包就曾刷出 11.7 万行日志；-s 只关命令回显，编译器/链接器报错照常输出，
# 失败时仍能从 "make: *** [Makefile:...] Error 1" 定位到目标。
makeSilent=1

llvmMingwVersion="22"
llvmMingwDate="20251202"
llvmMingwUrl="https://github.com/mstorsjo/llvm-mingw/releases/download/${llvmMingwDate}/llvm-mingw-${llvmMingwDate}-ucrt-ubuntu-22.04-x86_64.tar.xz"
llvmMingwSha256=""

hangoverVersion="${revision}"
hangoverDebUrl="https://github.com/AndreRH/hangover/releases/download/hangover-${hangoverVersion}/hangover_${hangoverVersion}_ubuntu2204_jammy_arm64.tar"

pre_setup() {
  local _hostTools="${wsDir}/host_tools/wine-tools-hangover-${revision}"
  local _llvmMingwDir="${wsDir}/tmp/llvm-mingw-${llvmMingwVersion}"

  echo "=== hangover-wine 构建开始 ==="

  # 0. 源码树兼容性探测（official / proton / 自定义树均非 hangover 形态）
  #    a) git 树可能不含预生成的 configure（proton 分支如此）→ autogen.sh 生成
  #    b) arm64ec 能力探测：旧树（≤ wine 9.x 前后）无
  #       'aarch64|arm64ec' 校验分支 → 降级 --enable-archs=aarch64,i386
  #       （仍为新 WoW64：ARM64 PE 原生 + i386 PE；x86_64 PE 不支持，
  #        跑 x86_64 Windows 程序需 hangover 源或 x86_64 形态）
  #       ※ 实证：official-11.0 / proton_10.0 均支持 arm64ec（configure 0 退出），
  #         三大默认源不会触发降级
  if [[ ! -f "${srcDir}/${pjName}/configure" ]]; then
    if [[ -f "${srcDir}/${pjName}/autogen.sh" ]]; then
      echo "源码树无预生成 configure，运行 autogen.sh ..."
      ( cd "${srcDir}/${pjName}" && ./autogen.sh ) || { echo "autogen 失败"; return 1; }
    else
      echo "错误: 源码树既无 configure 也无 autogen.sh"
      return 1
    fi
  fi
  if grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure.ac" 2>/dev/null || \
     grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure" 2>/dev/null; then
    echo "源码树支持 arm64ec（--enable-archs 校验分支存在），保持 --enable-archs=arm64ec,aarch64,i386"
  else
    echo "⚠️ 源码树不支持 arm64ec（过旧/魔改树），降级 --enable-archs=aarch64,i386"
    echo "   （ARM64 PE 原生 + i386 PE 新 WoW64；x86_64 PE 请改用 hangover 源或 x86_64 形态）"
    args="${args/--enable-archs=arm64ec,aarch64,i386/--enable-archs=aarch64,i386}"
  fi

  # 1. 下载并设置 llvm-mingw 工具链
  if [[ ! -d "${_llvmMingwDir}" ]]; then
    echo "下载 llvm-mingw 工具链..."
    mkdir -p "${wsDir}/tmp"
    wget -nv -P "${wsDir}/tmp" "${llvmMingwUrl}" || {
      echo "下载 llvm-mingw 失败"
      return 1
    }
    local _tarFile="${wsDir}/tmp/$(basename "${llvmMingwUrl}")"
    mkdir -p "${_llvmMingwDir}-tmp"
    tar -C "${_llvmMingwDir}-tmp" --strip-component=1 -xf "${_tarFile}"
    mv "${_llvmMingwDir}-tmp" "${_llvmMingwDir}"
    rm -rf "${_llvmMingwDir}-tmp"
  fi
  export PATH="${_llvmMingwDir}/bin:${PATH}"

  load_env_host

  cd "${srcDir}/${pjName}"
  # --forward 跳过已应用的 hunk；失败仅告警不中断（official/proton 树
  # 可能已含等价修正），失败 hunk 落 *.rej。
  for _patch in "${wsDir}/projects/${pjName}"/*.patch.beforehostbuild; do
    if [[ -f "${_patch}" ]]; then
      echo "应用 host build 补丁: $(basename "${_patch}")"
      if ! sed "s%@TERMUX_PREFIX@%${prefix}%g" "${_patch}" | patch --silent --forward -p1; then
        echo "⚠️  host 补丁 $(basename "${_patch}") 有 hunk 未应用（树差异，继续构建）"
      fi
    fi
  done

  mkdir -p "${srcDir}/${pjName}/wine-tools-build"
  cd "${srcDir}/${pjName}/wine-tools-build"
  FREETYPE_CFLAGS=-I/usr/include/freetype2 FREETYPE_LIBS=-lfreetype ../configure \
    --enable-win64 \
    --without-x \
    --with-freetype \
    --without-fontconfig \
    --without-gnutls \
    --without-cups \
    --without-pulse \
    --without-alsa \
    --without-gstreamer \
    --without-opengl \
    --without-vulkan \
    --without-wayland \
    --disable-tests || {
      echo "host configure 失败"
      return 1
    }
  make ${makeSilent:+-s} -j$(nproc) __tooldeps__ || {
    echo "host build 失败"
    return 1
  }
  make ${makeSilent:+-s} -j$(nproc) -C nls || {
    echo "host build 失败"
    return 1
  }

  cd ..

  # 3. 主构建: 配置和编译 wine
  echo "=== 主构建: 配置 wine ==="
  load_env "$targetArch"
  # load_env 把 NDK bin 前插到 PATH（且本流程 load_env 会被调用两次：
  # build-rootfs-arch.sh autotools 分支先调一次，pre_setup 内再调一次），
  # 必须在这里把 llvm-mingw 重新前插回最前 —— 否则 make 阶段 winegcc
  # 按 -b 目标名（arm64ec-windows-gcc 等）找不到专用编译器时回退解析
  # 裸 "clang"，PATH 顺序决定命中的是 NDK clang（clang 19，不认识
  # -marm64x）—— arm64ec/aarch64 双 arch 启用 ARM64X 混合构建时
  # makedep 会向 winegcc 传 -marm64x（tools/makedep.c 4687 行），
  # NDK clang 直接 "unknown argument" 报错（CI 实测 aclui.dll 链接中止）。
  # llvm-mingw 的 clang 21 接受 -marm64x 并由 lld-link 产出 ARM64X
  # 混合镜像，且其 bin 目录只有裸 clang/clang++，无裸 gcc/ld/ar 等可
  # 遮蔽系统工具的名字；unix .so 侧全部走 $CC（绝对路径 ccache NDK
  # clang），不受此 PATH 调整影响。
  export PATH="${_llvmMingwDir}/bin:${PATH}"
  cd "${srcDir}/${pjName}"

  # bionic 兼容补丁（路径/互斥锁属性/Socket IPX，与 wine 配方同源）
  # 补丁针对 hangover 树生成；official/proton 树个别 hunk 可能漂移，
  # 失败仅告警不中断，*.rej 留源码树供核查。
  local _patchFail=""
  for _patch in "${wsDir}/projects/${pjName}"/*.patch; do
    if [[ -f "${_patch}" ]] && [[ ! "${_patch}" == *".beforehostbuild" ]]; then
      echo "应用主补丁: $(basename "${_patch}")"
      if ! sed -e "s%@TERMUX_PREFIX@%${prefix}%g" \
        -e "s%@TERMUX_BASE_DIR@%/data/data/com.anwind/files/rootfs%g" \
        "${_patch}" | patch --silent --forward -p1; then
        echo "⚠️  主补丁 $(basename "${_patch}") 有 hunk 未应用（源码树差异，继续构建）"
        _patchFail+=" $(basename "${_patch}")"
      fi
    fi
  done
  if [[ -n "${_patchFail}" ]]; then
    echo "⚠️⚠️ 以下补丁存在失败 hunk:${_patchFail}"
    echo "    构建继续；若运行期异常请检查源码树内 *.rej 文件"
  fi

  # bionic POSIX shm 兼容注入（幂等）：
  # bionic 任何 API 级别都没有 shm_open/shm_unlink（bionic 官方文档
  # status.md 列为 "Missing functions ... explicitly disallowed by
  # SELinux"），而 proton 树（official <=9.x 旧树同理）的 esync/fsync
  # 无条件调用它们 —— dlls/ntdll/unix/{esync,fsync}.c 与
  # server/{esync,fsync}.c 共 4 个文件：头文件无声明，clang 15+ 的
  # -Wimplicit-function-declaration 直接判错（CI run#14 两个 flavor
  # 均折在此处）；libc 亦无符号，须链 libandroid-shmem（其补丁已实现
  # memfd_create 优先、ashmem 兜底，LDFLAGS 已带 -landroid-shmem）。
  # 这里把原型注入调用文件；wine >=10 官方树/hangover-11 树已删除
  # esync/fsync（合并重构进 sync.c，无 shm 调用），文件缺失自动跳过。
  inject_shm_compat() {
    local _f
    for _f in dlls/ntdll/unix/esync.c dlls/ntdll/unix/fsync.c \
              server/esync.c server/fsync.c; do
      [[ -f "$_f" ]] || continue
      if grep -q "ANWIND_SHM_COMPAT" "$_f"; then
        echo "shm 原型已注入过，跳过 => $_f"
        continue
      fi
      cat > ".anwind-shm-compat.h" <<'SHMDECL'
/* ANWIND_SHM_COMPAT: bionic 无 POSIX shm_open/shm_unlink
   （SELinux 策略禁止，任何 API 级别均无）。实现由 libandroid-shmem
   提供（memfd_create 优先，ashmem 兜底），链接经 -landroid-shmem
   解析；此块仅补原型，避免隐式函数声明被判错。 */
#include <sys/types.h>
extern int shm_open(const char *name, int oflag, mode_t mode);
extern int shm_unlink(const char *name);
SHMDECL
      # 插入位置：紧跟 #include "config.h" 之后 —— wine 的 makedep
      # （tools/makedep.c get_dependencies()）强制校验 config.h 必须是
      # 源文件第一个 include，否则 config.status 生成 Makefile 阶段直接
      # fatal_error "config.h must be included before other headers"
      # （proton_10.0 CI 实测：dlls/ntdll/unix/esync.c:32 中止）。
      # 置顶前置注入会把它顶掉第一位 —— 绝对不可。无 config.h 的文件
      # （老树个别 util）退回置顶注入。
      if grep -q '#include "config.h"' "$_f"; then
        sed -i '/#include "config.h"/r .anwind-shm-compat.h' "$_f" \
          && echo "已注入 shm_open/shm_unlink 原型（config.h 之后）=> $_f"
      else
        cat ".anwind-shm-compat.h" "$_f" > "$_f.anwind-tmp" \
          && mv "$_f.anwind-tmp" "$_f" \
          && echo "已注入 shm_open/shm_unlink 原型（置顶，无 config.h）=> $_f"
      fi
    done
    rm -f ".anwind-shm-compat.h"
  }
  inject_shm_compat

  # winedmo ffmpeg>=7 兼容改造（proton 树专属，官方/hangover 树无此文件）：
  # proton 的 dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c 是从
  # ffmpeg 内部源码拷贝的自定义 BSF，依赖 <7 的内部 ABI
  # （AVBSFContext.internal / ff_bsf_get_packet / AVBitStreamFilter 内部
  # 回调字段）。rootfs 的 ffmpeg 为 n9.0，公共头只剩 name/codec_ids/
  # priv_class 三字段，CI proton_10.0 实测 5 个编译错误直接炸掉
  # winedmo（--with-ffmpeg 的唯一消费者）。
  # 处理：把 BSF 文件整体重写为纯公共 API 的字节序反转 helper，并在
  # unix_demuxer.c 中改在 demuxer_read 收包后做反转（见
  # 0004-fix-winedmo-ffmpeg9.patch.proton；.patch.proton 后缀避开主补丁
  # 循环的 *.patch 通配，由这里条件应用）。功能等价：BE PCM → LE +
  # par_out 报 LE codec id（media type 构建读 filter->par_out）。
  if [[ -f dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c ]] \
     && grep -q "ctx->internal" dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c; then
    if grep -q "pcm_reverse_bytes" dlls/winedmo/unix_demuxer.c 2>/dev/null; then
      echo "winedmo ffmpeg>=7 兼容改造已应用过，跳过"
    else
      echo "应用 winedmo ffmpeg>=7 兼容改造（proton 自定义 BSF 使用 ffmpeg 内部 ABI）"
      cat > dlls/winedmo/libavcodec/pcm_byte_order_reverse_bsf.c <<'PCMBSF'
/*
 * PCM byte order conversion helper (big-endian -> little-endian)
 *
 * 原版为 proton 从 ffmpeg 内部源码拷贝的自定义 BSF，依赖 ffmpeg <7 的
 * 内部 ABI（AVBSFContext.internal / ff_bsf_get_packet / 内部回调字段）。
 * ffmpeg >= 7 将其全部私有化，外部树无法再自定义 BSF —— 改为纯公共
 * API 实现：unix_demuxer.c 在 demuxer_read 收包后调
 * pcm_byte_order_reverse_packet 做同样的逐采样字节序反转，并经
 * filter->par_out 向下游报 LE codec id（media type 构建入口）。
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 */

#if 0
#pragma makedep unix
#endif

#include "config.h"
#include "unix_private.h"

#ifdef HAVE_FFMPEG

/* 需要字节序反转的编解码器的每采样字节数，其余返回 0。
 * 仅处理大端变体：WAVEFORMATEX 无端序信息，容器里的大端 PCM
 * 统一转成小端交给 Windows 侧。 */
unsigned int pcm_byte_order_bytes_per_sample( enum AVCodecID codec_id )
{
    switch (codec_id)
    {
    case AV_CODEC_ID_PCM_S16BE: return 2;
    case AV_CODEC_ID_PCM_S24BE: return 3;
    case AV_CODEC_ID_PCM_S32BE: return 4;
    case AV_CODEC_ID_PCM_S64BE: return 8;
    case AV_CODEC_ID_PCM_F32BE: return 4;
    case AV_CODEC_ID_PCM_F64BE: return 8;
    default: return 0;
    }
}

/* 大端 codec id 的小端等价物（供下游 media type 映射使用）。 */
enum AVCodecID pcm_byte_order_reverse_codec_id( enum AVCodecID codec_id )
{
    switch (codec_id)
    {
    case AV_CODEC_ID_PCM_S16BE: return AV_CODEC_ID_PCM_S16LE;
    case AV_CODEC_ID_PCM_S24BE: return AV_CODEC_ID_PCM_S24LE;
    case AV_CODEC_ID_PCM_S32BE: return AV_CODEC_ID_PCM_S32LE;
    case AV_CODEC_ID_PCM_S64BE: return AV_CODEC_ID_PCM_S64LE;
    case AV_CODEC_ID_PCM_F32BE: return AV_CODEC_ID_PCM_F32LE;
    case AV_CODEC_ID_PCM_F64BE: return AV_CODEC_ID_PCM_F64LE;
    default: return codec_id;
    }
}

/* 原地反转包内每个采样的字节序。 */
void pcm_byte_order_reverse_packet( AVPacket *pkt, unsigned int bytes_per_sample )
{
    unsigned int i, half;
    uint8_t *buf, *end, tmp;

    if (!pkt->data || !bytes_per_sample) return;
    half = bytes_per_sample / 2u;
    buf = pkt->data;
    end = buf + pkt->size - (pkt->size % bytes_per_sample);

    while (buf < end)
    {
        for (i = 0; i < half; ++i)
        {
            tmp = buf[i];
            buf[i] = buf[bytes_per_sample - i - 1];
            buf[bytes_per_sample - i - 1] = tmp;
        }
        buf += bytes_per_sample;
    }
}

#endif /* HAVE_FFMPEG */
PCMBSF
      sed -i 's|^extern const AVBitStreamFilter ff_pcm_byte_order_reverse_bsf;|unsigned int pcm_byte_order_bytes_per_sample( enum AVCodecID codec_id );\nenum AVCodecID pcm_byte_order_reverse_codec_id( enum AVCodecID codec_id );\nvoid pcm_byte_order_reverse_packet( AVPacket *pkt, unsigned int bytes_per_sample );|' \
        dlls/winedmo/unix_private.h \
        && echo "已重写 winedmo 自定义 BSF 为公共 API helper"
      if patch --silent --forward -p1 < "${wsDir}/projects/${pjName}/0004-fix-winedmo-ffmpeg9.patch.proton"; then
        echo "已应用 winedmo demuxer 兼容补丁"
      else
        echo "⚠️ winedmo demuxer 兼容补丁有 hunk 未应用（树差异，继续构建）"
      fi
    fi
  fi

  # wineboot xstate aarch64 空桩补齐（proton 树专属，条件应用）：
  # proton_10.0 的 programs/wineboot/wineboot.c 中 initialize_xstate_features
  # 只在 x86 分支（#if __i386__||__x86_64__）与 #else 分支有定义，
  # #elif defined(__aarch64__) 分支漏掉空桩，而 "initialize_xstate_features( data )"
  # 的调用无条件 —— arm64ec 形态编译 aarch64-windows/arm64ec-windows PE 时
  # wineboot.o 直接报 -Wimplicit-function-declaration（CI proton_10.0 实测，
  # hangover-11.16 树无此代码不受影响）。修法与 #else 分支一致：补空桩。
  # 检测条件：文件存在调用点 且 aarch64 分支内无定义（幂等）。
  # .patch.proton 后缀避开主补丁循环的 *.patch 通配 —— hangover 树
  # wineboot.c 结构相近（同为 #elif __aarch64__ + read_tsc_frequency），
  # 普通应用会 fuzz 误匹配，必须仅在 proton 类树上精确套用。
  if [[ -f programs/wineboot/wineboot.c ]] \
     && grep -q "initialize_xstate_features( data )" programs/wineboot/wineboot.c; then
    if ! sed -n '/#elif defined(__aarch64__)/,/^#else/p' programs/wineboot/wineboot.c \
         | grep -q "initialize_xstate_features"; then
      echo "应用 wineboot aarch64 xstate 空桩补丁（proton 树漏定义）"
      if patch --silent --forward -p1 --fuzz=0 \
           < "${wsDir}/projects/${pjName}/0005-fix-wineboot-aarch64-xstate.patch.proton"; then
        echo "已补齐 wineboot aarch64 分支 initialize_xstate_features 空桩"
      else
        echo "⚠️ wineboot xstate 补丁未应用（树结构差异，继续构建）"
        echo "    若后续 aarch64 PE 编译报 initialize_xstate_features 隐式声明，"
        echo "    请核查 programs/wineboot/wineboot.c 的分支结构"
      fi
    else
      echo "wineboot aarch64 xstate 空桩已存在，跳过"
    fi
  fi


  CFLAGS="${CFLAGS/-Oz/}"
  CXXFLAGS="${CXXFLAGS/-Oz/}"
  CPPFLAGS="${CPPFLAGS/-Oz/}"
  CFLAGS="${CFLAGS/-fstack-protector-strong/}"
  CXXFLAGS="${CXXFLAGS/-fstack-protector-strong/}"
  CPPFLAGS="${CPPFLAGS/-fstack-protector-strong/}"
  LDFLAGS="${LDFLAGS/-Wl,-z,relro,-z,now/}"
  # -landroid-shmem：winex11.drv 的 MIT-SHM 路径引用 libandroid_shmget/shmat/
  # shmctl/shmdt（prefix/include/sys/shm.h 由 libandroid-shmem 提供，宏把标准
  # shm 接口映射到 libandroid_ 前缀符号）。LDFLAGS 会进每个 unix .so 的链接行
  # （lld 默认 as-needed，未用到该库的模块不会引入依赖），缺失时 winex11.so
  # 链接报 "undefined symbol: libandroid_shmget" 直接失败。
  export LDFLAGS+=" -Wl,--rosegment -landroid-shmem"

  # 注意：这里不 export {i386,x86_64,aarch64,arm64ec}_CC 覆盖为 NDK clang。
  # 这些变量是 PE 交叉编译器（非 "linux 部分"；unix .so 用的是 $CC），
  # 且 configure.ac 815-816 行 with_mingw 以 AS_VAR_SET 无条件覆盖环境
  # 传入的 ${arch}_CC（CI 实测：即便 export 了 NDK clang，探测仍走
  # llvm-mingw，记录 target=arm64ec-windows）——这几个 export 是死代码，
  # 留着只会误导排查方向。真正决定 make 期编译器的是 PATH 顺序（见上）。
  export CROSSCFLAGS="-O3 -pipe"
  export CROSSLDFLAGS="-s"

  args+=" --with-wine-tools=wine-tools-build --with-mingw=${_llvmMingwDir}/bin/clang"
}

pre_package() {
  # hangover-wine 安装在 $prefix/opt（非 opt/wine），此处仅补标识文件供
  # anwind-container / 用户识别该运行时形态；kind 依源码树实际能力落戳
  # （arm64ec = 完整新 WoW64；arm64 = 极旧树降级形态，三大默认源不触发）
  local _wineDir="${destDir}${prefix}/opt"
  if [[ -d "${_wineDir}" ]]; then
    local _kind="arm64-wow64-x11"
    if grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure.ac" 2>/dev/null || \
       grep -q "aarch64|arm64ec" "${srcDir}/${pjName}/configure" 2>/dev/null; then
      _kind="arm64ec-wow64-x11"
    fi
    cat > "${_wineDir}/.anwind-wine-info" << EOF
kind=${_kind}
tag=${ANWIND_WINE_LABEL:-hangover-${revision}}
backend=native
EOF
  fi
}
