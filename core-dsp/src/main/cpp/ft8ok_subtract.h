#ifndef FT8OK_SUBTRACT_H
#define FT8OK_SUBTRACT_H

#include <stdint.h>

#define FT8OK_SUBTRACT_RATE 12000

/*
 * Which signal shape to take out: FT8 (0, the default) or FT4 (1).
 * Set by the decoder with its own protocol, before any subtraction.
 */
void ft8ok_subtract_protocol(int ft4);

/*
 * Take one decoded message out of 12 kHz audio, in place.
 *
 * tones: the 79 tones from ft8_encode, or FT4's 105 from ft4_encode. freq_hz: tone 0,
 * as the decoder found it. start_sec: when the message starts in the audio; good to
 * about the decoder's time step (+-80 ms FT8, +-32 ms FT4), it is refined here. Not
 * thread-safe: it works in static buffers, and the decoder is called one at a time.
 */
void ft8ok_subtract(float* audio, int samples, const uint8_t* tones, float freq_hz, float start_sec);

/* The longest message in samples (FT8's); ft8ok_subtract_length() is the current mode's. */
#define FT8OK_SUBTRACT_LENGTH (79 * 1920)
int ft8ok_subtract_length(void);

/* The message as a phasor, tone 0 at 0 Hz: ft8ok_subtract_length() values, re then im. */
void ft8ok_subtract_reference(const uint8_t* tones, float* re, float* im);

#endif
