/*
 * The list of messages a partner could send next, tested against the audio.
 * A research measurement for now: its result is only logged.
 *
 * A priori decoding as WSJT-X does it clamps the bits it expects and runs the decoder
 * (lib/ft8/ft8b.f90, GPLv3). Once a partner has answered, every bit can be expected,
 * so this skips the decoder and compares whole codewords, which measured about a
 * decibel deeper. That part is ours.
 */
#include "ft8ok_replies.h"

#include <stdio.h>
#include <string.h>

#include "ft8_lib/ft8/constants.h"
#include "ft8_lib/ft8/decode.h"
#include "ft8_lib/ft8/encode.h"
#include "ft8_lib/ft8/message.h"

#define MAX_LIST 130
#define MAX_TEXT 48
#define CODE_BITS 174
#define TIME_REACH 2 /* steps of a symbol either way: +-0.4 s with the half steps */
#define FREQ_REACH 2 /* bins either way: +-15 Hz with the half bins */

static uint8_t codewords[MAX_LIST][CODE_BITS];
static char texts[MAX_LIST][MAX_TEXT];
static int list_size;

/* Nothing here may reach the session's call tables: the list holds calls never heard. */
static bool no_lookup(ftx_callsign_hash_type_t type, uint32_t hash, char* call) { return false; }
static void no_save(const char* call, uint32_t n22) {}
static ftx_callsign_hash_interface_t no_hashes = { .lookup_hash = no_lookup, .save_hash = no_save };

static void add_codeword(const uint8_t* payload, const char* text)
{
    uint8_t tones[FT8_NN];
    ft8_encode(payload, tones);
    uint8_t* bits = codewords[list_size];
    int b = 0;
    for (int s = 0; s < FT8_NN; s++)
    {
        /* The three Costas arrays carry no data. */
        if (s < 7 || (s >= 36 && s < 43) || s >= 72) continue;
        int v = 0;
        for (int g = 0; g < 8; g++)
            if (kFT8_Gray_map[g] == tones[s]) v = g;
        bits[b++] = (v >> 2) & 1;
        bits[b++] = (v >> 1) & 1;
        bits[b++] = v & 1;
    }
    snprintf(texts[list_size], MAX_TEXT, "%s", text);
    list_size++;
}

static int add_message(const char* text, int rr73_as_locator)
{
    ftx_message_t m;
    if (ftx_message_encode(&m, &no_hashes, text) != FTX_MESSAGE_RC_OK) return 0;
    if (rr73_as_locator)
    {
        /* WSJT-X packs RR73 as the locator of that name: its pack77_1 tests for a grid
           first. ft8_lib packs the special code. Both read as RR73 and both are on air,
           the locator form on 17 of 20 real ones. */
        m.payload[8] = 0x9d;
        m.payload[9] = 0x48;
    }
    add_codeword(m.payload, text);
    return 1;
}

static int build_list(const char* to, const char* from, const char* grid)
{
    char text[MAX_TEXT];
    list_size = 0;
    snprintf(text, sizeof text, "%s %s RRR", to, from);
    if (!add_message(text, 0)) return 0;
    snprintf(text, sizeof text, "%s %s 73", to, from);
    add_message(text, 0);
    snprintf(text, sizeof text, "%s %s RR73", to, from);
    add_message(text, 0);
    add_message(text, 1);
    for (int report = -30; report <= 30; report++)
    {
        snprintf(text, sizeof text, "%s %s %+03d", to, from, report);
        add_message(text, 0);
        snprintf(text, sizeof text, "%s %s R%+03d", to, from, report);
        add_message(text, 0);
    }
    /* RR73 packs as a locator, and is already in the list in both its forms. */
    if (grid != NULL && grid[0] != '\0' && strcmp(grid, "RR73") != 0)
    {
        snprintf(text, sizeof text, "%s %s %s", to, from, grid);
        add_message(text, 0);
        snprintf(text, sizeof text, "%s %s R %s", to, from, grid);
        add_message(text, 0);
    }
    return list_size;
}

int ft8ok_best_reply(const monitor_t* mon, const char* to, const char* from, const char* grid, float freq_hz,
                     float time_sec, char* out, int out_size)
{
    if (!build_list(to, from, grid)) return -1;

    const ftx_waterfall_t* wf = &mon->wf;
    const int bin = (int)(freq_hz * mon->symbol_period) - mon->min_bin;
    const int step = (int)(time_sec / mon->symbol_period);
    int best = CODE_BITS + 1, best_message = 0;

    for (int t = step - TIME_REACH; t <= step + TIME_REACH; t++)
    {
        /* A candidate must lie wholly in the waterfall: all 79 symbols, and its 8 tones. */
        if (t < 0 || t + FT8_NN >= wf->num_blocks) continue;
        for (int t_sub = 0; t_sub < wf->time_osr; t_sub++)
            for (int f = bin - FREQ_REACH; f <= bin + FREQ_REACH; f++)
            {
                if (f < 0 || f + 8 >= wf->num_bins) continue;
                for (int f_sub = 0; f_sub < wf->freq_osr; f_sub++)
                {
                    ftx_candidate_t cand = { .score = 0, .time_offset = (int16_t)t, .freq_offset = (int16_t)f, .time_sub = (uint8_t)t_sub, .freq_sub = (uint8_t)f_sub };
                    float log174[CODE_BITS];
                    ftx_ft8_log174(wf, &cand, log174);
                    for (int k = 0; k < list_size; k++)
                    {
                        int hard = 0;
                        for (int i = 0; i < CODE_BITS; i++) hard += (log174[i] > 0) != (codewords[k][i] != 0);
                        if (hard < best)
                        {
                            best = hard;
                            best_message = k;
                        }
                    }
                }
            }
    }
    if (best > CODE_BITS) return -1;
    snprintf(out, (size_t)out_size, "%s", texts[best_message]);
    return best;
}
