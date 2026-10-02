#!/usr/bin/env python3
"""
Generates the BOQ revision pair used to verify structural comparison.

Ground truth asserted by the tests and shown in the comparison UI:

  MR-104  quantity  37.5   -> 42.0     (numeric change)
  MR-105  rate      2400   -> 2550     (numeric change)
  CL-201  description changed
  SR-301  removed in Rev02
  MR-140  added in Rev02
  MR-120  formula changed   =D*E  ->  =D*E*1.05
  MR-104/MR-105 unchanged   (must NOT be reported)

  Rev01 has a "Store Room" sheet that Rev02 does not.
  Rev02 has a "Balcony" sheet that Rev01 does not.

Rows keep the same order in both revisions so relocation is not confused with
a content change; a separate unit column proves units are compared too.
"""
import os
import zipfile

OUT = os.path.dirname(os.path.abspath(__file__))

HEADERS = ["Item Code", "Description", "Unit", "Quantity", "Rate", "Amount"]

# (item, description, unit, quantity, rate, formula_template)
# formula_template uses {q} and {r} placeholders for the Quantity/Rate columns.
MASTER_R01 = [
    ("MR-104", "Galala Marble Wall Cladding", "m2", 37.5, 1850, "={q}*{r}"),
    ("MR-105", "Piatra Grey Feature Band", "m2", 12.75, 2400, "={q}*{r}"),
    ("MR-110", "Gypsum Ceiling Board", "m2", 210, 220, "={q}*{r}"),
    ("MR-120", "Porcelain Tiles 600x600", "m2", 80, 95, "={q}*{r}"),
    ("SR-301", "Sanitary Ware Set", "set", 4, 2600, "={q}*{r}"),
]

MASTER_R02 = [
    ("MR-104", "Galala Marble Wall Cladding", "m2", 42.0, 1850, "={q}*{r}"),
    ("MR-105", "Piatra Grey Feature Band", "m2", 12.75, 2550, "={q}*{r}"),
    ("MR-110", "Gypsum Ceiling Board", "m2", 210, 220, "={q}*{r}"),
    ("MR-120", "Porcelain Tiles 600x600", "m2", 80, 95, "={q}*{r}*1.05"),
    ("MR-140", "Travertine Feature Wall", "m2", 24, 2400, "={q}*{r}"),
]

CEILINGS_R01 = [("CL-201", "Gypsum Board Ceiling", "m2", 48, 950, "={q}*{r}")]

CEILINGS_R02 = [("CL-201", "Gypsum Board Ceiling Board", "m2", 48, 950, "={q}*{r}")]

STORE_ROOM_R01 = [("SR-401", "Storage Shelving", "unit", 6, 780, "={q}*{r}")]

BALCONY_R02 = [("BL-501", "Terracotta Floor Tile", "m2", 18, 320, "={q}*{r}")]


def col_letter(i):
    s = ""
    i += 1
    while i:
        i, rem = divmod(i - 1, 26)
        s = chr(65 + rem) + s
    return s


def sheet_xml(rows):
    """Header on row 1, data from row 2. Amount carries a real formula."""
    out = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
        "<sheetData>",
    ]
    # header
    cells = []
    for c, h in enumerate(HEADERS):
        ref = f"{col_letter(c)}1"
        cells.append(
            f'<c r="{ref}" t="inlineStr"><is><t>{h}</t></is></c>'
        )
    out.append(f'<row r="1">{"".join(cells)}</row>')

    for r, (item, desc, unit, qty, rate, f) in enumerate(rows, start=2):
        formula = f.format(q=col_letter(3) + str(r), r=col_letter(4) + str(r)).lstrip("=")
        def s(ref, val):
            return f'<c r="{ref}" t="inlineStr"><is><t>{val}</t></is></c>'
        def n(ref, val):
            return f'<c r="{ref}"><v>{val}</v></c>'
        cells = [
            s(f"A{r}", item),
            s(f"B{r}", desc),
            s(f"C{r}", unit),
            n(f"D{r}", qty),
            n(f"E{r}", rate),
            f'<c r="F{r}"><f>{formula}</f><v>0</v></c>',
        ]
        out.append(f'<row r="{r}">{"".join(cells)}</row>')

    out.append("</sheetData></worksheet>")
    return "".join(out).encode("utf-8")


def build(path, sheets):
    """sheets: list of (name, rows)."""
    content_types = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">',
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>',
        '<Default Extension="xml" ContentType="application/xml"/>',
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>',
    ]
    for i in range(len(sheets)):
        content_types.append(
            f'<Override PartName="/xl/worksheets/sheet{i + 1}.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        )
    content_types.append("</Types>")

    root_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="xl/workbook.xml"/></Relationships>'
    )

    sheets_decl = "".join(
        f'<sheet name="{name}" sheetId="{i + 1}" r:id="rId{i + 1}"/>'
        for i, (name, _) in enumerate(sheets)
    )
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        f"<sheets>{sheets_decl}</sheets></workbook>"
    )

    wb_rels = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">',
    ]
    for i in range(len(sheets)):
        wb_rels.append(
            f'<Relationship Id="rId{i + 1}" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
            f'Target="worksheets/sheet{i + 1}.xml"/>'
        )
    wb_rels.append("</Relationships>")

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", "".join(content_types))
        z.writestr("_rels/.rels", root_rels)
        z.writestr("xl/workbook.xml", workbook)
        z.writestr("xl/_rels/workbook.xml.rels", "".join(wb_rels))
        for i, (_, rows) in enumerate(sheets):
            z.writestr(f"xl/worksheets/sheet{i + 1}.xml", sheet_xml(rows))


if __name__ == "__main__":
    r1 = os.path.join(OUT, "WK_BOQ_Rev01.xlsx")
    r2 = os.path.join(OUT, "WK_BOQ_Rev02.xlsx")
    build(r1, [
        ("Master Bathroom", MASTER_R01),
        ("Ceilings", CEILINGS_R01),
        ("Store Room", STORE_ROOM_R01),
    ])
    build(r2, [
        ("Master Bathroom", MASTER_R02),
        ("Ceilings", CEILINGS_R02),
        ("Balcony", BALCONY_R02),
    ])
    for p in (r1, r2):
        print(f"{p}  ({os.path.getsize(p)} bytes)")
