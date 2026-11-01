#include "queue_locks.hpp"
#include <cstdint>
#include <iostream>

namespace {
std::vector<int> calls;
int enter_failure = 0, exit_failure = 0;
bool pending = false;
int identity(jobject object) { return static_cast<int>(reinterpret_cast<std::uintptr_t>(object)); }
jint JNICALL enter(JNIEnv *, jobject object) {
  int id = identity(object);
  calls.push_back(id);
  if (id == enter_failure) {
    pending = true;
    return JNI_ERR;
  }
  return JNI_OK;
}
jint JNICALL leave(JNIEnv *, jobject object) {
  int id = identity(object);
  calls.push_back(-id);
  if (id == exit_failure) {
    pending = true;
    return JNI_ERR;
  }
  return JNI_OK;
}
jboolean JNICALL exception(JNIEnv *) { return pending ? JNI_TRUE : JNI_FALSE; }
jthrowable JNICALL occurred(JNIEnv *) {
  return pending ? reinterpret_cast<jthrowable>(1) : nullptr;
}
void JNICALL clear(JNIEnv *) { pending = false; }
jint JNICALL restore(JNIEnv *, jthrowable) {
  pending = true;
  return JNI_OK;
}
void JNICALL discard(JNIEnv *, jobject) {}
void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}
void reset() {
  calls.clear();
  enter_failure = exit_failure = 0;
  pending = false;
}
} // namespace

int main() {
  try {
    JNINativeInterface_ functions{};
    functions.MonitorEnter = enter;
    functions.MonitorExit = leave;
    functions.ExceptionCheck = exception;
    functions.ExceptionOccurred = occurred;
    functions.ExceptionClear = clear;
    functions.Throw = restore;
    functions.DeleteLocalRef = discard;
    JNIEnv env{&functions};
    std::vector<jobject> locks{reinterpret_cast<jobject>(1), reinterpret_cast<jobject>(2),
                               reinterpret_cast<jobject>(3)};
    std::vector<jint> cleanup(3, JNI_ERR);
    enter_failure = 2;
    try {
      gorrect::MonitorCustody custody(&env, locks, cleanup);
      custody.acquire();
      throw std::runtime_error("partial acquisition unexpectedly passed");
    } catch (const std::runtime_error &) {
      require(calls == std::vector<int>({1, 2, -1}), "partial acquisition cleanup order");
      require(pending && cleanup[0] == JNI_OK && cleanup[1] == JNI_ERR,
              "original exception or acquisition outcome was lost");
    }
    reset();
    cleanup.assign(3, JNI_ERR);
    try {
      gorrect::MonitorCustody custody(&env, locks, cleanup);
      custody.acquire();
      throw std::runtime_error("body failure");
    } catch (const std::runtime_error &) {
      require(calls == std::vector<int>({1, 2, 3, -3, -2, -1}), "body failure reverse cleanup");
    }
    reset();
    cleanup.assign(3, JNI_ERR);
    exit_failure = 2;
    {
      gorrect::MonitorCustody custody(&env, locks, cleanup);
      custody.acquire();
      pending = true;
      custody.release();
      require(pending, "cleanup lost original exception");
      require(cleanup == std::vector<jint>({JNI_OK, JNI_ERR, JNI_OK}),
              "cleanup failure fabricated success or skipped remaining monitors");
    }
    require(calls == std::vector<int>({1, 2, 3, -3, -2, -1}), "cleanup attempted twice");
    reset();
    cleanup.assign(3, JNI_ERR);
    try {
      gorrect::MonitorCustody custody(&env, locks, cleanup);
      custody.acquire();
      custody.release();
      throw std::bad_alloc(); // Encoding happens after cleanup and must not reopen custody.
    } catch (const std::bad_alloc &) {
      require(calls == std::vector<int>({1, 2, 3, -3, -2, -1}), "encoding failure cleanup");
    }
    std::cout << "Queue monitor custody failure checks passed\n";
    return 0;
  } catch (const std::exception &failure) {
    std::cerr << failure.what() << '\n';
    return 1;
  }
}
