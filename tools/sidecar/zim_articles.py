"""The real articles of a ZIM, shared by every sidecar builder (Linked Library spec §5): redirects, metadata and
mwoffliner's redirect stubs left out."""


def is_stub(item) -> bool:
    """mwoffliner's redirect stubs: a ~200-byte HTML page with a meta refresh, not a real article."""
    if item.size >= 600:
        return False
    return b'http-equiv="refresh"' in bytes(item.content).lower().replace(b"'", b'"')


def articles(archive):
    """(path, title) for every real article in a ZIM."""
    for i in range(archive.all_entry_count):
        e = archive._get_entry_by_id(i)
        if e.is_redirect or e.path.startswith(("-/", "_", "I/", "M/", "X/")):
            continue
        try:
            it = e.get_item()
            if it.mimetype != "text/html" or is_stub(it):
                continue
        except Exception:
            continue
        yield e.path, e.title
