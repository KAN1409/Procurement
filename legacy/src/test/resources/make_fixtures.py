#!/usr/bin/env python3
"""
Generates the two deterministic runtime-verification fixtures.

Ground truth encoded here is the same ground truth the runtime test asserts on,
so a mismatch means the pipeline (or the fixture) actually changed.

  WK_Runtime_Test.pdf        3 pages, one unique fact per page
  WK_BOQ_Runtime_Test.xlsx   2 sheets, formulas in the Amount column
"""
import os
import sys
import zlib

OUT = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- PDF fixture

PAGES = [
    [
        ("Project: WK Runtime Test", 20),
        ("Consultant: Horizon Design", 20),
    ],
    [
        ("Master Bathroom wall finish: Galala Marble", 20),
        ("Approved thickness: 20 mm", 20),
    ],
    [
        ("Purchase Request: PR-023", 20),
        ("Status: Approved", 20),
    ],
]


def esc(text: str) -> bytes:
    """Escape a PDF literal string for a WinAnsi-encoded content stream."""
    out = text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
    return out.encode("latin-1", "replace")


def content_stream(lines) -> bytes:
    parts = [b"BT /F1 14 Tf 72 720 Td 24 TL\n"]
    for text, _size in lines:
        parts.append(b"(" + esc(text) + b") Tj T*\n")
    parts.append(b"ET")
    return b"".join(parts)


def build_pdf() -> str:
    objects = []          # 1-based list of object bodies
    page_obj_ids = []

    # 1: catalog, 2: pages, 3: font. Page objects and their content follow.
    n_pages = len(PAGES)
    first_page_id = 4
    pages_id = 2

    for i in range(n_pages):
        page_obj_ids.append(first_page_id + i * 2)

    kids = " ".join(f"{pid} 0 R" for pid in page_obj_ids)
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(
        f"<< /Type /Pages /Kids [{kids}] /Count {n_pages} >>".encode("latin-1")
    )
    objects.append(
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica "
        b"/Encoding /WinAnsiEncoding >>"
    )

    for i, lines in enumerate(PAGES):
        page_id = page_obj_ids[i]
        content_id = page_id + 1
        stream = content_stream(lines)
        objects.append(
            (
                f"<< /Type /Page /Parent {pages_id} 0 R "
                f"/MediaBox [0 0 612 792] "
                f"/Resources << /Font << /F1 3 0 R >> >> "
                f"/Contents {content_id} 0 R >>"
            ).encode("latin-1")
        )
        objects.append(
            b"<< /Length "
            + str(len(stream)).encode()
            + b" >>\nstream\n"
            + stream
            + b"\nendstream"
        )

    out = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for idx, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{idx} 0 obj\n".encode("latin-1") + body + b"\nendobj\n"

    xref_pos = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode("latin-1")
    out += b"0000000000 65535 f \n"
    for off in offsets[1:]:
        out += f"{off:010d} 00000 n \n".encode("latin-1")
    out += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref_pos}\n%%EOF\n"
    ).encode("latin-1")

    path = os.path.join(OUT, "WK_Runtime_Test.pdf")
    with open(path, "wb") as fh:
        fh.write(bytes(out))
    return path


# -------------------------------------------------------------- Excel fixture

# Written as SpreadsheetML-free raw OOXML by hand: no third-party dependency,
# fully deterministic, and the Amount column carries a real formula.
SHEETS = [
    {
        "name": "Master Bathroom",
        "rows": [
            ["Item Code", "Description", "Unit", "Quantity", "Rate", "Amount"],
            ["MR-104", "Galala Marble Wall Cladding", "m²", 37.5, 1850, None],
            ["MR-105", "Piatra Grey Feature Band", "m²", 12.75, 2400, None],
        ],
    },
    {
        "name": "Ceilings",
        "rows": [
            ["Item Code", "Description", "Unit", "Quantity", "Rate", "Amount"],
            ["CL-201", "Gypsum Board Ceiling", "m²", 48, 950, None],
        ],
    },
]


def col_letter(index: int) -> str:
    # 0 -> A
    s = ""
    index += 1
    while index:
        index, rem = divmod(index - 1, 26)
        s = chr(65 + rem) + s
    return s


def sheet_xml(sheet) -> bytes:
    rows_xml = []
    for r, row in enumerate(sheet["rows"], start=1):
        cells = []
        for c, value in enumerate(row):
            ref = f"{col_letter(c)}{r}"
            if value is None:
                # Amount = Quantity * Rate  (D * E on this row)
                formula = f"D{r}*E{r}"
                cells.append(f'<c r="{ref}"><f>{formula}</f><v>0</v></c>')
            elif isinstance(value, (int, float)):
                cells.append(f'<c r="{ref}"><v>{value}</v></c>')
            else:
                safe = (
                    str(value)
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                )
                cells.append(
                    f'<c r="{ref}" t="inlineStr"><is><t xml:space="preserve">'
                    f"{safe}</t></is></c>"
                )
        rows_xml.append(f'<row r="{r}">' + "".join(cells) + "</row>")

    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        "<sheetData>" + "".join(rows_xml) + "</sheetData></worksheet>"
    ).encode("utf-8")


def build_xlsx() -> str:
    import zipfile

    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        + "".join(
            f'<Override PartName="/xl/worksheets/sheet{i + 1}.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            for i in range(len(SHEETS))
        )
        + "</Types>"
    )

    root_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="xl/workbook.xml"/>'
        "</Relationships>"
    )

    sheets_decl = "".join(
        f'<sheet name="{s["name"]}" sheetId="{i + 1}" r:id="rId{i + 1}"/>'
        for i, s in enumerate(SHEETS)
    )
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        f"<sheets>{sheets_decl}</sheets></workbook>"
    )

    wb_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + "".join(
            f'<Relationship Id="rId{i + 1}" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
            f'Target="worksheets/sheet{i + 1}.xml"/>'
            for i in range(len(SHEETS))
        )
        + "</Relationships>"
    )

    path = os.path.join(OUT, "WK_BOQ_Runtime_Test.xlsx")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", root_rels)
        z.writestr("xl/workbook.xml", workbook)
        z.writestr("xl/_rels/workbook.xml.rels", wb_rels)
        for i, sheet in enumerate(SHEETS):
            z.writestr(f"xl/worksheets/sheet{i + 1}.xml", sheet_xml(sheet))
    return path


if __name__ == "__main__":
    pdf = build_pdf()
    xlsx = build_xlsx()
    for p in (pdf, xlsx):
        print(f"{p}  ({os.path.getsize(p)} bytes)")
