#!/usr/bin/env bash
# redline.sh — kill the workbench JVM if the host crosses a resource red line.
# Red lines (sustained across 2 consecutive 30s samples):
#   - system-wide free memory < 8%   (memory_pressure)
#   - workbench JVM RSS > 24g        (1.5x the 16g heap cap = off-heap growth)
#   - loadavg(1m) > 24               (on a quiet host, something is wrong)
# On breach: SIGTERM then SIGKILL the server JVM and exit (the operator cleans up).
LOG="${1:-/tmp/wb_redline.log}"
BREACHES=0
while true; do
  free_pct=$(memory_pressure 2>/dev/null | awk '/free percentage/{gsub(/%/,"",$NF); print $NF}')
  pid=$(pgrep -f "dev.workbench.server.WorkbenchServer" | head -1)
  rss_kb=""
  [ -n "$pid" ] && rss_kb=$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ')
  load=$(sysctl -n vm.loadavg | awk '{print $2}')
  breach=""
  [ -n "$free_pct" ] && [ "$free_pct" -lt 8 ] && breach="free-memory ${free_pct}% < 8%"
  [ -n "$rss_kb" ] && [ "$rss_kb" -gt 25165824 ] && breach="workbench RSS $((rss_kb/1048576))g > 24g"
  if [ -n "$load" ] && [ "${load%.*}" -gt 24 ] 2>/dev/null; then breach="load1 $load > 24"; fi
  if [ -n "$breach" ]; then
    BREACHES=$((BREACHES+1))
    echo "$(date -u +%FT%TZ) breach $BREACHES/2: $breach (load=$load)" >> "$LOG"
    if [ "$BREACHES" -ge 2 ]; then
      echo "$(date -u +%FT%TZ) RED LINE SUSTAINED — killing workbench JVM (pid $pid)" >> "$LOG"
      pkill -f "dev.workbench.server.WorkbenchServer"
      sleep 3
      pkill -9 -f "dev.workbench.server.WorkbenchServer" 2>/dev/null
      echo "$(date -u +%FT%TZ) killed; exiting" >> "$LOG"
      exit 0
    fi
  else
    BREACHES=0
  fi
  sleep 30
done
