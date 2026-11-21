#import "GZimBridge.h"
#include <zim/archive.h>
#include <zim/entry.h>
#include <zim/item.h>
#include <zim/search.h>
#include <zim/suggestion.h>
#include <memory>
#include <sstream>

static NSString *S(const std::string &s) { return [[NSString alloc] initWithBytes:s.data() length:s.size() encoding:NSUTF8StringEncoding] ?: @""; }
static std::string C(NSString *s) { return std::string(s.UTF8String ?: ""); }

@interface GZimEntry ()
- (instancetype)initWithPath:(NSString *)path title:(NSString *)title mime:(NSString *)mime size:(int64_t)size data:(NSData *)data;
@end
@implementation GZimEntry
- (instancetype)initWithPath:(NSString *)path title:(NSString *)title mime:(NSString *)mime size:(int64_t)size data:(NSData *)data {
    if ((self = [super init])) { _path = path; _title = title; _mimeType = mime; _size = size; _data = data; }
    return self;
}
@end

@interface GZimHit ()
- (instancetype)initWithPath:(NSString *)path title:(NSString *)title snippet:(NSString *)snippet score:(int32_t)score;
@end
@implementation GZimHit
- (instancetype)initWithPath:(NSString *)path title:(NSString *)title snippet:(NSString *)snippet score:(int32_t)score {
    if ((self = [super init])) { _path = path; _title = title; _snippet = snippet; _score = score; }
    return self;
}
@end

@implementation GZimArchive {
    std::unique_ptr<zim::Archive> _archive;
    // Kept open between queries, as Android's KiwixZimArchive keeps its Searcher and SuggestionSearcher.
    std::unique_ptr<zim::SuggestionSearcher> _suggester;
    std::unique_ptr<zim::Searcher> _searcher;
    NSRecursiveLock *_lock;
}

- (instancetype)initWithPath:(NSString *)path error:(NSString **)error {
    if ((self = [super init])) {
        _lock = [NSRecursiveLock new];
        try {
            _archive = std::make_unique<zim::Archive>(C(path));
            std::ostringstream u; u << _archive->getUuid();
            _uuid = S(u.str());
        } catch (const std::exception &e) {
            if (error) *error = S(e.what());
            return nil;
        }
    }
    return self;
}

- (BOOL)hasFulltextIndex {
    [_lock lock];
    BOOL r = NO;
    try { r = _archive && _archive->hasFulltextIndex(); } catch (...) {}
    [_lock unlock];
    return r;
}

- (NSString *)mainPath {
    [_lock lock];
    NSString *r = nil;
    try { if (_archive && _archive->hasMainEntry()) r = S(_archive->getMainEntry().getItem(true).getPath()); } catch (...) {}
    [_lock unlock];
    return r;
}

- (NSString *)metadata:(NSString *)name {
    [_lock lock];
    NSString *r = @"";
    try { if (_archive) r = S(_archive->getMetadata(C(name))); } catch (...) {}
    [_lock unlock];
    return r;
}

- (NSString *)resolvePath:(NSString *)path {
    [_lock lock];
    NSString *r = nil;
    try { if (_archive) r = S(_archive->getEntryByPath(C(path)).getItem(true).getPath()); } catch (...) {}
    [_lock unlock];
    return r;
}

- (GZimEntry *)entry:(NSString *)path read:(BOOL)read {
    [_lock lock];
    GZimEntry *r = nil;
    try {
        if (_archive) {
            zim::Entry entry = _archive->getEntryByPath(C(path));
            zim::Item item = entry.getItem(true);
            NSData *data = nil;
            if (read) { zim::Blob b = item.getData(); data = [NSData dataWithBytes:b.data() length:b.size()]; }
            r = [[GZimEntry alloc] initWithPath:S(item.getPath()) title:S(item.getTitle()) mime:S(item.getMimetype()) size:(int64_t)item.getSize() data:data];
        }
    } catch (...) {}
    [_lock unlock];
    return r;
}

- (NSString *)randomPath {
    [_lock lock];
    NSString *r = nil;
    try { if (_archive) r = S(_archive->getRandomEntry().getItem(true).getPath()); } catch (...) {}
    [_lock unlock];
    return r;
}

- (NSArray<GZimHit *> *)suggest:(NSString *)query limit:(int32_t)limit {
    NSMutableArray *out = [NSMutableArray array];
    [_lock lock];
    try {
        if (_archive && limit > 0) {
            if (!_suggester) _suggester = std::make_unique<zim::SuggestionSearcher>(*_archive);
            auto results = _suggester->suggest(C(query)).getResults(0, limit);
            for (auto it = results.begin(); it != results.end(); ++it) {
                [out addObject:[[GZimHit alloc] initWithPath:S(it->getPath()) title:S(it->getTitle()) snippet:nil score:0]];
            }
        }
    } catch (...) {}
    [_lock unlock];
    return out;
}

- (NSArray<GZimHit *> *)search:(NSString *)query limit:(int32_t)limit snippets:(BOOL)snippets {
    NSMutableArray *out = [NSMutableArray array];
    [_lock lock];
    try {
        if (_archive && limit > 0 && _archive->hasFulltextIndex()) {
            if (!_searcher) _searcher = std::make_unique<zim::Searcher>(*_archive);
            auto results = _searcher->search(zim::Query(C(query))).getResults(0, limit);
            for (auto it = results.begin(); it != results.end(); ++it) {
                NSString *snippet = nil;
                if (snippets) { try { snippet = S(it.getSnippet()); } catch (...) {} }
                [out addObject:[[GZimHit alloc] initWithPath:S(it.getPath()) title:S(it.getTitle()) snippet:snippet score:it.getScore()]];
            }
        }
    } catch (...) {}
    [_lock unlock];
    return out;
}

- (BOOL)list:(NSSet<NSString *> *)mimes each:(BOOL (^)(GZimEntry *))each {
    [_lock lock];
    BOOL ok = NO;
    try {
        if (_archive) {
            for (const zim::Entry &entry : _archive->iterByPath()) {
                if (entry.isRedirect()) continue;
                zim::Item item = entry.getItem(false);
                NSString *mime = S(item.getMimetype());
                if (![mimes containsObject:mime]) continue;
                GZimEntry *e = [[GZimEntry alloc] initWithPath:S(item.getPath()) title:S(item.getTitle()) mime:mime size:(int64_t)item.getSize() data:nil];
                if (!each(e)) break;
            }
            ok = YES;
        }
    } catch (...) { ok = NO; }
    [_lock unlock];
    return ok;
}

- (void)close {
    [_lock lock];
    _suggester.reset();
    _searcher.reset();
    _archive.reset();
    [_lock unlock];
}
@end
