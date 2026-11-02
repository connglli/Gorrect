#include "native_roots.hpp"
#include <iostream>
#include <map>
#include <string>

namespace {
std::vector<std::string> calls;
std::map<jobject, jlong> tags;
std::uintptr_t next_reference = 100;
bool fail_global = false, fail_local = false, pending = false, nonnull_error = false,
     fail_tag = false;
jobjectRefType actual_kind = JNIGlobalRefType;

jobject JNICALL global(JNIEnv *, jobject value) {
  calls.push_back("global");
  if (fail_global)
    return nullptr;
  auto result = reinterpret_cast<jobject>(++next_reference);
  tags[result] = tags[value];
  return result;
}
void JNICALL delete_global(JNIEnv *, jobject value) {
  calls.push_back("delete:" + std::to_string(reinterpret_cast<std::uintptr_t>(value)));
  tags.erase(value);
}
jobject JNICALL local(JNIEnv *, jobject value) {
  calls.push_back("local");
  return fail_local ? nullptr : value;
}
void JNICALL delete_local(JNIEnv *, jobject) { calls.push_back("delete-local"); }
jboolean JNICALL exception(JNIEnv *) { return pending || nonnull_error ? JNI_TRUE : JNI_FALSE; }
jobjectRefType JNICALL kind(JNIEnv *, jobject) { return actual_kind; }
jvmtiError JNICALL tag(jvmtiEnv *, jobject value, jlong *result) {
  if (fail_tag)
    return JVMTI_ERROR_OUT_OF_MEMORY;
  *result = tags[value];
  return JVMTI_ERROR_NONE;
}
jvmtiError JNICALL set_tag(jvmtiEnv *, jobject value, jlong tag) {
  tags[value] = tag;
  return JVMTI_ERROR_NONE;
}
void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}
template <typename Action> void rejected(Action action, const char *message) {
  try {
    action();
  } catch (const std::exception &) {
    return;
  }
  throw std::runtime_error(message);
}
template <typename Action> void exhausted(Action action, const char *message) {
  try {
    action();
  } catch (const std::bad_alloc &) {
    return;
  }
  throw std::runtime_error(message);
}
} // namespace

int main() {
  try {
    JNINativeInterface_ functions{};
    functions.NewGlobalRef = global;
    functions.DeleteGlobalRef = delete_global;
    functions.NewLocalRef = local;
    functions.DeleteLocalRef = delete_local;
    functions.ExceptionCheck = exception;
    functions.GetObjectRefType = kind;
    JNIEnv env{&functions};
    jvmtiInterface_1_ tooling{};
    tooling.GetTag = tag;
    tooling.SetTag = set_tag;
    jvmtiEnv jvmti{&tooling};
    auto value = reinterpret_cast<jobject>(1);
    tags[value] = 7;
    gorrect::NativeRoots roots;
    roots.configure(73, 2, 3, 4);
    jlong next_tag = 1;
    rejected([&] { roots.configure(73, 2, 3, 4); }, "duplicate setup accepted");
    rejected([&] { roots.write(&env, 72, 0, value); }, "cross-run write accepted");
    rejected([&] { roots.write(&env, 73, 2, value); }, "invalid slot accepted");
    require(calls.empty(), "invalid write acquired reference");
    roots.write(&env, 73, 0, value);
    auto first = roots.borrowed(73, 0);
    require(first && calls == std::vector<std::string>{"global"}, "initial acquisition");
    auto rows = roots.rows(&env, &jvmti, 73, next_tag, 4);
    require(rows[0].generation == 1 && rows[0].tag == 7, "initial actual evidence");
    actual_kind = JNILocalRefType;
    rejected([&] { roots.rows(&env, &jvmti, 73, next_tag, 4); },
             "wrong native reference kind accepted");
    actual_kind = JNIGlobalRefType;
    fail_tag = true;
    exhausted([&] { roots.rows(&env, &jvmti, 73, next_tag, 4); },
              "tag resource failure misclassified");
    fail_tag = false;
    require(roots.borrowed(73, 0) == first, "failed evidence changed slot");
    calls.clear();
    for (bool with_exception : {false, true}) {
      fail_global = true;
      pending = with_exception;
      exhausted([&] { roots.write(&env, 73, 0, value); },
                "null global acquisition was not a resource failure");
      require(roots.borrowed(73, 0) == first && pending == with_exception,
              "failed acquisition changed slot or pending exception");
      pending = false;
      fail_global = false;
    }
    nonnull_error = true;
    exhausted([&] { roots.write(&env, 73, 0, value); },
              "exceptional global acquisition was not a resource failure");
    require(roots.borrowed(73, 0) == first && tags.size() == 2,
            "exceptional replacement leaked or destroyed previous global");
    nonnull_error = false;
    calls.clear();
    roots.write(&env, 73, 0, value);
    auto second = roots.borrowed(73, 0);
    require(second != first && calls == std::vector<std::string>{"global", "delete:101"},
            "replacement was not acquired before deletion");
    require(roots.rows(&env, &jvmti, 73, next_tag, 4)[0].generation == 2,
            "failure consumed acquisition generation");
    for (bool with_exception : {false, true}) {
      fail_local = true;
      pending = with_exception;
      exhausted([&] { roots.read(&env, 73, 0); },
                "null local acquisition was not a resource failure");
      require(roots.borrowed(73, 0) == second && pending == with_exception,
              "failed local acquisition changed global or pending exception");
      pending = false;
      fail_local = false;
    }
    nonnull_error = true;
    exhausted([&] { roots.read(&env, 73, 0); },
              "exceptional local acquisition was not a resource failure");
    nonnull_error = false;
    require(roots.read(&env, 73, 0) == second, "successful independent local result");
    roots.write(&env, 73, 1, value);
    calls.clear();
    rejected([&] { roots.write(&env, 73, 0, value); }, "acquisition quota accepted");
    require(calls.empty() && roots.borrowed(73, 0) == second, "quota changed state");
    rejected([&] { roots.rows(&env, &jvmti, 73, next_tag, 3); }, "resource record quota accepted");
    roots.write(&env, 73, 0, nullptr);
    require(roots.read(&env, 73, 0) == nullptr, "null read acquired handle");
    require(roots.rows(&env, &jvmti, 73, next_tag, 4)[0].generation == 2,
            "null deletion reused or erased generation");
    pending = true;
    roots.close(&env, 73);
    require(pending && tags.size() == 1, "cleanup lost exception or leaked global");
    auto closed = roots.rows(&env, &jvmti, 73, next_tag, 4, true);
    require(closed[0].handle == 0 && closed[1].handle == 0 && closed[1].generation == 3,
            "closed table evidence");
    rejected([&] { roots.read(&env, 73, 0); }, "closed read accepted");
    rejected([&] { roots.write(&env, 73, 0, nullptr); }, "closed write accepted");
    rejected([&] { roots.close(&env, 73); }, "closed cleanup reopened run");
    pending = false;
    gorrect::NativeRoots partial;
    partial.configure(74, 2, 4, 4);
    partial.write(&env, 74, 0, value);
    fail_global = true;
    rejected([&] { partial.write(&env, 74, 1, value); }, "partial init fault accepted");
    fail_global = false;
    partial.close(&env, 74);
    require(tags.size() == 1, "partial initialization cleanup leaked global");
    for (int slots : {0, 65}) {
      gorrect::NativeRoots invalid;
      rejected([&] { invalid.configure(75, slots, 4, 130); }, "slot bound accepted");
    }
    std::cout << "Native root failure and ownership checks passed\n";
    return 0;
  } catch (const std::exception &failure) {
    std::cerr << failure.what() << '\n';
    return 1;
  }
}
