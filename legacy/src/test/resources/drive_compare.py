#!/usr/bin/env python3
"""
Runtime verification for the Excel revision comparison.

Imports WK_BOQ_Rev01.xlsx and WK_BOQ_Rev02.xlsx into the current project via
the SAF picker, then opens the comparison screen and prints what it shows.

Usage: python3 drive_compare.py import | compare
"""
import re
import subprocess
import sys
import time

PKG = "com.edib.openwhispr"
ACT = f"{PKG}/com.edib.workknowledge.WorkKnowledgeActivity"
DUMP = "/data/local/tmp/wk"
PROJECT = "WK Runtime Verification"


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def launch():
    sh("am", "start", "-n", ACT)
    time.sleep(4.5)


def dump(name="ui.xml"):
    sh("uiautomator", "dump", f"{DUMP}/{name}")
    try:
        return open(f"{DUMP}/{name}", encoding="utf-8").read()
    except OSError:
        return ""


def texts(xml):
    return [m.group(1) for m in re.finditer(r'text="([^"]+)"', xml) if m.group(1).strip()]


def find(xml, pattern, attr="text"):
    rx = re.compile(pattern)
    for m in re.finditer(
        rf'{attr}="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
    ):
        if rx.search(m.group(1)):
            return (int(m.group(2)) + int(m.group(4))) // 2, (
                int(m.group(3)) + int(m.group(5))
            ) // 2
    return None


def tap(xy, wait=3.0):
    if not xy:
        return False
    sh("input", "tap", str(xy[0]), str(xy[1]))
    time.sleep(wait)
    return True


def go_home():
    for _ in range(6):
        if any(t == "WORK KNOWLEDGE" for t in texts(dump())):
            return
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(2)


def open_project():
    go_home()
    tap(find(dump(), rf"^{PROJECT}$"))


def import_files(names):
    open_project()
    xml = dump("p.xml")
    if not tap(find(xml, r"^Import more files$|^Import files$"), wait=5):
        print("  ! import button not found:", texts(xml)[:8])
        return
    # The picker opens in the fixture folder already; select each file.
    for name in names:
        xml = dump("pk.xml")
        xy = find(xml, rf"^{re.escape(name)}$")
        if not tap(xy, wait=3):
            print(f"  ! {name} not visible in picker")
            print("     ", texts(xml)[:12])
            return
        # multi-select: long press the first, then tap the rest
    sh("input", "keyevent", "KEYCODE_ENTER")
    time.sleep(6)
    print("AFTER IMPORT:", " | ".join(texts(dump("p2.xml"))[:10]))


def open_compare():
    open_project()
    xml = dump("p.xml")
    if not tap(find(xml, r"^Compare$"), wait=5):
        print("  ! Compare button not found:", texts(xml)[:8])
        return
    time.sleep(6)
    xml = dump("c.xml")
    ts = texts(xml)
    print("=== COMPARISON SCREEN")
    for t in ts[:40]:
        print("   ", t)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "import":
        import_files(sys.argv[2:])
    elif cmd == "compare":
        open_compare()
