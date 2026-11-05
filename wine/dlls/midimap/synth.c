/*
 * Wine MIDI mapper - built-in software synthesizer, Windows side
 *
 * The synthesizer itself lives on the Unix side (synth_unix.c) and is reached
 * through the midimap.so unix library.  This file only translates the calls
 * the mapper makes on its MIDI out ports into unix calls, and keeps track of
 * whether a synthesizer port is available at all.
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

#include <stdarg.h>
#include <string.h>

#include "windef.h"
#include "winbase.h"
#include "wingdi.h"
#include "winuser.h"
#include "mmsystem.h"

#include "wine/debug.h"

#include "synth.h"

WINE_DEFAULT_DEBUG_CHANNEL(midi);

/* 0: not probed yet, 1: the synthesizer port is usable, -1: it is not */
static int synth_state;
/* device id reserved for the synthesizer port, only meaningful when ready */
static unsigned int synth_devid = ~0u;

BOOL synth_setup(unsigned int devid)
{
    struct synth_setup_params params;

    if (!synth_state)
    {
        memset(&params, 0, sizeof(params));
        params.devid = devid;

        if (__wine_init_unix_call() || WINE_UNIX_CALL(unix_synth_setup, &params) || !params.ret)
        {
            WARN("no built-in software synthesizer (library %u, audio driver %u, SoundFont %u)\n",
                 (unsigned int)params.lib_ok, (unsigned int)params.driver_ok,
                 (unsigned int)params.soundfont_ok);
            synth_state = -1;
        }
        else
        {
            TRACE("built-in software synthesizer ready, audio driver '%s'%s\n", params.driver,
                  params.driver_requested ? "" : " (selected automatically)");
            synth_devid = devid;
            synth_state = 1;
        }
    }

    return synth_state > 0;
}

void synth_teardown(void)
{
    if (synth_devid == ~0u) return;

    WINE_UNIX_CALL(unix_synth_teardown, NULL);
    synth_devid = ~0u;
    synth_state = 0;
}

BOOL synth_is_port(unsigned int devid)
{
    return synth_devid != ~0u && devid == synth_devid;
}

const WCHAR *synth_port_name(void)
{
    return SYNTH_PORT_NAME;
}

/* number of bytes of a channel voice message, including its status byte */
static unsigned int short_msg_size(BYTE status)
{
    switch (status & 0xf0)
    {
    case 0x80:  /* note off */
    case 0x90:  /* note on */
    case 0xa0:  /* polyphonic key pressure */
    case 0xb0:  /* control change */
    case 0xe0:  /* pitch bend change */
        return 3;
    case 0xc0:  /* program change */
    case 0xd0:  /* channel pressure */
        return 2;
    default:
        return 1;
    }
}

static DWORD send_bytes(unsigned int devid, const BYTE *data, DWORD size)
{
    struct synth_bytes_params params;

    if (!size) return MMSYSERR_NOERROR;
    if (size > SYNTH_CHUNK_SIZE) return MMSYSERR_INVALPARAM;

    memset(&params, 0, sizeof(params));
    params.devid = devid;
    params.size = size;
    memcpy(params.data, data, size);

    if (WINE_UNIX_CALL(unix_synth_bytes, &params)) return MMSYSERR_ERROR;
    return params.ret ? MMSYSERR_ERROR : MMSYSERR_NOERROR;
}

DWORD synth_port_short_msg(unsigned int devid, DWORD msg)
{
    BYTE data[3];

    if (!synth_is_port(devid)) return MMSYSERR_BADDEVICEID;

    /* a short message is packed as status | data1 << 8 | data2 << 16 */
    data[0] = LOBYTE(LOWORD(msg));
    data[1] = HIBYTE(LOWORD(msg));
    data[2] = LOBYTE(HIWORD(msg));

    return send_bytes(devid, data, short_msg_size(data[0]));
}

DWORD synth_port_long_msg(unsigned int devid, const BYTE *data, DWORD size)
{
    DWORD offset, ret;

    if (!synth_is_port(devid)) return MMSYSERR_BADDEVICEID;
    if (!data && size) return MMSYSERR_INVALPARAM;

    /* successive chunks are fed to the same byte stream, so that messages
     * which straddle a chunk boundary are handled correctly */
    for (offset = 0; offset < size; offset += SYNTH_CHUNK_SIZE)
    {
        DWORD chunk = size - offset;

        if (chunk > SYNTH_CHUNK_SIZE) chunk = SYNTH_CHUNK_SIZE;
        if ((ret = send_bytes(devid, data + offset, chunk))) return ret;
    }

    return MMSYSERR_NOERROR;
}

BOOL synth_port_open(unsigned int devid)
{
    struct synth_state_params params;

    if (!synth_is_port(devid)) return FALSE;

    memset(&params, 0, sizeof(params));
    params.devid = devid;

    if (WINE_UNIX_CALL(unix_synth_open, &params)) return FALSE;
    return !params.ret;
}

DWORD synth_port_close(unsigned int devid)
{
    struct synth_state_params params;

    if (!synth_is_port(devid)) return MMSYSERR_BADDEVICEID;

    memset(&params, 0, sizeof(params));
    params.devid = devid;

    if (WINE_UNIX_CALL(unix_synth_close, &params)) return MMSYSERR_ERROR;
    return params.ret ? MMSYSERR_ERROR : MMSYSERR_NOERROR;
}

DWORD synth_port_reset(unsigned int devid)
{
    struct synth_state_params params;

    if (!synth_is_port(devid)) return MMSYSERR_BADDEVICEID;

    memset(&params, 0, sizeof(params));
    params.devid = devid;

    if (WINE_UNIX_CALL(unix_synth_reset, &params)) return MMSYSERR_ERROR;
    return params.ret ? MMSYSERR_ERROR : MMSYSERR_NOERROR;
}
