#!/bin/bash
# Runtime search verification.
# Usage: ./run_search.sh "MR-104" ["Piatra Grey" ...]
# Prints the result line and the first result's provenance for each query.

PKG=com.edib.openwhispr
ACT=$PKG/com.edib.workknowledge.WorkKnowledgeActivity
DUMP=/data/local/tmp/wk

for q in "$@"; do
  am start -n "$ACT" >/dev/null 2>&1
  sleep 4
  # Home -> Search
  input tap 429 426
  sleep 3

  uiautomator dump $DUMP/s0.xml >/dev/null 2>&1
  read -r EX EY <<< "$(python3 - <<'PY'
import re
s=open('/data/local/tmp/wk/s0.xml',encoding='utf-8').read()
m=re.search(r'class="android.widget.EditText"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s)
print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2 if m else '0 0')
PY
)"
  if [ "$EX" = "0" ]; then echo "  (search field not found)"; continue; fi

  input tap "$EX" "$EY"
  sleep 1
  for _ in $(seq 1 30); do input keyevent KEYCODE_DEL; done
  sleep 1
  # %s is how `input text` encodes a space
  input text "$(echo "$q" | sed 's/ /%s/g')"
  sleep 4

  uiautomator dump $DUMP/s.xml >/dev/null 2>&1
  echo "=== QUERY: $q"
  python3 - <<'PY'
import re
s=open('/data/local/tmp/wk/s.xml',encoding='utf-8').read()
ts=[m.group(1) for m in re.finditer(r'text="([^"]+)"',s) if m.group(1).strip()]
i=next((k for k,t in enumerate(ts) if t.endswith('result') or t=='No matches'),None)
if i is None:
    print("   ?  " + " | ".join(ts[-4:]))
else:
    print("   " + " | ".join(ts[i:i+6]))
PY
done
