#ifndef FT8OK_REPLIES_H
#define FT8OK_REPLIES_H

#include "ft8_lib/common/monitor.h"

/*
 * How well does the audio near one frequency and time match anything `to from` could
 * send next: RRR, RR73 in both its forms, 73, a report with or
 * without R, and, when `grid` is not empty, `to from grid` and `to from R grid`. Every
 * message of that list is known in full, so its code bits are compared with the audio
 * directly, with no decoder and no sync.
 *
 * Returns the fewest hard errors found, out of 174, and the message in out; -1 if the
 * calls cannot be packed as a standard message. Not thread-safe.
 */
int ft8ok_best_reply(const monitor_t* mon, const char* to, const char* from, const char* grid, float freq_hz,
                     float time_sec, char* out, int out_size);

#endif
