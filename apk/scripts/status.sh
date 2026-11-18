#!/system/bin/sh
# 状态文件会跨重启保留，必须和当前进程 pid 对比，
# 否则重启后会拿旧 pid 的 ok=1 误判成"已注入"。
CFG=/data/system/uninstall-hook-native.cfg
LIST=$(grep '^inject=' "$CFG" 2>/dev/null | cut -d= -f2)
[ -z "$LIST" ] && LIST="system_server,com.vecentek.decoreapp,com.android.systemui,com.iflytek.autofly.voicecoreservice"
for P in $(echo "$LIST" | tr ',' ' '); do
  echo "=== $P ==="
  F="/data/system/nativehook-$P.status"
  LIVE=$(pidof "$P" 2>/dev/null)
  LIVE=${LIVE%% *}
  if [ ! -f "$F" ]; then
    echo "ok=0"
    echo "missing=1"
    echo "live_pid=$LIVE"
    continue
  fi
  SPID=$(grep '^pid=' "$F" 2>/dev/null | head -n 1 | cut -d= -f2)
  if [ -n "$LIVE" ] && [ "$SPID" = "$LIVE" ]; then
    cat "$F"
  else
    echo "ok=0"
    echo "pid=$SPID"
    echo "live_pid=$LIVE"
    echo "stale=1"
  fi
done
echo "STATUS_DONE"