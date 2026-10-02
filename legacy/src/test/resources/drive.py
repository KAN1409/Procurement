#!/usr/bin/env python3
"""
Runtime search / source-navigation verification driver.

Taps through the app with uiautomator and prints the screen text after each
step, so a result can be judged from what the app actually shows.

Usage:
  python3 drive.py search "MR-104" "PR-023" ...
  python3 drive.py open-project
  python3 drive.py delete-pdf
  python3 drive.py tap-result <n>        # tap the nth search result
  python3 drive.py shot <name>
"""
import re
import subprocess
import sys
import time

PKG = "com.edib.openwhispr"
ACT = f"{PKG}/com.edib.workknowledge.WorkKnowledgeActivity"
DUMP = "/data/local/tmp/wk"


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


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


def centre(xml, pattern, attr="text"):
    """Centre of the first node whose text/content-desc matches `pattern`."""
    rx = re.compile(pattern)
    for m in re.finditer(
        rf'{attr}="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
    ):
        if rx.search(m.group(1)):
            return (int(m.group(2)) + int(m.group(4))) // 2, (
                int(m.group(3)) + int(m.group(5))
            ) // 2
    return None


def tap(xy):
    if not xy:
        return False
    sh("input", "tap", str(xy[0]), str(xy[1]))
    time.sleep(3.5)
    return True


def edit_field(xml):
    m = re.search(
        r'class="android.widget.EditText"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
    )
    if not m:
        return None
    return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2


def open_search():
    launch()
    go_home()
    xml = dump()
    if not tap(centre(xml, r"^Search$", "content-desc")):
        print("  ! could not find Search button")
        return False
    return True


def go_home():
    """The activity keeps its back stack across launches, so pop until the
    project list is showing."""
    for _ in range(6):
        xml = dump()
        if any(t == "WORK KNOWLEDGE" for t in texts(xml)):
            return True
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(2)
    return False


def search(q):
    if not open_search():
        return
    xml = dump("s0.xml")
    if not tap(edit_field(xml)):
        print("  ! search field not found")
        return
    for _ in range(30):
        sh("input", "keyevent", "KEYCODE_DEL")
    time.sleep(0.5)
    sh("input", "text", q.replace(" ", "%s"))
    time.sleep(3.5)
    xml = dump("s.xml")
    print(f"=== QUERY: {q}")
    for t in texts(xml)[:9]:
        print("   ", t)


def open_project():
    launch()
    go_home()
    xml = dump()
    xy = centre(xml, r"^WK Runtime Verification$")
    if not tap(xy):
        print("  ! project row not found:", texts(xml)[:6])
        return
    xml = dump("p.xml")
    print("SCREEN:", " | ".join(texts(xml)[:10]))


def delete_pdf():
    open_project()
    xml = dump("p.xml")
    xy = centre(xml, r"Delete WK_Runtime_Test\.pdf", "content-desc")
    if not tap(xy):
        print("  ! delete button not found")
        print("     available:", re.findall(r'content-desc="(Delete[^"]*)"', xml))
        return
    xml = dump("d.xml")
    xy = centre(xml, r"^Delete$")
    if not tap(xy):
        print("  ! confirm button not found:", texts(xml)[:8])
        return
    xml = dump()
    print("AFTER DELETE:", " | ".join(texts(xml)[:10]))


def tap_result(n=1):
    xml = dump("s.xml")
    # results are the clickable rows; approximate by the provenance line
    for i, m in enumerate(
        re.finditer(
            r'text="([^"]*(?:Page \d+|Rows \d+[^"]*))"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            xml,
        )
    ):
        if i + 1 == n:
            x = (int(m.group(2)) + int(m.group(4))) // 2
            y = (int(m.group(3)) + int(m.group(5))) // 2
            sh("input", "tap", str(x), str(y))
            time.sleep(4)
            print("TAPPED RESULT", n, "->")
            print("   ", " | ".join(texts(dump("n.xml"))[:8]))
            return
    print("  ! no results to tap")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "search":
        for q in sys.argv[2:]:
            search(q)
    elif cmd == "open-project":
        open_project()
    elif cmd == "delete-pdf":
        delete_pdf()
    elif cmd == "tap-result":
        tap_result(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
