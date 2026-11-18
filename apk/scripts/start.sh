#!/system/bin/sh
D=/data/local/tmp/hook-native
CFG=/data/system/uninstall-hook-native.cfg
if [ ! -x "$D/nhinject" ]; then
  echo "missing nhinject"
  exit 2
fi
LIST=$(grep '^inject=' "$CFG" 2>/dev/null | cut -d= -f2)
[ -z "$LIST" ] && LIST="system_server,com.vecentek.decoreapp,com.android.systemui,com.iflytek.autofly.voicecoreservice"
for P in $(echo "$LIST" | tr ',' ' '); do
  PIDS=$(pidof "$P" 2>/dev/null)
  [ -z "$PIDS" ] && continue
  F="/data/system/nativehook-$P.status"
  SOK=$(grep '^ok=' "$F" 2>/dev/null | head -n 1 | cut -d= -f2)
  SPID=$(grep '^pid=' "$F" 2>/dev/null | head -n 1 | cut -d= -f2)
  for PID in $PIDS; do
    # 已经注入且 pid 对得上就跳过：重复注入会再 dlopen 一份 payload，
    # LSPlant 二次 Init 失败后会把状态写成 ok=0，常驻检测就会每 15 秒
    # 反复重注入，越修越乱。
    if [ "$SOK" = "1" ] && [ "$SPID" = "$PID" ]; then
      echo "SKIP $P=$PID already hook"
      continue
    fi
    echo "PID $P=$PID"
    "$D/nhinject" "$PID"
  done
done
echo "START_DONE"
