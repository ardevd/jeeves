#!/usr/bin/env bash
# memwatch.sh — sample the workbench JVM's memory every 30s, append to a log.
# Columns: iso-time | pid | rss_mb | heap_used_mb | note
# Picks the LARGEST-RSS java process whose cmdline mentions dev.workbench
# (IT JVMs and the standalone server all carry dev.workbench.* main classes).
# Usage: backend/tools/memwatch.sh [logfile]   (default: backend/.workbench/mem-metrics.log)
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LOG="${1:-$HERE/.workbench/mem-metrics.log}"
mkdir -p "$(dirname "$LOG")"
while true; do
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  pid=""
  best=0
  for p in $(pgrep -f "dev\.workbench"); do
    ps -p "$p" -o comm= | grep -q java || continue
    r=$(ps -p "$p" -o rss= | tr -d ' ')
    [ -n "$r" ] && [ "$r" -gt "$best" ] && { best=$r; pid=$p; }
  done
  if [ -z "$pid" ]; then
    echo "$ts | - | - | - | no workbench JVM" >> "$LOG"
  else
    rss=$((best / 1024))
    # G1: " garbage-first heap   total 1048576K, used 24576K [0x..." → field 6
    heap=$(jcmd "$pid" GC.heap_info 2>/dev/null \
      | awk '/heap.*total.*used/{gsub(/,/,"",$6); printf "%d", $6/1024; exit}')
    [ -z "$heap" ] && heap="?"
    echo "$ts | $pid | ${rss}MB | ${heap}MB |" >> "$LOG"
  fi
  sleep 30
done
