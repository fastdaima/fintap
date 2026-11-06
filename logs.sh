#!/usr/bin/env bash
# Pull the back-tap decision log + fire snapshots from the phone and summarize outcomes.
# Turn a misfire into a regression test:  cp calib/field/fire-<id>.bin calib/neg_field_<id>.bin
set -euo pipefail
cd "$(dirname "$0")"
ADB="$HOME/Android/Sdk/platform-tools/adb"
OUT=calib/field
mkdir -p "$OUT"
"$ADB" pull /sdcard/Android/data/io.github.fastdaima.fintap/files/. "$OUT" >/dev/null
rm -f "$OUT"/pos*.bin "$OUT"/neg*.bin "$OUT"/unlabeled*.bin   # calibration recordings live in calib/ already
cat "$OUT"/decisions.1.log "$OUT"/decisions.log 2>/dev/null > "$OUT/all.log" || true
echo "fires:     $(grep -c ' FIRE ' "$OUT/all.log" || true)"
echo "saved:     $(grep -c 'OUTCOME .* saved' "$OUT/all.log" || true)"
echo "dismissed: $(grep -c 'OUTCOME .* dismissed' "$OUT/all.log" || true)   <- check these for misfires"
grep 'OUTCOME .* dismissed' "$OUT/all.log" | while read -r _ _ _ id _; do
  id=${id#id=}; echo "  fire-$id.bin  $(date -d @$((id/1000)) '+%F %T')"
done
