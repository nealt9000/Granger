// Plan I6 Task 6: TagLib for MP3 tags, as Android's JNI bridge reads them (core/taglib/src/main/cpp/taglib_jni.cpp):
// the PropertyMap, the audio properties (Average), the front cover (else the first picture), and the same "unparseable"
// rule. The file is opened read-only; nothing here can write to it.
#import "GTagLib.h"

#include <taglib/fileref.h>
#include <taglib/tpropertymap.h>
#include <taglib/tvariant.h>

@implementation GTagLibTags
@end

static NSString *GString(const TagLib::String &s) {
    const std::string utf8 = s.to8Bit(true);
    return [[NSString alloc] initWithBytes:utf8.data() length:utf8.size() encoding:NSUTF8StringEncoding] ?: @"";
}

static NSData *GFirstCover(const TagLib::FileRef &ref) {
    const auto pictures = ref.complexProperties("PICTURE");
    const TagLib::VariantMap *chosen = nullptr;
    for (const auto &picture : pictures) {
        if (!picture.value("data").toByteVector().isEmpty()) {
            if (picture.value("pictureType").toString() == "Front Cover") { chosen = &picture; break; }
            if (!chosen) chosen = &picture;
        }
    }
    if (!chosen) return nil;
    const TagLib::ByteVector data = chosen->value("data").toByteVector();
    return [NSData dataWithBytes:data.data() length:data.size()];
}

@implementation GTagLib

+ (GTagLibTags *)readPath:(NSString *)path {
    try {
        TagLib::FileRef ref(path.fileSystemRepresentation, true, TagLib::AudioProperties::Average);
        if (ref.isNull()) return nil;
        const TagLib::AudioProperties *props = ref.audioProperties();
        const TagLib::PropertyMap properties = ref.properties();
        // As Android: any bytes named *.mp3 open as an MPEG file, so one with no measurable audio and no tags is unparseable.
        if ((!props || props->sampleRate() <= 0) && properties.isEmpty() && ref.complexPropertyKeys().isEmpty()) return nil;
        NSMutableDictionary<NSString *, NSArray<NSString *> *> *map = [NSMutableDictionary dictionary];
        for (const auto &[key, list] : properties) {
            NSMutableArray<NSString *> *values = [NSMutableArray array];
            for (const auto &value : list) [values addObject:GString(value)];
            map[GString(key)] = values;
        }
        GTagLibTags *tags = [GTagLibTags new];
        tags.properties = map;
        if (props) {
            tags.lengthMs = props->lengthInMilliseconds();
            tags.sampleRate = props->sampleRate();
            tags.bitrate = props->bitrate();
            tags.channels = props->channels();
        }
        tags.cover = GFirstCover(ref);
        return tags;
    } catch (...) {
        return nil;
    }
}

@end
