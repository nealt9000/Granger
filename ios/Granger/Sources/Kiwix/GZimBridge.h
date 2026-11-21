// Plan I4 Task 5: a pack file read with Kiwix's libzim (CoreKiwix.xcframework), as Objective-C for Swift. KiwixArchive
// (Swift) wraps this as the shared Kotlin ZimArchive, so every shared reader opens a pack the same way on both phones.
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// An entry after redirects: its final path, title, MIME type and size; `data` only when read.
@interface GZimEntry : NSObject
@property (nonatomic, readonly) NSString *path;
@property (nonatomic, readonly) NSString *title;
@property (nonatomic, readonly) NSString *mimeType;
@property (nonatomic, readonly) int64_t size;
@property (nonatomic, readonly, nullable) NSData *data;
@end

/// A title suggestion or full-text hit; `snippet` and `score` only for full text.
@interface GZimHit : NSObject
@property (nonatomic, readonly) NSString *path;
@property (nonatomic, readonly) NSString *title;
@property (nonatomic, readonly, nullable) NSString *snippet;
@property (nonatomic, readonly) int32_t score;
@end

/// One open ZIM file. Every call takes the archive's lock and answers nil or empty instead of throwing, also after `close`.
@interface GZimArchive : NSObject
/// Nil when the file can't be opened (missing, not a ZIM, damaged); `error` says why.
- (nullable instancetype)initWithPath:(NSString *)path error:(NSString * _Nullable * _Nullable)error;
@property (nonatomic, readonly) NSString *uuid;
@property (nonatomic, readonly) BOOL hasFulltextIndex;
@property (nonatomic, readonly, nullable) NSString *mainPath;
- (NSString *)metadata:(NSString *)name;
- (nullable NSString *)resolvePath:(NSString *)path;
/// The entry at `path` after redirects; with its bytes when `read`.
- (nullable GZimEntry *)entry:(NSString *)path read:(BOOL)read;
- (nullable NSString *)randomPath;
- (NSArray<GZimHit *> *)suggest:(NSString *)query limit:(int32_t)limit;
- (NSArray<GZimHit *> *)search:(NSString *)query limit:(int32_t)limit snippets:(BOOL)snippets;
/// Every entry whose MIME type is one of `mimes`, in path order, redirects skipped; `each` returns NO to stop. NO when it can't be listed.
- (BOOL)list:(NSSet<NSString *> *)mimes each:(BOOL (^)(GZimEntry *entry))each;
- (void)close;
@end

NS_ASSUME_NONNULL_END
