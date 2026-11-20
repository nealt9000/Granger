"""Runs Docling over one PDF and saves its document (JSON) plus a summary of what it found.

    python probe.py <pdf> <out_dir> [--ocr | --force-ocr]

--force-ocr reads every page by sight: for PDFs whose text layer runs lines across columns.
"""
import collections
import json
import sys
import time
from pathlib import Path

from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import OcrMacOptions, PdfPipelineOptions
from docling.document_converter import DocumentConverter, PdfFormatOption


def converter(ocr: bool, force: bool = False) -> DocumentConverter:
    opts = PdfPipelineOptions()
    opts.do_ocr = ocr or force
    opts.ocr_options = OcrMacOptions(lang=["en-US"], force_full_page_ocr=force)
    opts.do_table_structure = True
    opts.generate_picture_images = True
    opts.images_scale = 2.0
    return DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)})


def main() -> None:
    pdf, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    t = time.time()
    doc = converter("--ocr" in sys.argv, "--force-ocr" in sys.argv).convert(pdf).document
    took = time.time() - t
    doc.save_as_json(out / "doc.json")
    (out / "doc.md").write_text(doc.export_to_markdown())
    labels = collections.Counter()
    heads = []
    for item, level in doc.iterate_items():
        label = str(getattr(item, "label", "?"))
        labels[label] += 1
        if label in ("section_header", "title"):
            heads.append((getattr(item, "level", None), item.prov[0].page_no if item.prov else None, item.text[:70]))
    summary = {"pdf": str(pdf), "seconds": round(took), "pages": len(doc.pages), "labels": labels, "headings": heads}
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps({k: v for k, v in summary.items() if k != "headings"}))
    print(len(heads), "headings; first 25:")
    for h in heads[:25]:
        print("  ", h)


if __name__ == "__main__":
    main()
