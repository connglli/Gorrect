#pragma once

#include "retained_wire.hpp"
#include <jni.h>
#include <jvmti.h>

namespace gorrect::retained {

// Called once during Agent_OnLoad, before Case can load. Retained mode never requests local access.
void initialize(jvmtiEnv *environment, bool bytecode_locations);
bool enabled();
void JNICALL loaded(jvmtiEnv *, JNIEnv *, jclass, jobject, const char *, jobject, jint,
                    const unsigned char *, jint *, unsigned char **);
void JNICALL prepared(jvmtiEnv *, JNIEnv *, jthread, jclass);

// Startup binds the actual intended mirror. Native storage owns only weak references and copies;
// every temporary strong mirror/loader reference is released before these functions return.
CodeEvidence bind(JNIEnv *env, jlong run, jclass intended);
CodeEvidence finish(JNIEnv *env, jlong run, bool complete);

// Thread arguments are control objects owned by the current parked cut. Each call releases its
// temporary JNI references; the Java argument array keeps only those controls alive across walking.
FrameEvidence before(JNIEnv *env, jlong run, jlong checkpoint, jobjectArray threads,
                     jintArray workers, bool terminal);
void after(JNIEnv *env, FrameEvidence &frames, jobjectArray threads);
} // namespace gorrect::retained
