#include "retained_observer.hpp"
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>

namespace {
std::string scenario;
constexpr auto class_limit = 16 * 1024 * 1024;
jobject loader = reinterpret_cast<jobject>(std::uintptr_t{1});
jclass type = reinterpret_cast<jclass>(std::uintptr_t{2});

jweak JNICALL weak_reference(JNIEnv *, jobject value) {
  if ((scenario == "load-allocation" && value == loader) ||
      (scenario == "prepare-allocation" && value == type))
    return nullptr;
  return static_cast<jweak>(value);
}
jboolean JNICALL same_object(JNIEnv *, jobject first, jobject second) {
  return first == second ? JNI_TRUE : JNI_FALSE;
}
void JNICALL delete_local(JNIEnv *, jobject) {}
jvmtiError JNICALL class_signature(jvmtiEnv *, jclass, char **signature, char **) {
  if (scenario == "jvmti-resource")
    return JVMTI_ERROR_OUT_OF_MEMORY;
  if (scenario == "jvmti-failure")
    return JVMTI_ERROR_INVALID_CLASS;
  *signature = static_cast<char *>(std::malloc(7));
  if (!*signature)
    return JVMTI_ERROR_OUT_OF_MEMORY;
  std::memcpy(*signature, "LCase;", 7);
  return JVMTI_ERROR_NONE;
}
jvmtiError JNICALL class_loader(jvmtiEnv *, jclass, jobject *result) {
  *result = loader;
  return JVMTI_ERROR_NONE;
}
jvmtiError JNICALL class_methods(jvmtiEnv *, jclass, jint *count, jmethodID **methods) {
  // The explicit count bound is checked before the callback reads an entry.
  *count = 257;
  *methods = nullptr;
  return JVMTI_ERROR_NONE;
}
jvmtiError JNICALL deallocate(jvmtiEnv *, unsigned char *memory) {
  std::free(memory);
  return JVMTI_ERROR_NONE;
}
} // namespace

// Each CTest invocation owns fresh single-case native state. Null weak-reference allocation and
// explicit JVMTI errors exercise production callback catches without inducing a process OOM.
int main(int argc, char **argv) {
  if (argc != 3)
    return 2;
  scenario = argv[1];
  JNINativeInterface_ jni_functions{};
  jni_functions.NewWeakGlobalRef = weak_reference;
  jni_functions.IsSameObject = same_object;
  jni_functions.DeleteLocalRef = delete_local;
  JNIEnv env{&jni_functions};
  jvmtiInterface_1_ jvmti_functions{};
  jvmti_functions.GetClassSignature = class_signature;
  jvmti_functions.GetClassLoader = class_loader;
  jvmti_functions.GetClassMethods = class_methods;
  jvmti_functions.Deallocate = deallocate;
  jvmtiEnv ti{&jvmti_functions};
  gorrect::retained::initialize(&ti, true);
  const unsigned char bytes[] = {0xca, 0xfe, 0xba, 0xbe};
  jint count = scenario == "class-limit" ? class_limit + 1 : static_cast<jint>(sizeof(bytes));
  gorrect::retained::loaded(&ti, &env, nullptr, loader, "Case", nullptr, count, bytes, nullptr,
                            nullptr);
  if (scenario != "load-allocation" && scenario != "class-limit")
    gorrect::retained::prepared(&ti, &env, nullptr, type);

  auto certificate = gorrect::retained::bind(&env, 17, type);
  bool limit = scenario == "class-limit" || scenario == "method-limit";
  auto status = limit ? gorrect::RetainedStatus::limit : gorrect::RetainedStatus::jvmti_error;
  jint error = limit                         ? 0
               : scenario == "jvmti-failure" ? JVMTI_ERROR_INVALID_CLASS
                                             : JVMTI_ERROR_OUT_OF_MEMORY;
  if (certificate.status != status || certificate.error != error || certificate.run != 17 ||
      certificate.epoch <= 0 || certificate.final ||
      (certificate.flags & gorrect::code_flag::intended_bound)) {
    std::cerr << "Failed callback certificate lost its cause: " << scenario << '\n';
    return 1;
  }
  auto encoded = gorrect::encode(certificate);
  std::ofstream output(argv[2], std::ios::binary);
  output.write(reinterpret_cast<const char *>(encoded.data()),
               static_cast<std::streamsize>(encoded.size()));
  output.flush();
  if (!output)
    return 3;

  auto final = gorrect::retained::finish(&env, 17, false);
  if (!final.final || final.status != status || final.error != error ||
      final.epoch <= certificate.epoch)
    return 4;
  return 0;
}
