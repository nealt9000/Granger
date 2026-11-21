#import "GRarArchive.h"
#include <archive.h>
#include <archive_entry.h>

@implementation GRarArchive

+ (NSArray<NSString *> *)unpack:(NSString *)archive into:(NSString *)scratch keep:(BOOL (^)(NSString *))keep {
    struct archive *a = archive_read_new();
    if (a == NULL) return nil;
    archive_read_support_format_rar(a);
    if (archive_read_open_filename(a, archive.fileSystemRepresentation, 64 * 1024) != ARCHIVE_OK) {
        archive_read_free(a);
        return nil;
    }
    NSMutableArray<NSString *> *out = [NSMutableArray array];
    BOOL ok = YES;
    struct archive_entry *entry;
    int r;
    while ((r = archive_read_next_header(a, &entry)) == ARCHIVE_OK || r == ARCHIVE_WARN) {
        if (archive_entry_filetype(entry) != AE_IFREG) continue;
        const char *utf8 = archive_entry_pathname_utf8(entry);
        const char *raw = utf8 != NULL ? utf8 : archive_entry_pathname(entry);
        if (raw == NULL) continue;
        NSString *name = [NSString stringWithUTF8String:raw] ?: [NSString stringWithCString:raw encoding:NSISOLatin1StringEncoding];
        if (name == nil || !keep(name)) {
            if (archive_read_data_skip(a) != ARCHIVE_OK) { ok = NO; break; }
            continue;
        }
        NSString *path = [scratch stringByAppendingPathComponent:[NSString stringWithFormat:@"%lu", (unsigned long)(out.count / 2)]];
        FILE *file = fopen(path.fileSystemRepresentation, "wb");
        if (file == NULL) { ok = NO; break; }
        const void *buffer;
        size_t size;
        la_int64_t offset;
        int d;
        while ((d = archive_read_data_block(a, &buffer, &size, &offset)) == ARCHIVE_OK) {
            if (size > 0 && fwrite(buffer, 1, size, file) != size) { d = ARCHIVE_FATAL; break; }
        }
        fclose(file);
        if (d != ARCHIVE_EOF) { ok = NO; break; }
        [out addObject:name];
        [out addObject:path];
    }
    if (ok && r != ARCHIVE_EOF) ok = NO;
    archive_read_free(a);
    return ok ? out : nil;
}

@end
