#!/system/bin/sh
D=/data/local/tmp/hook-native
CFG=/data/system/uninstall-hook-native.cfg

# 注意：所有常驻进程必须后台化，且统一用 `( setsid ... ) &` 包一层子 shell。
#
# 早期版本写成 `setsid xxx </dev/null >/dev/null 2>&1`（没有 &），
# sh 会等子进程退出后才继续 —— nhkeepd / rt_shell 都是常驻不退出，
# 于是脚本永久卡在那一行，末尾调用记录仪 boot.sh 的代码根本执行不到。
# 实测开机 4 分钟仍卡在 `setsid nhkeepd 15`，evsdvr 一直起不来。
#
# 只加 & 还不够：命令跨多行续行时，实测子进程仍会挂在脚本名下。
# 包一层子 shell 再整体 & 才彻底脱离。

# 启动并脱离，不阻塞本脚本
bg() { ( setsid "$@" </dev/null >/dev/null 2>&1 ) </dev/null >/dev/null 2>&1 & }

if [ -x "$D/rt_shell" ]; then
  if ! ps -A | grep -F "$D/rt_shell" | grep -v grep >/dev/null 2>&1; then
    bg "$D/rt_shell" 4455
  fi
fi
if [ -x "$D/rt_httpd" ]; then
  if ! ps -A | grep -F "$D/rt_httpd" | grep -v grep >/dev/null 2>&1; then
    bg "$D/rt_httpd" 4466
  fi
fi
# root 侧巡检守护：与 App 的 KeepAliveService 互补。
# App 被 force-stop 后进程内线程全没，只有这个还能补注入。
if [ -x "$D/nhkeepd" ]; then
  if ! ps -A | grep -F "$D/nhkeepd" | grep -v grep >/dev/null 2>&1; then
    bg "$D/nhkeepd" 15
  fi
fi
# 常驻会话日志：只留 hook / 投屏相关 tag，滚动保留最近两份。
# 闪退后 logcat 主缓冲很快被系统日志挤掉，这份文件还能回看。
if ! ps -A | grep -F "$D/session.log" | grep -v grep >/dev/null 2>&1; then
  (
    setsid logcat -v time -s NHookNative UCarAdapter UCarConnectProxy \
      UCar_ShareObservers CarLinkMainActivity AndroidRuntime DEBUG \
      -f "$D/session.log" -r 2048 -n 2 </dev/null >/dev/null 2>&1
  ) </dev/null >/dev/null 2>&1 &
fi

# ---- 后摄记录仪 ----
# 受配置开关 evs_dvr 控制（界面上「后摄记录仪」，默认关）。
# 它跑在独立的 root 进程里，自己注入 evsserver，失败不影响倒车影像。
# boot.sh 幂等（pgrep 到 run.sh 就直接退出），被反复调用也没关系。
if [ "$(grep '^evs_dvr=' "$CFG" 2>/dev/null | cut -d= -f2)" = "1" ]; then
  if [ -x "$D/evsdvr/boot.sh" ]; then
    sh "$D/evsdvr/boot.sh"
  fi
else
  # 关掉了：把正在跑的录制裁掉，回收空间
  if pgrep -f "$D/evsdvr/run.sh" >/dev/null 2>&1; then
    pkill -f "$D/evsdvr/run.sh" 2>/dev/null
    pkill -x evsdvr 2>/dev/null
  fi
fi

echo "SERVICES_DONE"
