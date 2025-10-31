#include <jni.h>
#include <string>
#include <android/log.h>

// OpenCV Headers
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

// NDK Media and Window Headers
#include <media/NdkImage.h>
#include <android/native_window_jni.h>

#include <vector>
#include <cstring>

#define TAG "From JNI"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__))

// JNI cached IDs
static struct {
    jclass imageClass;
    jmethodID getPlanesMid;
    jmethodID getWidthMid;
    jmethodID getHeightMid;

    jclass planeClass;
    jmethodID getBufferMid;
    jmethodID getPixelStrideMid;
    jmethodID getRowStrideMid;
} g_jni;

// Helper: get direct pointer from java.nio.ByteBuffer
static uint8_t* getDirectBuffer(JNIEnv* env, jobject byteBuffer, size_t* outCapacity) {
    if (byteBuffer == nullptr) {
        if (outCapacity) *outCapacity = 0;
        return nullptr;
    }
    uint8_t* addr = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (outCapacity) {
        jlong cap = env->GetDirectBufferCapacity(byteBuffer);
        *outCapacity = (size_t)cap;
    }
    return addr;
}

// Called when the native library is loaded: cache class/method IDs
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Cache android.media.Image class and methods
    jclass localImageClass = env->FindClass("android/media/Image");
    if (!localImageClass) {
        LOGE("Failed to find android.media.Image");
        return JNI_ERR;
    }
    g_jni.imageClass = reinterpret_cast<jclass>(env->NewGlobalRef(localImageClass));
    g_jni.getPlanesMid = env->GetMethodID(g_jni.imageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    g_jni.getWidthMid = env->GetMethodID(g_jni.imageClass, "getWidth", "()I");
    g_jni.getHeightMid = env->GetMethodID(g_jni.imageClass, "getHeight", "()I");

    // Cache android.media.Image$Plane and its methods
    jclass localPlaneClass = env->FindClass("android/media/Image$Plane");
    if (!localPlaneClass) {
        LOGE("Failed to find android.media.Image$Plane");
        return JNI_ERR;
    }
    g_jni.planeClass = reinterpret_cast<jclass>(env->NewGlobalRef(localPlaneClass));
    g_jni.getBufferMid = env->GetMethodID(g_jni.planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
    g_jni.getPixelStrideMid = env->GetMethodID(g_jni.planeClass, "getPixelStride", "()I");
    g_jni.getRowStrideMid = env->GetMethodID(g_jni.planeClass, "getRowStride", "()I");

    // Done
    LOGI("JNI_OnLoad completed, cached method IDs");
    return JNI_VERSION_1_6;
}

// stringFromJNI implementation (unchanged, kept for completeness)
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_play_1opencv_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++! Using OpenCV version: ";
    hello += CV_VERSION;
    return env->NewStringUTF(hello.c_str());
}


// processFrame: convert android.media.Image (YUV_420_888) -> RGBA and render to Surface
extern "C" JNIEXPORT void JNICALL
Java_com_example_play_1opencv_MainActivity_processFrame(
        JNIEnv* env,
        jobject /*thiz*/,
        jobject image,
        jobject surface) {

    if (!image) {
        LOGE("processFrame: image is null");
        return;
    }
    if (!surface) {
        LOGE("processFrame: surface is null");
        return;
    }

    // Get width/height
    jint width = env->CallIntMethod(image, g_jni.getWidthMid);
    jint height = env->CallIntMethod(image, g_jni.getHeightMid);
    if (width <= 0 || height <= 0) {
        LOGE("Invalid image dims: %d x %d", width, height);
        return;
    }

    // Get planes array
    jobjectArray planes = reinterpret_cast<jobjectArray>(env->CallObjectMethod(image, g_jni.getPlanesMid));
    if (!planes) {
        LOGE("Image.getPlanes() returned null");
        return;
    }
    jsize numPlanes = env->GetArrayLength(planes);
    if (numPlanes < 3) {
        LOGE("Unexpected plane count: %d", numPlanes);
        return;
    }

    // Read plane info and buffers
    jobject plane0 = env->GetObjectArrayElement(planes, 0);
    jobject plane1 = env->GetObjectArrayElement(planes, 1);
    jobject plane2 = env->GetObjectArrayElement(planes, 2);

    jobject buf0 = env->CallObjectMethod(plane0, g_jni.getBufferMid);
    jobject buf1 = env->CallObjectMethod(plane1, g_jni.getBufferMid);
    jobject buf2 = env->CallObjectMethod(plane2, g_jni.getBufferMid);

    int rowStrideY = env->CallIntMethod(plane0, g_jni.getRowStrideMid);
    int pixelStrideY = env->CallIntMethod(plane0, g_jni.getPixelStrideMid);

    int rowStrideU = env->CallIntMethod(plane1, g_jni.getRowStrideMid);
    int pixelStrideU = env->CallIntMethod(plane1, g_jni.getPixelStrideMid);

    int rowStrideV = env->CallIntMethod(plane2, g_jni.getRowStrideMid);
    int pixelStrideV = env->CallIntMethod(plane2, g_jni.getPixelStrideMid);

    size_t cap0 = 0, cap1 = 0, cap2 = 0;
    uint8_t* dataY = getDirectBuffer(env, buf0, &cap0);
    uint8_t* dataU = getDirectBuffer(env, buf1, &cap1);
    uint8_t* dataV = getDirectBuffer(env, buf2, &cap2);

    if (!dataY || !dataU || !dataV) {
        LOGE("One of plane buffers is null (Y:%p U:%p V:%p)", dataY, dataU, dataV);
        return;
    }

    // Prepare an I420 buffer: Y (W*H), U (W/2*H/2), V (W/2*H/2)
    const int w = width;
    const int h = height;
    const int chromaW = (w + 1) / 2;
    const int chromaH = (h + 1) / 2;
    size_t ySize = w * h;
    size_t uSize = chromaW * chromaH;
    size_t vSize = uSize;
    std::vector<uint8_t> i420;
    i420.resize(ySize + uSize + vSize);

    // Copy Y plane: account for rowStride and pixelStride (pixelStride for Y is normally 1)
    uint8_t* dstY = i420.data();
    for (int row = 0; row < h; ++row) {
        uint8_t* srcRow = dataY + (size_t)row * rowStrideY;
        // If pixelStrideY == 1, we can copy directly width bytes
        if (pixelStrideY == 1) {
            memcpy(dstY + row * w, srcRow, (size_t)w);
        } else {
            // Rare: handle pixelStride > 1 by reading each pixel
            for (int col = 0; col < w; ++col) {
                dstY[row * w + col] = srcRow[col * pixelStrideY];
            }
        }
    }

    // Copy U and V into contiguous U plane and V plane (I420 expects full planar U then V)
    uint8_t* dstU = i420.data() + ySize;
    uint8_t* dstV = i420.data() + ySize + uSize;

    // plane1 and plane2 may have pixelStride == 1 or 2 (interleaved). We handle general case.
    // We assume planes are in order Y, U, V (Android typical). If you see colors swapped, try swapping U/V.
    for (int row = 0; row < chromaH; ++row) {
        // source row pointers (each chroma row corresponds to row*rowStride on plane)
        uint8_t* srcURow = dataU + (size_t)row * rowStrideU;
        uint8_t* srcVRow = dataV + (size_t)row * rowStrideV;

        int dstRowOffset = row * chromaW;
        if (pixelStrideU == 1 && pixelStrideV == 1) {
            // simple: contiguous chroma rows
            memcpy(dstU + dstRowOffset, srcURow, (size_t)chromaW);
            memcpy(dstV + dstRowOffset, srcVRow, (size_t)chromaW);
        } else {
            // general: sample every pixelStride to build contiguous chroma plane
            for (int col = 0; col < chromaW; ++col) {
                // src index uses pixelStride
                dstU[dstRowOffset + col] = srcURow[col * pixelStrideU];
                dstV[dstRowOffset + col] = srcVRow[col * pixelStrideV];
            }
        }
    }

    // Convert I420 (YUV420p) -> RGBA using OpenCV
    cv::Mat yuvI420((int)(h + chromaH), w, CV_8UC1, i420.data());
    // yuvI420 layout: Y rows followed by U rows then V rows
    cv::Mat rgba;

    try {
        cv::cvtColor(yuvI420, rgba, cv::COLOR_YUV2RGBA_I420);
    } catch (const cv::Exception& e) {
        LOGE("OpenCV cvtColor error: %s", e.what());
        return;
    }
    cv::rotate(rgba, rgba, cv::ROTATE_90_CLOCKWISE);
    // Optional: some processing (for demo, draw a small circle)
    cv::circle(rgba, cv::Point(w/2, h/2), std::min(w,h)/8, cv::Scalar(255,0,0,255), 6);


    // Render rgba to Surface using ANativeWindow
    ANativeWindow* nativeWindow = ANativeWindow_fromSurface(env, surface);
    if (!nativeWindow) {
        LOGE("ANativeWindow_fromSurface returned NULL");
        return;
    }

    // Set buffer geometry to match image size and RGBA_8888
    if (ANativeWindow_setBuffersGeometry(nativeWindow, w, h, WINDOW_FORMAT_RGBA_8888) != 0) {
        LOGE("ANativeWindow_setBuffersGeometry failed");
        ANativeWindow_release(nativeWindow);
        return;
    }

    ANativeWindow_Buffer windowBuffer;
    if (ANativeWindow_lock(nativeWindow, &windowBuffer, nullptr) != 0) {
        LOGE("ANativeWindow_lock failed");
        ANativeWindow_release(nativeWindow);
        return;
    }

    // windowBuffer.stride is in pixels for the buffer
    uint8_t* dst = reinterpret_cast<uint8_t*>(windowBuffer.bits);
    int dstStride = windowBuffer.stride; // pixels per row in destination
    int dstRowBytes = dstStride * 4; // 4 bytes per pixel (RGBA_8888)
    int srcRowBytes = w * 4;

    // If stride matches width, copy entire block
    if (dstStride == w) {
        memcpy(dst, rgba.data, (size_t)h * srcRowBytes);
    } else {
        // copy row by row respecting destination stride
        for (int row = 0; row < h; ++row) {
            uint8_t* srcPtr = rgba.data + row * srcRowBytes;
            uint8_t* dstPtr = dst + row * dstRowBytes;
            memcpy(dstPtr, srcPtr, (size_t)srcRowBytes);
        }
    }

    ANativeWindow_unlockAndPost(nativeWindow);
    ANativeWindow_release(nativeWindow);

    // Local references cleanup
    env->DeleteLocalRef(planes);
    env->DeleteLocalRef(plane0);
    env->DeleteLocalRef(plane1);
    env->DeleteLocalRef(plane2);
    env->DeleteLocalRef(buf0);
    env->DeleteLocalRef(buf1);
    env->DeleteLocalRef(buf2);

    // Done
    LOGI("processFrame: rendered %dx%d", w, h);
}
