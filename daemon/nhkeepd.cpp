// nhkeepd：root 侧常驻巡检守护进程。
//
// 背景：原来的巡检（每 15 秒 status.sh，异常就 start.sh）跑在 APK 的
// KeepAliveService 里。App 一旦被 force-stop（stopped=true），进程没了、
// 线程没了，注入就再也没人补——2026-11-05 17:02 就是这样丢的。
//
// 这个进程像 rt_httpd 一样由 ensure_services.sh 用 setsid 拉起，只要不重启
// 就一直活着，与 App 是否在运行无关。
//
// 判定依据用 /proc/<pid>/maps 里有没有 libnhpayload.so，而不是只看 status
// 文件：status 可能是上一轮甚至上一代进程留下的（pid 对不上照样是 ok=1）。
//
// 用法：nhkeepd [interval_sec]

#define LOG_TAG "NHKeepd"

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <netinet/in.h>
#include <arpa/inet.h>

namespace {

constexpr const char* kCfg = "/data/system/uninstall-hook-native.cfg";
constexpr const char* kInject = "/data/local/tmp/hook-native/nhinject";
constexpr const char* kEnsure = "/data/local/tmp/hook-native/ensure_services.sh";
constexpr const char* kHb = "/data/local/tmp/hook-native/nhkeepd.hb";
constexpr const char* kLog = "/data/local/tmp/hook-native/nhkeepd.log";
constexpr int kMaxPkgs = 16;
constexpr int kMaxName = 128;
constexpr long kLogMax = 512 * 1024;

volatile sig_atomic_t g_stop = 0;

void OnSignal(int) { g_stop = 1; }

long Now() { return static_cast<long>(time(nullptr)); }

void Logf(const char* fmt, ...) {
  char body[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(body, sizeof(body), fmt, ap);
  va_end(ap);

  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", body);

  struct stat st;
  if (stat(kLog, &st) == 0 && st.st_size > kLogMax) {
    char bak[256];
    snprintf(bak, sizeof(bak), "%s.1", kLog);
    rename(kLog, bak);
  }
  FILE* fp = fopen(kLog, "a");
  if (!fp) return;
  struct tm tm_now;
  time_t t = time(nullptr);
  localtime_r(&t, &tm_now);
  fprintf(fp, "%02d-%02d %02d:%02d:%02d %s\n", tm_now.tm_mon + 1, tm_now.tm_mday,
          tm_now.tm_hour, tm_now.tm_min, tm_now.tm_sec, body);
  fclose(fp);
}

void WriteHeartbeat() {
  // root 的 umask 通常是 077，fopen 会建成 600，App 读不到。
  // 心跳是给人/给 App 看的，显式 0644。
  int fd = open(kHb, O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd < 0) return;
  fchmod(fd, 0644);
  char buf[32];
  int n = snprintf(buf, sizeof(buf), "%ld\n", Now());
  if (n > 0) {
    ssize_t ignored = write(fd, buf, static_cast<size_t>(n));
    (void)ignored;
  }
  close(fd);
}

// 从 cfg 读 inject= 列表，逗号分隔。
int ReadInjectList(char out[][kMaxName], int max) {
  FILE* fp = fopen(kCfg, "r");
  if (!fp) return 0;
  char line[1024];
  int n = 0;
  while (fgets(line, sizeof(line), fp)) {
    if (strncmp(line, "inject=", 7) != 0) continue;
    char* p = line + 7;
    char* end = p + strlen(p);
    while (end > p && (end[-1] == '\n' || end[-1] == '\r')) --end;
    *end = '\0';
    while (p && *p && n < max) {
      char* comma = strchr(p, ',');
      if (comma) *comma = '\0';
      if (*p) {
        strncpy(out[n], p, kMaxName - 1);
        out[n][kMaxName - 1] = '\0';
        ++n;
      }
      p = comma ? comma + 1 : nullptr;
    }
    break;
  }
  fclose(fp);
  return n;
}

bool CmdlineIs(const char* path, const char* pkg) {
  char buf[256];
  int fd = open(path, O_RDONLY);
  if (fd < 0) return false;
  ssize_t got = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  if (got <= 0) return false;
  buf[got] = '\0';
  // 进程名以 args 分隔，只比第一个字段。
  return strcmp(buf, pkg) == 0;
}

int FindPids(const char* pkg, int* out, int max) {
  DIR* d = opendir("/proc");
  if (!d) return 0;
  int n = 0;
  struct dirent* e;
  while ((e = readdir(d)) != nullptr && n < max) {
    if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
    char path[256];
    snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
    if (!CmdlineIs(path, pkg)) continue;
    out[n++] = atoi(e->d_name);
  }
  closedir(d);
  return n;
}

bool HasPayload(int pid) {
  char path[128];
  snprintf(path, sizeof(path), "/proc/%d/maps", pid);
  FILE* fp = fopen(path, "r");
  if (!fp) return false;
  char line[512];
  bool found = false;
  while (fgets(line, sizeof(line), fp)) {
    if (strstr(line, "libnhpayload.so")) {
      found = true;
      break;
    }
  }
  fclose(fp);
  return found;
}

bool PortOpen(int port) {
  int fd = socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) return false;
  struct timeval tv = {0, 300000};
  setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(static_cast<uint16_t>(port));
  addr.sin_addr.s_addr = inet_addr("127.0.0.1");
  bool ok = connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0;
  close(fd);
  return ok;
}

// 执行 nhinject，最多等 8 秒；超时就 kill，避免看门狗被拖死。
bool Inject(int pid) {
  pid_t child = fork();
  if (child < 0) return false;
  if (child == 0) {
    int devnull = open("/dev/null", O_WRONLY);
    if (devnull >= 0) {
      dup2(devnull, STDOUT_FILENO);
      dup2(devnull, STDERR_FILENO);
      close(devnull);
    }
    char pidstr[16];
    snprintf(pidstr, sizeof(pidstr), "%d", pid);
    execl(kInject, "nhinject", pidstr, static_cast<char*>(nullptr));
    _exit(127);
  }
  for (int i = 0; i < 80; ++i) {
    int status = 0;
    pid_t r = waitpid(child, &status, WNOHANG);
    if (r == child) {
      return WIFEXITED(status) && WEXITSTATUS(status) == 0;
    }
    usleep(100 * 1000);
  }
  kill(child, SIGKILL);
  waitpid(child, nullptr, 0);
  return false;
}

void EnsureChannels() {
  static long last_try = 0;
  if (PortOpen(4455) && PortOpen(4466)) return;
  if (Now() - last_try < 30) return;
  last_try = Now();
  Logf("channels down (4455=%d 4466=%d), run ensure_services", PortOpen(4455),
       PortOpen(4466));
  pid_t child = fork();
  if (child == 0) {
    execl("/system/bin/sh", "sh", kEnsure, static_cast<char*>(nullptr));
    _exit(127);
  }
  waitpid(child, nullptr, 0);
}

void InjectPass() {
  char pkgs[kMaxPkgs][kMaxName];
  int n = ReadInjectList(pkgs, kMaxPkgs);
  if (n == 0) {
    Logf("inject list empty");
    return;
  }
  for (int i = 0; i < n; ++i) {
    int pids[8];
    int count = FindPids(pkgs[i], pids, 8);
    if (count == 0) continue;  // 进程没起来，下一轮再看
    for (int j = 0; j < count; ++j) {
      int pid = pids[j];
      if (HasPayload(pid)) continue;
      Logf("%s pid=%d missing payload -> inject", pkgs[i], pid);
      bool ok = Inject(pid);
      if (ok && HasPayload(pid)) {
        Logf("%s pid=%d injected ok", pkgs[i], pid);
      } else {
        Logf("%s pid=%d inject failed", pkgs[i], pid);
      }
    }
  }
}

// 用「扫 /proc 看有没有同名进程」判重，不用 flock。
// flock 在进程被 kill -9 或文件被替换后偶尔会留下拿不到的锁，反而起不来。
bool AnotherInstanceRunning() {
  DIR* d = opendir("/proc");
  if (!d) return false;
  const int self = getpid();
  bool found = false;
  struct dirent* e;
  while ((e = readdir(d)) != nullptr) {
    if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
    int pid = atoi(e->d_name);
    if (pid == self) continue;
    // 只看 comm（可执行文件名），不看 cmdline：调用它的 sh 命令行里也可能带 nhkeepd。
    char path[256];
    snprintf(path, sizeof(path), "/proc/%d/comm", pid);
    char buf[64];
    int fd = open(path, O_RDONLY);
    if (fd < 0) continue;
    ssize_t got = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (got <= 0) continue;
    buf[got] = '\0';
    if (strncmp(buf, "nhkeepd", 7) == 0) {
      found = true;
      break;
    }
  }
  closedir(d);
  return found;
}

}  // namespace

int main(int argc, char** argv) {
  int interval = argc > 1 ? atoi(argv[1]) : 15;
  if (interval < 5) interval = 5;

  signal(SIGTERM, OnSignal);
  signal(SIGINT, OnSignal);
  signal(SIGCHLD, SIG_DFL);

  if (AnotherInstanceRunning()) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "another instance running");
    return 0;
  }

  Logf("started interval=%ds pid=%d", interval, getpid());
  while (!g_stop) {
    WriteHeartbeat();
    EnsureChannels();
    InjectPass();
    for (int i = 0; i < interval * 10 && !g_stop; ++i) {
      usleep(100 * 1000);
    }
  }
  Logf("stopped");
  return 0;
}
