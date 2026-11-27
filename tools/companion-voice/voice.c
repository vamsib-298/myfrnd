#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include "flite.h"

extern cst_voice *register_cmu_us_rms(const char *);
static pthread_mutex_t voice_lock = PTHREAD_MUTEX_INITIALIZER;
static cst_voice *voice;

static void put32(unsigned char *p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = (unsigned char)(v >> (8 * i));
}

// Android ABIs are little-endian. Return a mono PCM WAV that MediaPlayer can read from memory.
JNIEXPORT jbyteArray JNICALL
Java_com_wellness_overlay3d_overlay_CompanionSpeech_synthesize(JNIEnv *env, jobject self, jstring input) {
    (void)self;
    const char *text = (*env)->GetStringUTFChars(env, input, NULL);
    if (!text) return NULL;
    pthread_mutex_lock(&voice_lock);
    if (!voice) {
        flite_init();
        voice = register_cmu_us_rms(NULL);
    }
    cst_wave *wave = voice ? flite_text_to_wave(text, voice) : NULL;
    pthread_mutex_unlock(&voice_lock);
    (*env)->ReleaseStringUTFChars(env, input, text);
    if (!wave) return NULL;
    jbyteArray result = NULL;
    if (wave->num_channels == 1 && wave->num_samples > 0 && wave->num_samples < 16000 * 240) {
        int bytes = wave->num_samples * 2;
        unsigned char header[44] = {0};
        memcpy(header, "RIFF", 4);
        put32(header + 4, bytes + 36);
        memcpy(header + 8, "WAVEfmt ", 8);
        put32(header + 16, 16);
        header[20] = 1;
        header[22] = 1;
        put32(header + 24, wave->sample_rate);
        put32(header + 28, wave->sample_rate * 2);
        header[32] = 2;
        header[34] = 16;
        memcpy(header + 36, "data", 4);
        put32(header + 40, bytes);
        result = (*env)->NewByteArray(env, bytes + 44);
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, 44, (const jbyte *)header);
            (*env)->SetByteArrayRegion(env, result, 44, bytes, (const jbyte *)wave->samples);
        }
    }
    delete_wave(wave);
    return result;
}
