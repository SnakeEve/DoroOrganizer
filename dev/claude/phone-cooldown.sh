#!/usr/bin/env bash
# Wait until the phone is cool enough for a perf run (DEC-108): the hottest CPU sensor in the LIVE section of
# `dumpsys thermalservice` ("Current temperatures from HAL") at or below the limit. The "Cached temperatures"
# section above it is a stale snapshot of the last thermal event and must not be read.
#
# Usage: sh dev/claude/phone-cooldown.sh [limit_C=44] [max_wait_min=15]
#   ADB=<path to adb> and ANDROID_SERIAL=<serial> are honored (one device: neither is needed).
# Prints one line with the CPU and skin readings it ends on (archive it with the perf results).
# Exit 0: cool. Exit 1: still above the limit after max_wait_min. Exit 2: no reading (no device, or no HAL section).
LIMIT=${1:-44}
MAXMIN=${2:-15}
ADB=${ADB:-adb}

live() {
  "$ADB" shell dumpsys thermalservice 2>/dev/null | tr -d '\r' |
    awk '/^Current temperatures from HAL:/ { f = 1; next } f && /^[^\t]/ { f = 0 } f'
}
cpu_max() { live | grep -o 'mValue=[0-9.]*, mType=0,' | grep -o '[0-9][0-9.]*' | sort -n | tail -1; }
skin() { live | grep -o 'mValue=[0-9.]*, mType=3, mName=skin' | grep -o '[0-9][0-9.]*' | head -1; }

end=$(( $(date +%s) + MAXMIN * 60 ))
while :; do
  t=$(cpu_max)
  if [ -z "$t" ]; then echo "phone-cooldown: no live CPU reading ($(date +%H:%M:%S))"; exit 2; fi
  if awk -v t="$t" -v l="$LIMIT" 'BEGIN { exit !(t <= l) }'; then
    echo "phone-cooldown: cool, CPU max $t C, skin $(skin) C, limit $LIMIT C ($(date +%H:%M:%S))"; exit 0
  fi
  if [ "$(date +%s)" -ge "$end" ]; then
    echo "phone-cooldown: NOT cool after $MAXMIN min, CPU max $t C, skin $(skin) C, limit $LIMIT C ($(date +%H:%M:%S))"; exit 1
  fi
  sleep 15
done
