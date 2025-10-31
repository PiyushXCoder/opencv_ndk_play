#include <jni.h>
#include <string>
#include <android/log.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_play_1opencv_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++!";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_play_1opencv_MainActivity_processFrame(
        JNIEnv* env,
        jobject thiz,
        jobject image,
        jobject surface
        ) {

}