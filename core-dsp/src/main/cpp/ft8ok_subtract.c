/*
 * Signal subtraction, FT8 and FT4.
 *
 * A strong signal hides what lies beside it, and ft8_lib decodes only what it can see
 * in one look. Rebuilding each decoded message and taking it out of the audio uncovers
 * those: 192 messages became 271 on a busy 20 m recording (host bench).
 *
 * The method is WSJT-X's (GPLv3, as this project), from lib/ft8/subtractft8.f90 and
 * lib/ft8/ft8_downsample.f90: follow the signal's complex amplitude through a low-pass
 * filter, so fading is followed, and remove it. Written from those files' method, not
 * translated from them: the filter here is two box filters at a low rate, where
 * WSJT-X applies a cos^2 window by FFT at the full one.
 *
 * Everything but the final removal happens at a low rate (400 Hz for FT8, 500 Hz for
 * FT4), around the signal. The decoder's frequency and time are only good to a bin
 * and a step, so each must be refined first or the removal leaves most of the signal
 * behind.
 *
 * FT4 (lib/ft4/subtractft4.f90, lib/ft4/gen_ft4wave.f90): 105 symbols
 * of 576 samples, GFSK BT 1.0, the first and last symbol faded in and out over the
 * whole symbol, and a shorter amplitude filter (1400 samples against FT8's 4000), so
 * fading is followed faster.
 */
#include "ft8ok_subtract.h"

#include <complex.h>
#include <math.h>
#include <string.h>

#define RATE FT8OK_SUBTRACT_RATE
#define MESSAGE_MAX FT8OK_SUBTRACT_LENGTH /* FT8's, the longer */
#define SYMBOL_MAX 1920
#define LOW_MAX (MESSAGE_MAX / 24)        /* the smallest DECIM below */
#define SEARCH_MAX 32

typedef float complex cpx;

typedef struct
{
    int symbol;      /* samples per symbol */
    int symbols;     /* symbols per message, ramps included */
    float bt;        /* GFSK smoothing */
    int decim;       /* 12 kHz down to the low rate; divides symbol */
    int search;      /* low-rate samples either way: about the decoder's time step */
    int half_filter; /* each box filter's half width, low-rate samples */
    float df_step;   /* coarse frequency step, Hz; the fine step is a quarter */
    int ramps;       /* the first and last symbol fade in and out (FT4) */
} shape_t;

/* FT8: +-80 ms search (the decoder's time step); each box about 0.17 s, together about
   WSJT-X's 4000-sample window; frequency +-4 Hz around a 3.125 Hz decoder step. */
static const shape_t kFT8 = { 1920, 79, 2.0f, 30, 32, 33, 0.5f, 0 };

/* FT4: 500 Hz low rate; +-32 ms search around the decoder's 24 ms step; each box about
   58 ms, together about WSJT-X's 1400-sample window; frequency +-8 Hz around a 10.4 Hz
   decoder step. */
static const shape_t kFT4 = { 576, 105, 1.0f, 24, 16, 14, 1.0f, 1 };

static const shape_t* shape = &kFT8;

static float pulse[3 * SYMBOL_MAX];
static const shape_t* pulse_for = NULL;

void ft8ok_subtract_protocol(int ft4)
{
    shape = ft4 ? &kFT4 : &kFT8;
}

int ft8ok_subtract_length(void)
{
    return shape->symbols * shape->symbol;
}

static void make_pulse(void)
{
    const int sym = shape->symbol;
    const float c = (float)M_PI * sqrtf(2 / logf(2)) * shape->bt;
    for (int i = 0; i < 3 * sym; i++)
    {
        float t = i / (float)sym - 1.5f;
        pulse[i] = (erff(c * (t + .5f)) - erff(c * (t - .5f))) / 2;
    }
    pulse_for = shape;
}

/* The message as a phasor, unit amplitude but for FT4's ramps. A short series per step,
   not a sine: each step is tiny. */
static void reference(const uint8_t* tones, cpx* ref)
{
    static float dphi[MESSAGE_MAX + 2 * SYMBOL_MAX];
    const int sym = shape->symbol, syms = shape->symbols, message = syms * sym;
    const float peak = 2 * (float)M_PI / sym;
    if (pulse_for != shape) make_pulse();
    memset(dphi, 0, sizeof dphi);
    for (int i = 0; i < syms; i++)
        for (int j = 0; j < 3 * sym; j++) dphi[j + i * sym] += peak * tones[i] * pulse[j];
    /* The first and last tones held on past the ends, as ft8_lib's synthesis does.
       FT4's are both tone 0, so this adds nothing there, as in WSJT-X. */
    for (int j = 0; j < 2 * sym; j++)
    {
        dphi[j] += peak * pulse[j + sym] * tones[0];
        dphi[j + syms * sym] += peak * pulse[j] * tones[syms - 1];
    }
    double re = 1, im = 0;
    for (int k = 0; k < message; k++)
    {
        ref[k] = (float)re + I * (float)im;
        double d = dphi[k + sym], d2 = d * d;
        double c = 1 - d2 / 2, s = d - d * d2 / 6;
        double r = re * c - im * s;
        im = re * s + im * c;
        re = r;
        if ((k & 1023) == 0)
        {
            double norm = 1 / sqrt(re * re + im * im);
            re *= norm;
            im *= norm;
        }
    }
    if (shape->ramps)
    {
        /* gen_ft4wave: (1-cos)/2 over the first symbol, (1+cos)/2 over the last. */
        for (int i = 0; i < sym; i++)
        {
            const float up = (1 - cosf(2 * (float)M_PI * i / (2.0f * sym))) / 2;
            const float down = (1 + cosf(2 * (float)M_PI * i / (2.0f * sym))) / 2;
            ref[i] *= up;
            ref[message - sym + i] *= down;
        }
    }
}

void ft8ok_subtract_reference(const uint8_t* tones, float* re, float* im)
{
    static cpx ref[MESSAGE_MAX];
    reference(tones, ref);
    const int message = ft8ok_subtract_length();
    for (int k = 0; k < message; k++)
    {
        re[k] = crealf(ref[k]);
        im[k] = cimagf(ref[k]);
    }
}

/* Power of the message against the mixed audio, shifted by d, off by df Hz, summed by symbol. */
static double fit(const cpx* mixed, const cpx* low_ref, int d, double df)
{
    const int low_symbol = shape->symbol / shape->decim;
    double total = 0;
    float w = 2 * (float)M_PI * (float)df * shape->decim / RATE;
    cpx step = cosf(w) - I * sinf(w);
    cpx rot = 1;
    for (int b = 0; b < shape->symbols; b++)
    {
        cpx sum = 0;
        for (int m = b * low_symbol; m < (b + 1) * low_symbol; m++)
        {
            sum += mixed[m + d] * conjf(low_ref[m]) * rot;
            rot *= step;
        }
        total += crealf(sum) * crealf(sum) + cimagf(sum) * cimagf(sum);
    }
    return total;
}

/* Two passes of a box filter; weight counts the samples really there, for the ends. */
static void smooth(cpx* a, float* w, int low)
{
    static cpx a2[LOW_MAX];
    static float w2[LOW_MAX];
    const int h = shape->half_filter;
    for (int pass = 0; pass < 2; pass++)
    {
        cpx* from = pass ? a2 : a;
        cpx* to = pass ? a : a2;
        float* wfrom = pass ? w2 : w;
        float* wto = pass ? w : w2;
        cpx acc = 0;
        double wacc = 0;
        for (int k = -h; k < low + h; k++)
        {
            int add = k + h, drop = k - h - 1;
            if (add >= 0 && add < low)
            {
                acc += from[add];
                wacc += wfrom[add];
            }
            if (drop >= 0 && drop < low)
            {
                acc -= from[drop];
                wacc -= wfrom[drop];
            }
            if (k >= 0 && k < low)
            {
                to[k] = acc;
                wto[k] = (float)wacc;
            }
        }
    }
}

void ft8ok_subtract(float* x, int n, const uint8_t* tones, float freq0, float time0)
{
    static cpx ref[MESSAGE_MAX];
    static cpx low_ref[LOW_MAX];
    static cpx mixed[LOW_MAX + 2 * SEARCH_MAX + 2];
    static cpx amp[LOW_MAX];
    static float weight[LOW_MAX];

    const int decim = shape->decim, search = shape->search;
    const int low = ft8ok_subtract_length() / decim;

    reference(tones, ref);
    for (int m = 0; m < low; m++)
    {
        cpx sum = 0;
        for (int k = m * decim; k < (m + 1) * decim; k++) sum += ref[k];
        low_ref[m] = sum;
    }

    /* Mix the audio down to the signal once, then search at the low rate. */
    int first = (int)lrintf(time0 * RATE) - search * decim;
    float w0 = 2 * (float)M_PI * freq0 / RATE;
    cpx step = cosf(w0) - I * sinf(w0);
    double p0 = fmod(2 * M_PI * freq0 / RATE * first, 2 * M_PI);
    cpx rot = (float)cos(p0) - I * (float)sin(p0);
    for (int m = 0; m < low + 2 * search + 2; m++)
    {
        cpx sum = 0;
        for (int k = 0; k < decim; k++)
        {
            int i = first + m * decim + k;
            if (i >= 0 && i < n) sum += x[i] * rot;
            rot *= step;
        }
        rot /= cabsf(rot);
        mixed[m] = sum;
    }

    const double coarse = shape->df_step, fine = shape->df_step / 4;
    int d = search;
    double df = 0, best = -1;
    for (int t = 0; t <= 2 * search; t += 2)
    {
        double v = fit(mixed, low_ref, t, 0);
        if (v > best)
        {
            best = v;
            d = t;
        }
    }
    for (int i = -8; i <= 8; i++)
    {
        double v = fit(mixed, low_ref, d, i * coarse);
        if (v > best)
        {
            best = v;
            df = i * coarse;
        }
    }
    int d0 = d;
    for (int t = d0 - 2; t <= d0 + 2; t++)
    {
        if (t < 0 || t > 2 * search) continue;
        double v = fit(mixed, low_ref, t, df);
        if (v > best)
        {
            best = v;
            d = t;
        }
    }
    double f0 = df;
    for (int i = -4; i <= 4; i++)
    {
        double v = fit(mixed, low_ref, d, f0 + i * fine);
        if (v > best)
        {
            best = v;
            df = f0 + i * fine;
        }
    }
    /* A step is one low-rate sample (2.5 ms FT8, 2 ms FT4): a parabola through the peak
       gets inside it. */
    double frac = 0;
    if (d > 0 && d < 2 * search)
    {
        double a = fit(mixed, low_ref, d - 1, df), b = fit(mixed, low_ref, d + 1, df);
        double den = a - 2 * best + b;
        if (den < 0) frac = .5 * (a - b) / den;
    }
    int start = first + d * decim + (int)lrint(frac * decim);
    double freq = freq0 + df;

    /* Follow the amplitude at the low rate, then take the signal out at the full one. */
    float w = 2 * (float)M_PI * (float)freq / RATE;
    step = cosf(w) + I * sinf(w);
    double ps = fmod(2 * M_PI * freq / RATE * start, 2 * M_PI);
    cpx start_rot = (float)cos(ps) + I * (float)sin(ps);
    rot = start_rot;
    for (int m = 0; m < low; m++)
    {
        cpx sum = 0;
        float there = 0;
        for (int k = m * decim; k < (m + 1) * decim; k++)
        {
            int i = start + k;
            if (i >= 0 && i < n)
            {
                sum += x[i] * conjf(ref[k] * rot);
                /* The reference's own power: 1 a sample (FT8), less in FT4's ramps,
                   where a count would read the amplitude low. */
                there += shape->ramps ? crealf(ref[k]) * crealf(ref[k]) + cimagf(ref[k]) * cimagf(ref[k]) : 1;
            }
            rot *= step;
        }
        rot /= cabsf(rot);
        amp[m] = sum;
        weight[m] = there;
    }
    smooth(amp, weight, low);
    for (int m = 0; m < low; m++) amp[m] = weight[m] > decim ? amp[m] / weight[m] : 0;

    rot = start_rot;
    for (int m = 0; m < low; m++)
    {
        cpx here = amp[m], next = m + 1 < low ? amp[m + 1] : amp[m];
        for (int j = 0; j < decim; j++)
        {
            int k = m * decim + j, i = start + k;
            /* Each low-rate value belongs to the middle of its block. */
            cpx a = here + (next - here) * ((j - decim / 2 + .5f) / decim);
            if (i >= 0 && i < n) x[i] -= 2 * crealf(ref[k] * rot * a);
            rot *= step;
        }
        rot /= cabsf(rot);
    }
}
