#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// A RAR archive (a CBR comic) read with libarchive (Plan I5 call 6), where Android uses junrar. Only RAR 4, as junrar.
@interface GRarArchive : NSObject

/// Unpacks the entries of the RAR archive at `archive` whose names `keep` accepts into the folder `scratch`, in the
/// archive's own order (a solid archive unpacks no other way), each as `<scratch>/<n>`. Returns the names and paths in
/// pairs (name, path, name, path…), or nil when it isn't a RAR archive libarchive can read (RAR 5, encrypted, damaged).
+ (nullable NSArray<NSString *> *)unpack:(NSString *)archive into:(NSString *)scratch keep:(BOOL (^)(NSString *name))keep;

@end

NS_ASSUME_NONNULL_END
