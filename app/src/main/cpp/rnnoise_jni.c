// Puente entre Kotlin y RNNoise (reducción de ruido con red neuronal, Xiph, licencia BSD)
#include <jni.h>
#include <stdint.h>
#include "rnnoise/rnnoise.h"

JNIEXPORT jlong JNICALL
Java_ar_gustavo_cerobudget_Rnn_crear(JNIEnv *env, jclass cls) {
    return (jlong)(intptr_t)rnnoise_create();
}

JNIEXPORT void JNICALL
Java_ar_gustavo_cerobudget_Rnn_liberar(JNIEnv *env, jclass cls, jlong h) {
    if (h) rnnoise_destroy((DenoiseState *)(intptr_t)h);
}

// Procesa 'cuadros' cuadros de 480 muestras (48 kHz, escala de 16 bits), en el lugar
JNIEXPORT void JNICALL
Java_ar_gustavo_cerobudget_Rnn_procesar(JNIEnv *env, jclass cls, jlong h, jfloatArray arr, jint cuadros) {
    DenoiseState *st = (DenoiseState *)(intptr_t)h;
    jfloat *x = (*env)->GetFloatArrayElements(env, arr, NULL);
    if (!x) return;
    float out[480];
    for (int f = 0; f < cuadros; f++) {
        float *p = x + f * 480;
        rnnoise_process_frame(st, out, p);
        for (int i = 0; i < 480; i++) p[i] = out[i];
    }
    (*env)->ReleaseFloatArrayElements(env, arr, x, 0);
}
