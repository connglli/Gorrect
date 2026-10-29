#include <jni.h>
#include <jvmti.h>

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) { return JNI_VERSION_1_8; }

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *, void *) {
  void *environment = nullptr;
  if (vm->GetEnv(&environment, JVMTI_VERSION_1_2) != JNI_OK || environment == nullptr) {
    return JNI_ERR;
  }
  return JNI_OK;
}
