#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// One file's tags as TagLib reads them (Plan I6 Task 6): its PropertyMap, audio properties and first picture, the same
/// fields as Android's TagLib bridge (core/taglib's taglib_jni.cpp).
@interface GTagLibTags : NSObject
@property (nonatomic, copy) NSDictionary<NSString *, NSArray<NSString *> *> *properties;
@property (nonatomic) NSInteger lengthMs;
@property (nonatomic) NSInteger sampleRate;
@property (nonatomic) NSInteger bitrate;
@property (nonatomic) NSInteger channels;
@property (nonatomic, nullable, copy) NSData *cover;
@end

/// TagLib (2.3.2, LGPL-2.1/MPL-1.1, static, built by tools/ios/build-taglib.sh): read-only, used for MP3s, whose
/// multi-value ID3v2.4 frames AVAsset cuts to their first value.
@interface GTagLib : NSObject
/// The tags of the file at [path], or nil when TagLib can't parse it (neither audio it can measure nor any tags).
+ (nullable GTagLibTags *)readPath:(NSString *)path;
@end

NS_ASSUME_NONNULL_END
