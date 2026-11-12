"""The real articles of a ZIM, shared by every sidecar builder (Linked Library spec §5): redirects, metadata,
mwoffliner's redirect stubs and Wikipedia's non-article namespaces (maxi carries 2.2M Category: and Portal: pages)
left out."""

# Namespaces that hold no article; Spanish "Anexo:" (lists) is content and stays. A real title keeps a space after its
# colon ("Portal:_Still_Alive"), a namespace page doesn't.
NAMESPACES = ("Category", "Portal", "Template", "File", "Wikipedia", "Help", "Draft", "Module", "Special", "Talk",
              "Categoría", "Plantilla", "Archivo", "Ayuda", "Especial", "Discusión")


def is_namespace_page(path: str) -> bool:
    ns, colon, rest = path.partition(":")
    return bool(colon) and ns in NAMESPACES and not rest.startswith("_")


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
        if is_namespace_page(e.path):
            continue
        try:
            it = e.get_item()
            if it.mimetype != "text/html" or is_stub(it):
                continue
        except Exception:
            continue
        yield e.path, e.title
