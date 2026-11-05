/*
 * Wine MIDI mapper - built-in software synthesizer, shared interface
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

#ifndef __WINE_MIDIMAP_SYNTH_H
#define __WINE_MIDIMAP_SYNTH_H

#include "windef.h"

#include "wine/unixlib.h"

/* Name the built-in synthesizer is announced under.  It has to fit in the
 * device name of a MIDIOUTCAPS structure, which is MAXPNAMELEN characters
 * including the terminating null. */
#define SYNTH_PORT_NAME L"Wine Software Synth"

/* Number of MIDI bytes carried by one unix call.  Long messages are split
 * into chunks of at most this size. */
#define SYNTH_CHUNK_SIZE 512

/* Maximum length of an audio driver name reported back to the Windows side,
 * including the terminating null.  FluidSynth driver names are short. */
#define SYNTH_DRIVER_NAME_MAX 32

/* All parameter structures below are made of 32-bit members and plain char
 * arrays only, so that their layout is identical in 32-bit and 64-bit builds
 * and the same handler can serve both the 32-bit and the 64-bit entry point
 * tables. */

struct synth_setup_params
{
    UINT32 devid;        /* in: device id reserved by the caller */
    UINT32 ret;          /* out: non-zero when a usable synthesizer is ready */
    UINT32 lib_ok;       /* out: the synthesizer library was loaded */
    UINT32 driver_ok;    /* out: an audio driver was opened */
    UINT32 soundfont_ok; /* out: a SoundFont was loaded */
    UINT32 driver_requested; /* out: an audio driver name was requested */
    CHAR   driver[SYNTH_DRIVER_NAME_MAX]; /* out: the audio driver that was opened */
};

struct synth_state_params
{
    UINT32 devid;
    UINT32 ret;
};

struct synth_bytes_params
{
    UINT32 devid;
    UINT32 size;
    UINT32 ret;
    BYTE   data[SYNTH_CHUNK_SIZE];
};

enum synth_funcs
{
    unix_synth_setup,
    unix_synth_teardown,
    unix_synth_open,
    unix_synth_close,
    unix_synth_bytes,
    unix_synth_reset,
    unix_synth_funcs_count,
};

/* implemented in synth.c */
BOOL         synth_setup(unsigned int devid);
void         synth_teardown(void);
BOOL         synth_is_port(unsigned int devid);
const WCHAR *synth_port_name(void);
BOOL         synth_port_open(unsigned int devid);
DWORD        synth_port_close(unsigned int devid);
DWORD        synth_port_short_msg(unsigned int devid, DWORD msg);
DWORD        synth_port_long_msg(unsigned int devid, const BYTE *data, DWORD size);
DWORD        synth_port_reset(unsigned int devid);

#endif /* __WINE_MIDIMAP_SYNTH_H */
