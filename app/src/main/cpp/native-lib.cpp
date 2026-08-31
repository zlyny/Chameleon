#include <jni.h>
#include <string>

// 未来用于 ChameleonUltra 原生算法库


extern "C" JNIEXPORT jstring JNICALL
Java_com_example_chameleon_jni_ChameleonNative_nativeVersion(
        JNIEnv* env,
        jobject /* this */) {
    std::string version = "chameleon-native 0.1.0";
    return env->NewStringUTF(version.c_str());
}
