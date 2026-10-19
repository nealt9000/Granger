// JNI bridge for app.granger.core.taglib.TagLib. Read-only: the stream is opened
// read-only and nothing here can write to the file.
#include <jni.h>
#include <unistd.h>

#include <cstring>
#include <string>
#include <vector>

#include <fileref.h>
#include <tfilestream.h>
#include <tpropertymap.h>
#include <tvariant.h>

namespace {

// FileStream over a file descriptor, named after the caller's hint so TagLib can
// try the file extension first (it falls back to content detection).
class HintedFileStream : public TagLib::FileStream {
 public:
  HintedFileStream(int fd, std::string hint) : TagLib::FileStream(fd, true), hint_(std::move(hint)) {}
  TagLib::FileName name() const override { return hint_.c_str(); }

 private:
  std::string hint_;
};

jstring toJString(JNIEnv *env, const TagLib::String &s) {
  // UTF-16 avoids NewStringUTF's modified-UTF-8 pitfalls with non-BMP characters.
  const TagLib::ByteVector utf16 = s.data(TagLib::String::UTF16LE);
  std::vector<jchar> chars(utf16.size() / 2);
  if (!chars.empty()) std::memcpy(chars.data(), utf16.data(), chars.size() * sizeof(jchar));
  return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

jbyteArray firstCover(JNIEnv *env, const TagLib::FileRef &ref) {
  const auto pictures = ref.complexProperties("PICTURE");
  const TagLib::VariantMap *chosen = nullptr;
  for (const auto &picture : pictures) {
    if (!picture.value("data").toByteVector().isEmpty()) {
      if (picture.value("pictureType").toString() == "Front Cover") {
        chosen = &picture;
        break;
      }
      if (!chosen) chosen = &picture;
    }
  }
  if (!chosen) return nullptr;
  const TagLib::ByteVector data = chosen->value("data").toByteVector();
  jbyteArray out = env->NewByteArray(static_cast<jsize>(data.size()));
  if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte *>(data.data()));
  return out;
}

// Returns Object[] { String[] keys, String[][] values, int[] {lengthMs, sampleRate, bitrate, channels}, byte[] cover }.
jobjectArray read(JNIEnv *env, jint fd, jstring hint) {
  std::string hintName;
  if (hint) {
    const char *chars = env->GetStringUTFChars(hint, nullptr);
    if (!chars) return nullptr;
    hintName = chars;
    env->ReleaseStringUTFChars(hint, chars);
  }

  // FileStream closes its descriptor (fclose) when destroyed; the caller owns fd, so hand TagLib a dup.
  const int own = dup(fd);
  if (own < 0) return nullptr;
  HintedFileStream stream(own, hintName);
  if (!stream.isOpen()) {
    close(own);  // fdopen failed, so the stream didn't take ownership.
    return nullptr;
  }

  TagLib::FileRef ref(&stream, true, TagLib::AudioProperties::Average);
  if (ref.isNull()) return nullptr;

  // Extension detection accepts any bytes named *.mp3 (MPEG::File is always "valid"),
  // so a file with neither audio TagLib can measure nor any tags counts as unparseable.
  const TagLib::AudioProperties *props = ref.audioProperties();
  const TagLib::PropertyMap properties = ref.properties();
  if ((!props || props->sampleRate() <= 0) && properties.isEmpty() && ref.complexPropertyKeys().isEmpty()) {
    return nullptr;
  }

  jclass stringClass = env->FindClass("java/lang/String");
  jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");
  jclass objectClass = env->FindClass("java/lang/Object");
  if (!stringClass || !stringArrayClass || !objectClass) return nullptr;

  const auto size = static_cast<jsize>(properties.size());
  jobjectArray keys = env->NewObjectArray(size, stringClass, nullptr);
  jobjectArray values = env->NewObjectArray(size, stringArrayClass, nullptr);
  if (!keys || !values) return nullptr;
  jsize i = 0;
  for (const auto &[key, list] : properties) {
    jstring jKey = toJString(env, key);
    jobjectArray jList = env->NewObjectArray(static_cast<jsize>(list.size()), stringClass, nullptr);
    if (!jKey || !jList) return nullptr;
    jsize j = 0;
    for (const auto &value : list) {
      jstring jValue = toJString(env, value);
      if (!jValue) return nullptr;
      env->SetObjectArrayElement(jList, j++, jValue);
      env->DeleteLocalRef(jValue);
    }
    env->SetObjectArrayElement(keys, i, jKey);
    env->SetObjectArrayElement(values, i, jList);
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(jList);
    ++i;
  }

  jint audio[4] = {0, 0, 0, 0};
  if (props) {
    audio[0] = props->lengthInMilliseconds();
    audio[1] = props->sampleRate();
    audio[2] = props->bitrate();
    audio[3] = props->channels();
  }
  jintArray jAudio = env->NewIntArray(4);
  if (!jAudio) return nullptr;
  env->SetIntArrayRegion(jAudio, 0, 4, audio);

  jbyteArray cover = firstCover(env, ref);
  if (env->ExceptionCheck()) return nullptr;

  jobjectArray result = env->NewObjectArray(4, objectClass, nullptr);
  if (!result) return nullptr;
  env->SetObjectArrayElement(result, 0, keys);
  env->SetObjectArrayElement(result, 1, values);
  env->SetObjectArrayElement(result, 2, jAudio);
  env->SetObjectArrayElement(result, 3, cover);
  return result;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_granger_core_taglib_TagLib_nativeRead(JNIEnv *env, jobject /* this */, jint fd, jstring hint) {
  try {
    jobjectArray result = read(env, fd, hint);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      return nullptr;
    }
    return result;
  } catch (...) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    return nullptr;
  }
}
