/*
 * FT8OK native decoder bridge.
 *
 * Copyright (C) 2026 VE4OK
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Wraps ft8_lib (MIT, see ft8_lib/LICENSE) behind the narrow interface
 * ADR 0004 requires: audio window in, decodes out. Nothing above this file
 * knows which decoder is underneath, so the library stays swappable.
 *
 * The decode sequence mirrors ft8_lib's own demo/decode_ft8.c: accumulate the
 * slot into a monitor/waterfall, find Costas sync candidates, LDPC-decode each,
 * reject duplicates, then unpack to text. The callsign hash table is required
 * for messages that carry hashed callsigns rather than full ones.
 *
 * Input is 12 kHz mono float. 12 kHz is not a preference: ft8_lib rejects
 * anything else, so callers must decimate first.
 */

#include <jni.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ft8_lib/common/monitor.h"
#include "ft8_lib/ft8/message.h"
#include "ft8_lib/ft8/decode.h"
#include "ft8_lib/ft8/encode.h"
#include "ft8_lib/ft8/constants.h"
#include "ft8_lib/ft8/text.h"
#include "ft8_lib/fft/kiss_fftr.h"
#include "ft8ok_subtract.h"
#include "ft8ok_replies.h"

/* Matches ft8_lib's demo defaults; see docs for why each value. */
static const int kMinScore = 10;        /* minimum Costas sync score to try */
static const int kMaxCandidates = 140;
static const int kLdpcIterations = 25;
#define kMaxDecodedMessages 100
#define kLineLength (FTX_MAX_MESSAGE_LENGTH + 64)
static const int kMaxSubtractionRounds = 3; /* a fourth found almost nothing on a busy band */
static const float kDtBiasSec = 0.15f;  /* Ft8Decoder.DT_BIAS_SEC */
/* FT4's: measured with a host test on WSJT-X-shaped messages at four
   starts, +0.040..+0.050 s (Ft8Decoder.DT_BIAS_SEC_FT4). Not FT8's 0.15. */
static const float kDtBiasSecFt4 = 0.045f;
static const int kFreqOsr = 2;          /* frequency bin subdivision */
static const int kTimeOsr = 2;          /* symbol subdivision */

/*
 * Every call heard in full, by its hashes, kept for the whole session - never
 * cleared between decode passes or slots.
 *
 * FT8 names a call it cannot carry in full by a hash: 22 bits in a standard
 * message (`VE4OK <VE3XAA/QRP> -12`, the only way a report can reach a /QRP call),
 * 12 bits in a type 4 (`<VE4OK> VE3XAA/QRP RR73`), 10 bits as the sender of a
 * DXpedition message. The full call is nearly always in an earlier slot - the CQ
 * that was answered - so a table wiped per pass, as this one once was,
 * shows those messages as `<...>` and the contact stalls (on air, VE3XAA/QRP).
 *
 * Laid out as WSJT-X keeps them (packjt77.f90, save_hash_call): calls10 and calls12
 * indexed by the hash, the newest call taking a slot two calls share; calls22 a list
 * of the MAX_CALLS22 most recent calls, newest first. One difference: a call heard
 * again moves back to the front here, where WSJT-X only refreshes it in place, so a
 * station heard all session is not pushed out by a thousand passers-by.
 *
 * Our own call is kept apart and checked first ([g_mycall]), as WSJT-X does with
 * hashmy12/hashmy22: it is the one call a hash addressed to us must resolve to, and
 * keeping it out of the shared slots stops a passer-by with the same 12-bit hash
 * from taking its place. Decoder calls are serialised by the Kotlin side
 * (Ft8Decoder is @Synchronized), so none of this needs a lock.
 */
#define MAX_CALLS22 1000

static char g_calls10[1024][12];
static char g_calls12[4096][12];
static char g_calls22[MAX_CALLS22][12];
static uint32_t g_hash22[MAX_CALLS22];
static int g_ncalls22;

static char g_mycall[12];
static uint32_t g_myhash22;

/* WSJT-X ihashcall(c, 22), as ft8_lib's save_callsign computes it. -1 if a
   character is outside FT8's callsign alphabet. */
static int64_t callsign_hash22(const char* callsign)
{
    uint64_t n58 = 0;
    int i = 0;
    for (; callsign[i] != '\0' && i < 11; i++)
    {
        int j = nchar(callsign[i], FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
        if (j < 0) return -1;
        n58 = 38 * n58 + (uint64_t)j;
    }
    for (; i < 11; i++) n58 = 38 * n58; /* trailing spaces, index 0 */
    return (int64_t)(((47055833459ull * n58) >> (64 - 22)) & 0x3FFFFFull);
}

static void set_mycall(const char* callsign)
{
    int64_t h = callsign_hash22(callsign);
    if (h < 0 || strlen(callsign) < 3)
    {
        g_mycall[0] = '\0';
        return;
    }
    strncpy(g_mycall, callsign, 11);
    g_mycall[11] = '\0';
    g_myhash22 = (uint32_t)h;
}

static void copy_call(char* dst, const char* src)
{
    strncpy(dst, src, 11);
    dst[11] = '\0';
}

static void hashtable_add(const char* callsign, uint32_t n22)
{
    if (strlen(callsign) < 3) return;
    n22 &= 0x3FFFFFu;
    if (g_mycall[0] != '\0' && strcmp(callsign, g_mycall) == 0) return;

    copy_call(g_calls10[n22 >> 12], callsign);
    copy_call(g_calls12[n22 >> 10], callsign);

    /* Already listed: take it out, so it goes back in at the front. */
    int at = g_ncalls22;
    for (int i = 0; i < g_ncalls22; i++)
    {
        if (g_hash22[i] == n22) { at = i; break; }
    }
    if (at == g_ncalls22 && g_ncalls22 < MAX_CALLS22) g_ncalls22++;
    if (at >= MAX_CALLS22) at = MAX_CALLS22 - 1; /* full: the oldest goes */
    memmove(&g_hash22[1], &g_hash22[0], (size_t)at * sizeof(g_hash22[0]));
    memmove(&g_calls22[1], &g_calls22[0], (size_t)at * sizeof(g_calls22[0]));
    g_hash22[0] = n22;
    copy_call(g_calls22[0], callsign);
}

static bool hashtable_lookup(ftx_callsign_hash_type_t hash_type, uint32_t hash, char* callsign)
{
    callsign[0] = '\0';
    switch (hash_type)
    {
    case FTX_CALLSIGN_HASH_10_BITS:
        /* Only ever the sender of a DXpedition message: never us. */
        strcpy(callsign, g_calls10[hash & 0x3FFu]);
        break;
    case FTX_CALLSIGN_HASH_12_BITS:
        if (g_mycall[0] != '\0' && (g_myhash22 >> 10) == (hash & 0xFFFu)) strcpy(callsign, g_mycall);
        else strcpy(callsign, g_calls12[hash & 0xFFFu]);
        break;
    case FTX_CALLSIGN_HASH_22_BITS:
        if (g_mycall[0] != '\0' && g_myhash22 == (hash & 0x3FFFFFu))
        {
            strcpy(callsign, g_mycall);
            break;
        }
        for (int i = 0; i < g_ncalls22; i++)
        {
            if (g_hash22[i] == (hash & 0x3FFFFFu))
            {
                strcpy(callsign, g_calls22[i]);
                break;
            }
        }
        break;
    }
    return callsign[0] != '\0';
}

static ftx_callsign_hash_interface_t g_hash_if = {
    .lookup_hash = hashtable_lookup,
    .save_hash = hashtable_add
};

/*
 * Our own callsign, for hashes addressed to us (see the tables above). Empty or
 * unencodable clears it. Called before each decode, under the same lock.
 */
JNIEXPORT void JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeSetMyCall(JNIEnv* env, jclass clazz, jstring call)
{
    const char* text = (*env)->GetStringUTFChars(env, call, NULL);
    if (text == NULL) return;
    set_mycall(text);
    (*env)->ReleaseStringUTFChars(env, call, text);
}

/*
 * Which protocol nativeDecode looks for: FT8 (the default) or FT4, fixed per app
 * (FT8OK / FT4OK). Only the plain decode follows it: subtraction,
 * the reply list and the encoder still make FT8 waveforms, and Ft8Decoder does not
 * call them for FT4. Called before a decode, under the same lock.
 */
static ftx_protocol_t g_protocol = FTX_PROTOCOL_FT8;

JNIEXPORT void JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeSetProtocol(JNIEnv* env, jclass clazz, jboolean ft4)
{
    g_protocol = ft4 ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8;
    ft8ok_subtract_protocol(ft4 ? 1 : 0);
}

/* How much later than the true start the decoder reports a message, by protocol. */
static float dt_bias_sec(void)
{
    return g_protocol == FTX_PROTOCOL_FT4 ? kDtBiasSecFt4 : kDtBiasSec;
}

/*
 * Average power spectrum of a slot, in dB, for the occupancy map.
 *
 * Separate from decoding on purpose: the occupancy strip must show energy that is
 * present but *not* decodable - signals too weak to decode still collide with a
 * transmission, which is the whole reason the strip exists.
 * Decoded message frequencies are overlaid by the caller.
 *
 * Returns nfft/2+1 bins covering DC..Nyquist, so bin width is sampleRate/nfft.
 * A Hann window is applied: without it a strong carrier's spectral leakage smears
 * across the band and the map reads as busy everywhere, which would defeat
 * gap-finding.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeSpectrum(JNIEnv* env, jclass clazz,
                                             jfloatArray samples, jint nfft)
{
    const jsize num_samples = (*env)->GetArrayLength(env, samples);
    if (nfft < 64 || nfft > 16384 || (nfft & 1) || num_samples < nfft)
    {
        return (*env)->NewFloatArray(env, 0);
    }

    jfloat* signal = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (signal == NULL) return (*env)->NewFloatArray(env, 0);

    const int num_bins = nfft / 2 + 1;
    float* accum = (float*)calloc(num_bins, sizeof(float));
    float* window = (float*)malloc(nfft * sizeof(float));
    float* frame = (float*)malloc(nfft * sizeof(float));
    kiss_fft_cpx* spectrum = (kiss_fft_cpx*)malloc(num_bins * sizeof(kiss_fft_cpx));
    kiss_fftr_cfg cfg = kiss_fftr_alloc(nfft, 0, NULL, NULL);

    if (!accum || !window || !frame || !spectrum || !cfg)
    {
        free(accum); free(window); free(frame); free(spectrum);
        if (cfg) kiss_fftr_free(cfg);
        (*env)->ReleaseFloatArrayElements(env, samples, signal, JNI_ABORT);
        return (*env)->NewFloatArray(env, 0);
    }

    for (int i = 0; i < nfft; ++i)
    {
        window[i] = 0.5f - 0.5f * cosf(2.0f * (float)M_PI * i / (nfft - 1));
    }

    /* 50% overlap: averaging several frames across the slot suppresses the
       variation from FT8's tone hopping, leaving the occupied bandwidth. */
    const int hop = nfft / 2;
    int frames = 0;
    for (int pos = 0; pos + nfft <= num_samples; pos += hop)
    {
        for (int i = 0; i < nfft; ++i) frame[i] = signal[pos + i] * window[i];
        kiss_fftr(cfg, frame, spectrum);
        for (int b = 0; b < num_bins; ++b)
        {
            accum[b] += spectrum[b].r * spectrum[b].r + spectrum[b].i * spectrum[b].i;
        }
        frames++;
    }

    jfloatArray result = (*env)->NewFloatArray(env, num_bins);
    if (result != NULL && frames > 0)
    {
        float* out = (float*)malloc(num_bins * sizeof(float));
        if (out != NULL)
        {
            for (int b = 0; b < num_bins; ++b)
            {
                float power = accum[b] / frames;
                /* dB, floored so silence does not become -inf and poison the
                   caller's arithmetic. */
                out[b] = (power > 1e-20f) ? 10.0f * log10f(power) : -200.0f;
            }
            (*env)->SetFloatArrayRegion(env, result, 0, num_bins, out);
            free(out);
        }
    }

    kiss_fftr_free(cfg);
    free(accum); free(window); free(frame); free(spectrum);
    (*env)->ReleaseFloatArrayElements(env, samples, signal, JNI_ABORT);
    return result;
}

/*
 * What the newest plain decode found, for nativeDecodeDeeper to take out of the audio.
 * The decoder is called one at a time (Ft8Decoder.decode), so one record is enough; the
 * fingerprint catches a self test decoding other audio in between.
 */
typedef struct
{
    ftx_message_t message;
    float time_sec;
    float freq_hz;
} known_message_t;

static known_message_t g_known[kMaxDecodedMessages];
static int g_known_count = 0;
static int g_known_samples = 0;
static float g_known_mark[2];
static double g_decode_ms = 0;
static float* g_cleaned = NULL;
static int g_cleaned_samples = 0;

static double now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

/*
 * One look at the audio: the sequence of ft8_lib's demo/decode_ft8.c. Messages not yet
 * in g_known are added to it and written to lines from num_lines on.
 *
 * @return the new number of lines.
 */
static int decode_window(const float* signal, int num_samples, int sampleRate, char lines[][kLineLength], int num_lines)
{
    const double started = now_ms();
    monitor_t mon;
    monitor_config_t cfg = {
        .f_min = 0,     // 0-3100 Hz: the whole band stations use
        .f_max = 3100,
        .sample_rate = sampleRate,
        .time_osr = kTimeOsr,
        .freq_osr = kFreqOsr,
        .protocol = g_protocol
    };

    monitor_init(&mon, &cfg);

    for (int pos = 0; pos + mon.block_size <= num_samples; pos += mon.block_size)
    {
        monitor_process(&mon, signal + pos);
    }

    const ftx_waterfall_t* wf = &mon.wf;
    ftx_candidate_t candidates[kMaxCandidates];
    int num_candidates = ftx_find_candidates(wf, kMaxCandidates, candidates, kMinScore);

    for (int idx = 0; idx < num_candidates && num_lines < kMaxDecodedMessages && g_known_count < kMaxDecodedMessages; ++idx)
    {
        const ftx_candidate_t* cand = &candidates[idx];

        float freq_hz = (mon.min_bin + cand->freq_offset + (float)cand->freq_sub / wf->freq_osr) / mon.symbol_period;
        float time_sec = (cand->time_offset + (float)cand->time_sub / wf->time_osr) * mon.symbol_period;

        ftx_message_t message;
        ftx_decode_status_t status;
        if (!ftx_decode_candidate(wf, cand, kLdpcIterations, &message, &status))
        {
            continue; /* failed CRC or LDPC: not a message */
        }

        /* Reject duplicates: the same transmission is usually found by several
           candidates at neighbouring time/frequency offsets. */
        bool duplicate = false;
        for (int i = 0; i < g_known_count && !duplicate; ++i)
        {
            duplicate = g_known[i].message.hash == message.hash &&
                        0 == memcmp(g_known[i].message.payload, message.payload, sizeof(message.payload));
        }
        if (duplicate) continue;

        g_known[g_known_count].message = message;
        g_known[g_known_count].time_sec = time_sec;
        g_known[g_known_count].freq_hz = freq_hz;
        g_known_count++;

        char text[FTX_MAX_MESSAGE_LENGTH];
        ftx_message_offsets_t offsets;
        if (ftx_message_decode(&message, &g_hash_if, text, &offsets) != FTX_MESSAGE_RC_OK)
        {
            continue; /* unpacking failed: do not surface a half-read message */
        }

        snprintf(lines[num_lines], kLineLength, "%d\t%.2f\t%.0f\t%s",
                 cand->score, time_sec, freq_hz, text);
        num_lines++;
    }

    monitor_free(&mon);
    g_decode_ms = now_ms() - started;
    return num_lines;
}

/* Take g_known[i] out of audio, in the protocol's own waveform. */
static void subtract_known(float* audio, int num_samples, int i)
{
    uint8_t tones[FT4_NN]; /* the longer of the two (105, FT8 79) */
    if (g_protocol == FTX_PROTOCOL_FT4)
        ft4_encode(g_known[i].message.payload, tones);
    else
        ft8_encode(g_known[i].message.payload, tones);
    /* Where the message starts: the decoder reads DT this much high (Ft8Decoder.DT_BIAS_SEC). */
    ft8ok_subtract(audio, num_samples, tones, g_known[i].freq_hz, g_known[i].time_sec - dt_bias_sec());
}

/* A packed message's tones in the protocol in use: FT8's 79 (0-7) or FT4's 105 (0-3,
   the first and last being the ramp symbols). */
static jbyteArray tones_of(JNIEnv* env, const ftx_message_t* msg)
{
    uint8_t tones[FT4_NN]; /* the longer */
    int count;
    if (g_protocol == FTX_PROTOCOL_FT4)
    {
        ft4_encode(msg->payload, tones);
        count = FT4_NN;
    }
    else
    {
        ft8_encode(msg->payload, tones);
        count = FT8_NN;
    }
    jbyteArray result = (*env)->NewByteArray(env, count);
    if (result == NULL)
    {
        return (*env)->NewByteArray(env, 0);
    }
    (*env)->SetByteArrayRegion(env, result, 0, count, (const jbyte*)tones);
    return result;
}

static jobjectArray to_strings(JNIEnv* env, char lines[][kLineLength], int num_lines)
{
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, num_lines, stringClass, NULL);
    for (int i = 0; i < num_lines; ++i)
    {
        jstring s = (*env)->NewStringUTF(env, lines[i]);
        (*env)->SetObjectArrayElement(env, result, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return result;
}

/*
 * Decode one slot of 12 kHz mono audio.
 *
 * Returns a String[] where each entry is tab-separated:
 *     score <TAB> dt_seconds <TAB> freq_hz <TAB> message
 *
 * `score` is the raw Costas sync score, NOT an SNR. ft8_lib's demo prints
 * `score * 0.5` and labels it SNR, with its own "TODO: compute better
 * approximation of SNR" - it is a correlation strength, not a signal-to-noise
 * ratio, so no calibration offset can turn it into one. The raw
 * score is passed up unmodified rather than dressed as something it is not; the
 * Kotlin layer must not report it as an SNR, least of all in an on-air signal
 * report.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeDecode(JNIEnv* env, jclass clazz,
                                           jfloatArray samples, jint sampleRate)
{
    const jsize num_samples = (*env)->GetArrayLength(env, samples);
    jfloat* signal = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (signal == NULL)
    {
        return to_strings(env, NULL, 0);
    }

    /* Collected before touching JNI, so allocation failures cannot leave the monitor un-freed. */
    char lines[kMaxDecodedMessages][kLineLength];
    g_known_count = 0;
    int num_lines = decode_window(signal, num_samples, (int)sampleRate, lines, 0);

    g_known_samples = num_samples;
    if (num_samples > 0)
    {
        g_known_mark[0] = signal[num_samples / 2];
        g_known_mark[1] = signal[num_samples - 1];
    }
    (*env)->ReleaseFloatArrayElements(env, samples, signal, JNI_ABORT);
    return to_strings(env, lines, num_lines);
}

/*
 * Look again at the audio of the last nativeDecode, with what it found taken out:
 * a strong signal hides what lies beside it. Stops when a look finds
 * nothing new, after kMaxSubtractionRounds, or at budgetMs, whichever is first.
 *
 * Returns a String[]: first "removed <TAB> cut_short" (cut_short 1 when time ran out or
 * the audio was not the last decode's), then decodes as nativeDecode gives them with
 * <TAB> look added. looks holds a float[] per look, as long as samples: a look that
 * found something leaves there the audio it searched, with the message still in it and
 * its neighbours gone, which is where that message's report must be measured.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeDecodeDeeper(JNIEnv* env, jclass clazz,
                                                 jfloatArray samples, jint sampleRate, jlong budgetMs,
                                                 jobjectArray looks)
{
    const double deadline = now_ms() + (double)budgetMs;
    char lines[kMaxDecodedMessages + 1][kLineLength];
    int num_lines = 1;
    int removed = 0;
    int cut_short = 0;

    const jsize num_samples = (*env)->GetArrayLength(env, samples);
    float* audio = malloc(sizeof(float) * (size_t)(num_samples > 0 ? num_samples : 1));
    if (audio != NULL && num_samples > 0)
    {
        (*env)->GetFloatArrayRegion(env, samples, 0, num_samples, audio);
    }
    if (audio == NULL || num_samples == 0 || num_samples != g_known_samples ||
        audio[num_samples / 2] != g_known_mark[0] || audio[num_samples - 1] != g_known_mark[1])
    {
        cut_short = 1;
    }
    else
    {
        int done = 0;
        const int rounds = (*env)->GetArrayLength(env, looks);
        for (int round = 0; round < kMaxSubtractionRounds && round < rounds && !cut_short; ++round)
        {
            const int before = g_known_count;
            for (int i = done; i < before; ++i)
            {
                if (now_ms() >= deadline)
                {
                    cut_short = 1;
                    break;
                }
                subtract_known(audio, num_samples, i);
                removed++;
            }
            done = before;
            /* A look takes about as long as the last one did: do not start one that cannot finish. */
            if (cut_short || now_ms() + g_decode_ms >= deadline)
            {
                cut_short = 1;
                break;
            }
            const int first_line = num_lines;
            num_lines = decode_window(audio, num_samples, (int)sampleRate, lines, num_lines);
            if (g_known_count == before) break;

            jfloatArray look = (jfloatArray)(*env)->GetObjectArrayElement(env, looks, round);
            (*env)->SetFloatArrayRegion(env, look, 0, num_samples, audio);
            (*env)->DeleteLocalRef(env, look);
            for (int i = first_line; i < num_lines; ++i)
            {
                const size_t used = strlen(lines[i]);
                snprintf(lines[i] + used, kLineLength - used, "\t%d", round);
            }
        }
    }
    /* Kept for nativeBestReply: the slot with what was heard taken out. */
    free(g_cleaned);
    g_cleaned = audio;
    g_cleaned_samples = (audio != NULL && num_samples == g_known_samples) ? num_samples : 0;

    snprintf(lines[0], kLineLength, "%d\t%d", removed, cut_short);
    return to_strings(env, lines, num_lines);
}

/*
 * How well the audio near hz and dt matches anything `from` could send `to` next, and
 * the same for `decoy` in place of `to`. cleaned: use the audio the
 * last nativeDecodeDeeper left, not samples.
 *
 * Returns "hard <TAB> message <TAB> decoy_hard", or null if the calls will not pack.
 */
JNIEXPORT jstring JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeBestReply(JNIEnv* env, jclass clazz, jfloatArray samples, jint sampleRate,
                                              jboolean cleaned, jstring to, jstring decoy, jstring from,
                                              jstring grid, jfloat freqHz, jfloat dtSeconds)
{
    const jsize num_samples = (*env)->GetArrayLength(env, samples);
    const char* to_text = (*env)->GetStringUTFChars(env, to, NULL);
    const char* decoy_text = (*env)->GetStringUTFChars(env, decoy, NULL);
    const char* from_text = (*env)->GetStringUTFChars(env, from, NULL);
    const char* grid_text = (*env)->GetStringUTFChars(env, grid, NULL);
    jfloat* raw = (*env)->GetFloatArrayElements(env, samples, NULL);
    jstring result = NULL;

    if (to_text != NULL && decoy_text != NULL && from_text != NULL && grid_text != NULL && raw != NULL)
    {
        const float* audio = (cleaned && g_cleaned != NULL && g_cleaned_samples == num_samples) ? g_cleaned : raw;
        monitor_t mon;
        monitor_config_t cfg = {
            .f_min = 0,
            .f_max = 3100,
            .sample_rate = (int)sampleRate,
            .time_osr = kTimeOsr,
            .freq_osr = kFreqOsr,
            .protocol = FTX_PROTOCOL_FT8
        };
        monitor_init(&mon, &cfg);
        for (int pos = 0; pos + mon.block_size <= num_samples; pos += mon.block_size)
        {
            monitor_process(&mon, audio + pos);
        }

        char message[kLineLength];
        char decoy_message[kLineLength];
        const int hard = ft8ok_best_reply(&mon, to_text, from_text, grid_text, freqHz, dtSeconds, message, sizeof message);
        const int decoy_hard = ft8ok_best_reply(&mon, decoy_text, from_text, grid_text, freqHz, dtSeconds, decoy_message, sizeof decoy_message);
        monitor_free(&mon);

        if (hard >= 0 && decoy_hard >= 0)
        {
            char line[kLineLength + 32];
            snprintf(line, sizeof line, "%d\t%s\t%d", hard, message, decoy_hard);
            result = (*env)->NewStringUTF(env, line);
        }
    }

    if (raw != NULL) (*env)->ReleaseFloatArrayElements(env, samples, raw, JNI_ABORT);
    if (grid_text != NULL) (*env)->ReleaseStringUTFChars(env, grid, grid_text);
    if (from_text != NULL) (*env)->ReleaseStringUTFChars(env, from, from_text);
    if (decoy_text != NULL) (*env)->ReleaseStringUTFChars(env, decoy, decoy_text);
    if (to_text != NULL) (*env)->ReleaseStringUTFChars(env, to, to_text);
    return result;
}

/*
 * Encode a message into its 79 FT8 tones.
 *
 * Only the symbol encoding happens here, not the waveform. ft8_lib's packer and
 * LDPC encoder are proven code and there is nothing to gain by rewriting them,
 * while the GFSK synthesis that turns tones into audio is pure arithmetic and lives
 * in Kotlin, where it can be unit-tested on the JVM against a scripted tone
 * sequence rather than only on a radio.
 *
 * Returns a byte[] of 79 tone indices (0-7), or an empty array if the message
 * cannot be encoded - which happens for a callsign or grid the 77-bit protocol
 * cannot represent, and must be treated as "do not transmit" rather than ignored.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeEncode(JNIEnv* env, jclass clazz, jstring message)
{
    const char* text = (*env)->GetStringUTFChars(env, message, NULL);
    if (text == NULL)
    {
        return (*env)->NewByteArray(env, 0);
    }

    ftx_message_t msg;
    ftx_message_init(&msg);
    ftx_message_rc_t rc = ftx_message_encode(&msg, NULL, text);
    (*env)->ReleaseStringUTFChars(env, message, text);

    if (rc != FTX_MESSAGE_RC_OK)
    {
        return (*env)->NewByteArray(env, 0);
    }

    return tones_of(env, &msg);
}

/*
 * How ftx_message_encode would carry a message, without making tones. Our own
 * codes, so the Kotlin side does not depend on the order of ftx_message_type_t:
 * 0 = cannot be carried, 1 = free text, 2 = standard (i3 1 or 2), 3 = non-standard
 * callsign (i3 4), 4 = any other type.
 *
 * Needed because ftx_message_encode falls back to free text on its own when a
 * standard message fails, and the operator must see that before it is sent.
 */
JNIEXPORT jint JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeEncoding(JNIEnv* env, jclass clazz, jstring message)
{
    const char* text = (*env)->GetStringUTFChars(env, message, NULL);
    if (text == NULL)
    {
        return 0;
    }
    ftx_message_t msg;
    ftx_message_init(&msg);
    ftx_message_rc_t rc = ftx_message_encode(&msg, NULL, text);
    (*env)->ReleaseStringUTFChars(env, message, text);
    if (rc != FTX_MESSAGE_RC_OK)
    {
        return 0;
    }
    switch (ftx_message_get_type(&msg))
    {
    case FTX_MESSAGE_TYPE_FREE_TEXT:
        return 1;
    case FTX_MESSAGE_TYPE_STANDARD:
        return 2;
    case FTX_MESSAGE_TYPE_NONSTD_CALL:
        return 3;
    default:
        return 4;
    }
}

/*
 * What other stations' decoders will show for a message, left to ftx_message_encode:
 * it is encoded and decoded straight back. Empty if it cannot be encoded.
 *
 * Needed because the encoder does not refuse what it cannot carry faithfully - it
 * garbles it. Measured against this library on the host: "CQ HIHI TEST"
 * decodes as "CQ HIHI <...>", "W1AW VE4OK EN" as "W1AW VE4OK +00". Only a message
 * that comes back exactly as typed is sent as a standard message.
 * No callsign hash table is given, so a hashed callsign comes back as "<...>".
 */
JNIEXPORT jstring JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeDecodedAs(JNIEnv* env, jclass clazz, jstring message)
{
    const char* text = (*env)->GetStringUTFChars(env, message, NULL);
    if (text == NULL)
    {
        return (*env)->NewStringUTF(env, "");
    }
    ftx_message_t msg;
    ftx_message_init(&msg);
    ftx_message_rc_t rc = ftx_message_encode(&msg, NULL, text);
    (*env)->ReleaseStringUTFChars(env, message, text);
    if (rc != FTX_MESSAGE_RC_OK)
    {
        return (*env)->NewStringUTF(env, "");
    }
    char decoded[64] = { 0 };
    ftx_message_offsets_t offsets;
    if (ftx_message_decode(&msg, NULL, decoded, &offsets) != FTX_MESSAGE_RC_OK)
    {
        return (*env)->NewStringUTF(env, "");
    }
    return (*env)->NewStringUTF(env, decoded);
}

/*
 * Encode a message as free text and nothing else, or return an empty array if it
 * does not fit (13 characters, FT8's free-text alphabet). Used for text the operator
 * typed: left to choose, ftx_message_encode can read three plain words as a
 * compound-callsign message.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_ft8ok_dsp_Ft8Decoder_nativeEncodeFree(JNIEnv* env, jclass clazz, jstring message)
{
    const char* text = (*env)->GetStringUTFChars(env, message, NULL);
    if (text == NULL)
    {
        return (*env)->NewByteArray(env, 0);
    }
    ftx_message_t msg;
    ftx_message_init(&msg);
    ftx_message_rc_t rc = ftx_message_encode_free(&msg, text);
    (*env)->ReleaseStringUTFChars(env, message, text);
    if (rc != FTX_MESSAGE_RC_OK)
    {
        return (*env)->NewByteArray(env, 0);
    }

    return tones_of(env, &msg);
}
