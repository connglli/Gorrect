#pragma once

#include <array>
#include <cstring>
#include <jvmti.h>

namespace gorrect {
// ABI of com.ibm.GetStackTraceExtended from OpenJ9's public ibmjvmti.h. Do not infer
// frame kind from a machine PC: OpenJ9 currently reports that field as unsupported.
struct OpenJ9Frame {
  jmethodID method;
  jlocation location, machinepc;
  jint type;
  void *native_frame;
};

inline jvmtiExtensionFunction openj9_stacks(jvmtiEnv *env) {
  jint count = 0;
  jvmtiExtensionFunctionInfo *functions = nullptr;
  if (env->GetExtensionFunctions(&count, &functions) != JVMTI_ERROR_NONE)
    return nullptr;
  jvmtiExtensionFunction result = nullptr;
  for (jint index = 0; index < count; ++index) {
    auto &function = functions[index];
    if (std::strcmp(function.id, "com.ibm.GetStackTraceExtended") == 0 &&
        function.param_count == 6) {
      constexpr std::array<jvmtiParamTypes, 6> types{JVMTI_TYPE_JINT,  JVMTI_TYPE_JTHREAD,
                                                     JVMTI_TYPE_JINT,  JVMTI_TYPE_JINT,
                                                     JVMTI_TYPE_CVOID, JVMTI_TYPE_JINT};
      bool compatible = true;
      for (jint parameter = 0; parameter < 6; ++parameter)
        compatible &= function.params[parameter].base_type == types[parameter];
      if (compatible)
        result = function.func;
    }
    for (jint parameter = 0; parameter < function.param_count; ++parameter)
      env->Deallocate(reinterpret_cast<unsigned char *>(function.params[parameter].name));
    env->Deallocate(reinterpret_cast<unsigned char *>(function.id));
    env->Deallocate(reinterpret_cast<unsigned char *>(function.short_description));
    env->Deallocate(reinterpret_cast<unsigned char *>(function.params));
    env->Deallocate(reinterpret_cast<unsigned char *>(function.errors));
  }
  env->Deallocate(reinterpret_cast<unsigned char *>(functions));
  return result;
}
} // namespace gorrect
