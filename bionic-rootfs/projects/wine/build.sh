revision="${ANWIND_WINE_TAG:-hangover-11.16}"
url="${ANWIND_WINE_URL:-https://github.com/AndreRH/wine/archive/refs/tags/hangover-11.16.tar.gz}"
urlType="tar"
arch="x86_64"
buildSys="autotools"
license="LGPL-2.1"
doNotApplyPatch=1

# ============================================================
# AnWind bionic Wine（x86_64 / X11 / WoW64）
# ------------------------------------------------------------
# 与 hangover-wine（aarch64 + qemu）互补的第二个 Wine 运行时：
#   * 目标架构 x86_64（bionic），PE 侧 i386+x86_64（新 WoW64）
#   * aarch64 设备上由 box64 加载运行（MiceWine 同款模型：
#     `box64 $PREFIX/opt/wine/bin/wine ...`）
#   * x86_64 设备上原生运行，无需模拟器
#   * 显示：仅 X11（--with-x 全套 X 扩展；无 wayland）
#     X 服务为 AnWind 内置 termux-x11/libXlorie（App 侧
#     tmp/.X11-unix/Xn，默认 :1），rootfs 内 libxcb/xtrans
#     已按 @APP_PREFIX@ 打好 socket 路径补丁
#   * 音频：PulseAudio（PULSE_SERVER=127.0.0.1:4713，由
#     anwind-x11 会话拉起；rootfs 亦自带 pulseaudio）
# 源码支持三种来源（CI 下拉选择，亦可任意环境变量覆盖）：
#   hangover  AndreRH/wine        tag    hangover-<ver>   （默认，与 arm64ec 配方同源）
#   official  wine-mirror/wine    tag    wine-<ver>       （WineHQ 官方源码 GitHub 镜像）
#   proton    ValveSoftware/wine  branch proton_<ver>     （Valve Proton 的 wine 源码树）
# 本目录的 bionic 补丁针对 hangover 树生成；official/proton 树结构相同
# （均为上游 wine 树），个别 hunk 可能因版本漂移失败——pre_setup 对此
# 容错（打印告警继续构建），失败 hunk 落 *.rej 供人工核查。
# 可用环境变量覆盖：
#   ANWIND_WINE_URL   源码 tarball 直链（三种来源皆用 GitHub archive）
#   ANWIND_WINE_TAG   版本标识（进包名 wine-<TAG>-x86_64.tar）
#   ANWIND_WINE_LABEL 运行时标识（写入 .anwind-wine-info 的 tag=，缺省用 TAG）
# ============================================================

args="
  ac_cv_header_linux_userfaultfd_h=no
  ac_cv_path_GRADLE=no
  --prefix=$prefix/opt/wine
  --libdir=$prefix/opt/wine/lib
  --includedir=$prefix/opt/wine/include
  --enable-archs=i386,x86_64
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
  --x-libraries=$prefix/lib
  --x-includes=$prefix/include
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

# 构建依赖全部来自 rootfs 既有配方（双架构可用，缺什么 build 系统
# 依据 toposort 自动补齐）：
# ※ gstreamer 必须先开启（--with-gstreamer）：wine 的 winegstreamer
#   后端依赖它做 WMF/编解码转发，proton 树还额外要求 gstreamer-gl-1.0
#   （由 gst-plugins-base gl=enabled 提供，见 gstreamer 配方注释）。
# ※ alsa：与 arm64ec 形态保持一致（winealsa unixlib 参与构建；
#   CI 产物校验两形态都要求 winealsa.so 存在）。
deps="pthread-stub alsa-lib fontconfig freetype gnutls gstreamer ffmpeg pulseaudio mesa vulkan-headers vulkan-icd-loader xkeyboard-config libxkbcommon xorgproto libxcb xtrans libX11 libXext libXrender libXfixes libXi libXrandr libXcursor libXinerama libXcomposite libXxf86vm libandroid-shmem"

# 静默编译：wine 的 make 会回显每条完整编译/链接命令（ccache 全路径），
# 单包就曾刷出 11.7 万行日志；-s 只关命令回显，编译器/链接器报错照常输出，
# 失败时仍能从 "make: *** [Makefile:...] Error 1" 定位到目标。
makeSilent=1

# PE 交叉编译器：llvm-mingw（与 hangover-wine 同版本， proven）
llvmMingwVersion="21"
llvmMingwDate="20250319"
llvmMingwUrl="https://github.com/mstorsjo/llvm-mingw/releases/download/${llvmMingwDate}/llvm-mingw-${llvmMingwDate}-ucrt-ubuntu-20.04-x86_64.tar.xz"
llvmMingwSha256="ab2a1489416fa82b3e85e88cb877053ee8a591993408caf076737d8de5ae72ca"

pre_setup() {
  local _hostTools="${wsDir}/host_tools/wine-tools-${revision}"
  local _llvmMingwDir="${wsDir}/tmp/llvm-mingw-${llvmMingwVersion}"

  echo "=== AnWind bionic wine（x86_64/X11/WoW64）构建开始 ==="

  # 0. 源码树兼容性：git 树可能不含预生成 configure（如 proton 分支）→ autogen 生成
  if [[ ! -f "${srcDir}/${pjName}/configure" ]]; then
    if [[ -f "${srcDir}/${pjName}/autogen.sh" ]]; then
      echo "源码树无预生成 configure，运行 autogen.sh ..."
      ( cd "${srcDir}/${pjName}" && ./autogen.sh ) || { echo "autogen 失败"; return 1; }
    else
      echo "错误: 源码树既无 configure 也无 autogen.sh"
      return 1
    fi
  fi

  # 1. 下载并设置 llvm-mingw 工具链（i686/x86_64 PE 目标）
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

  # 2. host 构建补丁（winegcc/winebuild 目标修正，bionic 树必需）
  #    --forward 跳过已应用的 hunk；失败仅告警不中断（official/proton 树
  #    可能已含等价修正），失败 hunk 落 *.rej。
  for _patch in "${wsDir}/projects/${pjName}"/*.patch.beforehostbuild; do
    if [[ -f "${_patch}" ]]; then
      echo "应用 host build 补丁: $(basename "${_patch}")"
      if ! sed "s%@TERMUX_PREFIX@%${prefix}%g" "${_patch}" | patch --silent --forward -p1; then
        echo "⚠️  host 补丁 $(basename "${_patch}") 有 hunk 未应用（树差异，继续构建）"
      fi
    fi
  done

  # 3. 宿主 wine 工具链（winebuild/wrc/widl/nls 皆为构建机本地产物）
  #    --enable-win64 --without-x：工具与 X11/bionic 无关，最小化依赖
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
    echo "host nls build 失败"
    return 1
  }

  cd ..

  # 4. 主构建：bionic 交叉编译（Unix 侧 x86_64-android，PE 侧 i386/x86_64）
  echo "=== 主构建: 配置 wine（--with-x X11，无 wayland） ==="
  load_env "$targetArch"
  cd "${srcDir}/${pjName}"

  # bionic 兼容补丁（路径/互斥锁属性/Socket IPX，与 hangover-wine 同源）
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

  # PE→Unix thunk 亦用 ndk clang（CC 已含版本与 ccache）
  export i386_CC="$CC"
  export x86_64_CC="$CC"

  export CROSSCFLAGS="-O3 -pipe"
  export CROSSLDFLAGS="-s"

  args+=" --with-wine-tools=wine-tools-build --with-mingw=${_llvmMingwDir}/bin/clang"
}

pre_package() {
  # 注意：不可定义 install() —— build 系统一旦发现 install() 就会
  # 跳过 `make install`（hangover-wine 亦如此，靠 configure 的
  # --prefix=$prefix/opt/wine + DESTDIR 落盘）。此处仅补版本戳，
  # 供 anwind-container 识别 wine 运行时。
  local _wineDir="${destDir}${prefix}/opt/wine"
  if [[ -d "${_wineDir}" ]]; then
    cat > "${_wineDir}/.anwind-wine-info" << EOF
kind=x86_64-wow64-x11
tag=${ANWIND_WINE_LABEL:-${revision}}
backend=box64|native
EOF
  else
    echo "警告: 未找到 wine 安装目录 ${_wineDir}，可能 make install 失败"
  fi
}

