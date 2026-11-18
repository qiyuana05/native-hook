// nhinject：arm64 ptrace 注入器
//
// 用法：
//   nhinject <pid> [lib1.so lib2.so ...]
//   nhinject <pid>                       # 默认加载 liblsplant.so + libnhpayload.so
//
// 原理：ptrace attach 目标进程，在目标栈上写入路径，修改寄存器调用
// libdl.so 的 dlopen()，返回地址指向 libc.so 里的 brk #0 指令。
// dlopen 返回后触发 SIGTRAP，注入器恢复原寄存器并继续。
//
// 依赖：root（DM01）+ SELinux Permissive；目标进程为 64 位。

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

#define RTLD_NOW_LOCAL 2
#define RTLD_GLOBAL_LOCAL 0x100

struct arm64_regs {
  uint64_t regs[31];
  uint64_t sp;
  uint64_t pc;
  uint64_t pstate;
};

struct mapped_file {
  const uint8_t* data;
  size_t size;
  bool mapped;
};

static int map_file(const char* path, struct mapped_file* out) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    fprintf(stderr, "open %s: %s\n", path, strerror(errno));
    return -1;
  }
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_size <= 0) {
    fprintf(stderr, "fstat %s failed\n", path);
    close(fd);
    return -1;
  }
  void* map = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (map == MAP_FAILED) {
    fprintf(stderr, "mmap %s: %s\n", path, strerror(errno));
    return -1;
  }
  out->data = (const uint8_t*)map;
  out->size = (size_t)st.st_size;
  out->mapped = true;
  return 0;
}

static void unmap_file(struct mapped_file* file) {
  if (file->mapped) munmap((void*)file->data, file->size);
}

static int elf_find_symbol(const char* path, const char* want, uint64_t* value) {
  struct mapped_file file = {0};
  if (map_file(path, &file) != 0) return -1;
  int rc = -1;
  const Elf64_Ehdr* eh = (const Elf64_Ehdr*)file.data;
  if (file.size < sizeof(*eh) || memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 ||
      eh->e_ident[EI_CLASS] != ELFCLASS64 || eh->e_shoff == 0 ||
      eh->e_shentsize != sizeof(Elf64_Shdr) ||
      eh->e_shoff + (uint64_t)eh->e_shnum * sizeof(Elf64_Shdr) > file.size) {
    fprintf(stderr, "%s: bad ELF\n", path);
    goto out;
  }
  const Elf64_Shdr* sections = (const Elf64_Shdr*)(file.data + eh->e_shoff);
  for (int pass = 0; pass < 2 && rc != 0; ++pass) {
    uint32_t wanted = pass == 0 ? SHT_DYNSYM : SHT_SYMTAB;
    for (int i = 0; i < eh->e_shnum; ++i) {
      const Elf64_Shdr* sh = &sections[i];
      if (sh->sh_type != wanted || sh->sh_entsize != sizeof(Elf64_Sym) ||
          sh->sh_link >= (uint32_t)eh->e_shnum ||
          sh->sh_offset + sh->sh_size > file.size) {
        continue;
      }
      const Elf64_Shdr* str_sh = &sections[sh->sh_link];
      if (str_sh->sh_offset + str_sh->sh_size > file.size) continue;
      const Elf64_Sym* syms = (const Elf64_Sym*)(file.data + sh->sh_offset);
      const char* strtab = (const char*)(file.data + str_sh->sh_offset);
      const size_t count = sh->sh_size / sizeof(Elf64_Sym);
      for (size_t j = 0; j < count; ++j) {
        const Elf64_Sym* sym = &syms[j];
        if (sym->st_shndx == SHN_UNDEF || sym->st_name >= str_sh->sh_size) {
          continue;
        }
        if (strcmp(strtab + sym->st_name, want) == 0) {
          *value = sym->st_value;
          rc = 0;
          break;
        }
      }
      if (rc == 0) break;
    }
  }
out:
  unmap_file(&file);
  return rc;
}

// 在 libc 的可执行段里找第一条 brk #imm（0xD420xxxx）。
static int elf_find_brk(const char* path, uint64_t* value) {
  struct mapped_file file = {0};
  if (map_file(path, &file) != 0) return -1;
  int rc = -1;
  const Elf64_Ehdr* eh = (const Elf64_Ehdr*)file.data;
  if (file.size < sizeof(*eh) || memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 ||
      eh->e_ident[EI_CLASS] != ELFCLASS64 || eh->e_phoff == 0 ||
      eh->e_phentsize != sizeof(Elf64_Phdr) ||
      eh->e_phoff + (uint64_t)eh->e_phnum * sizeof(Elf64_Phdr) > file.size) {
    goto out;
  }
  const Elf64_Phdr* phdrs = (const Elf64_Phdr*)(file.data + eh->e_phoff);
  for (int i = 0; i < eh->e_phnum && rc != 0; ++i) {
    const Elf64_Phdr* ph = &phdrs[i];
    if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X) || ph->p_filesz < 4) {
      continue;
    }
    if (ph->p_offset + ph->p_filesz > file.size) continue;
    const uint8_t* seg = file.data + ph->p_offset;
    for (uint64_t off = 0; off + 4 <= ph->p_filesz; off += 4) {
      uint32_t insn;
      memcpy(&insn, seg + off, sizeof(insn));
      if ((insn & 0xFFFF0000U) == 0xD4200000U) {
        *value = ph->p_vaddr + off;
        rc = 0;
        break;
      }
    }
  }
out:
  unmap_file(&file);
  return rc;
}

// 在 libc 的 .text 里找一条 udf #0（0x00000000），用作不改 x0 的返回断点。
// 地址落在 libc 映射内，linker 会按 libc 的 namespace 解析 caller。
static int elf_find_udf(const char* path, uint64_t min_vaddr, uint64_t* value) {
  struct mapped_file file = {0};
  if (map_file(path, &file) != 0) return -1;
  int rc = -1;
  const Elf64_Ehdr* eh = (const Elf64_Ehdr*)file.data;
  if (file.size < sizeof(*eh) || memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 ||
      eh->e_ident[EI_CLASS] != ELFCLASS64 || eh->e_phoff == 0 ||
      eh->e_phentsize != sizeof(Elf64_Phdr) ||
      eh->e_phoff + (uint64_t)eh->e_phnum * sizeof(Elf64_Phdr) > file.size) {
    goto out;
  }
  const Elf64_Phdr* phdrs = (const Elf64_Phdr*)(file.data + eh->e_phoff);
  for (int i = 0; i < eh->e_phnum && rc != 0; ++i) {
    const Elf64_Phdr* ph = &phdrs[i];
    if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X) || ph->p_filesz < 4) {
      continue;
    }
    if (ph->p_offset + ph->p_filesz > file.size) continue;
    const uint8_t* seg = file.data + ph->p_offset;
    for (uint64_t off = 0; off + 4 <= ph->p_filesz; off += 4) {
      uint32_t insn;
      memcpy(&insn, seg + off, sizeof(insn));
      if (insn == 0 && ph->p_vaddr + off >= min_vaddr) {
        *value = ph->p_vaddr + off;
        rc = 0;
        break;
      }
    }
  }
out:
  unmap_file(&file);
  return rc;
}

static uintptr_t find_module_base(pid_t pid, const char* soname) {
  char path[64];
  snprintf(path, sizeof(path), "/proc/%d/maps", pid);
  FILE* fp = fopen(path, "r");
  if (!fp) {
    fprintf(stderr, "open %s: %s\n", path, strerror(errno));
    return 0;
  }
  char line[512];
  uintptr_t base = 0;
  while (fgets(line, sizeof(line), fp)) {
    unsigned long start = 0;
    char perms[8] = {0};
    char module[256] = {0};
    if (sscanf(line, "%lx-%*lx %7s %*s %*s %*s %255s", &start, perms,
               module) != 3) {
      continue;
    }
    if (strstr(module, soname) == NULL) continue;
    if (base == 0 || start < base) base = start;
  }
  fclose(fp);
  return base;
}

// 目标进程里已经映射过这个 so 就不要再 dlopen。
// 部署时 /data/local/tmp 下的 so 会被替换成新 inode，如果此时再点“开启 Hook”，
// dlopen 会把它当成新库再加载一份 -> LSPlant 二次 Init -> system_server 软重启。
// /proc/<pid>/maps 里旧映射可能带 " (deleted)"，按 basename 匹配即可。
static int is_module_mapped(pid_t pid, const char* lib_path) {
  const char* base = strrchr(lib_path, '/');
  base = base ? base + 1 : lib_path;
  char path[64];
  snprintf(path, sizeof(path), "/proc/%d/maps", pid);
  FILE* fp = fopen(path, "r");
  if (!fp) return 0;
  char line[512];
  int found = 0;
  while (fgets(line, sizeof(line), fp)) {
    if (strstr(line, base) != NULL) {
      found = 1;
      break;
    }
  }
  fclose(fp);
  return found;
}

static int find_stack(pid_t pid, uintptr_t* start, uintptr_t* end) {
  char path[64];
  snprintf(path, sizeof(path), "/proc/%d/maps", pid);
  FILE* fp = fopen(path, "r");
  if (!fp) return -1;
  char line[512];
  int found = 0;
  while (fgets(line, sizeof(line), fp)) {
    unsigned long lo = 0, hi = 0;
    char module[256] = {0};
    if (sscanf(line, "%lx-%lx %*s %*s %*s %*s %255s", &lo, &hi, module) == 3 &&
        strcmp(module, "[stack]") == 0) {
      *start = lo;
      *end = hi;
      found = 1;
      break;
    }
  }
  fclose(fp);
  return found ? 0 : -1;
}

static int get_regs(pid_t pid, struct arm64_regs* regs) {
  struct iovec io = {regs, sizeof(*regs)};
  return (int)ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &io);
}

static int set_regs(pid_t pid, const struct arm64_regs* regs) {
  struct iovec io = {(void*)regs, sizeof(*regs)};
  return (int)ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &io);
}

static int write_mem(pid_t pid, uintptr_t addr, const void* data, size_t len) {
  const uint8_t* src = (const uint8_t*)data;
  for (size_t i = 0; i < len; i += 8) {
    size_t n = len - i < 8 ? len - i : 8;
    errno = 0;
    long word = ptrace(PTRACE_PEEKDATA, pid, (void*)(addr + i), NULL);
    if (errno != 0) return -1;
    uint8_t buf[8];
    memcpy(buf, &word, sizeof(buf));
    memcpy(buf, src + i, n);
    memcpy(&word, buf, sizeof(word));
    if (ptrace(PTRACE_POKEDATA, pid, (void*)(addr + i), (void*)word) < 0) {
      return -1;
    }
  }
  return 0;
}

static int remote_dlopen(pid_t pid, const struct arm64_regs* saved,
                         uintptr_t dlopen_addr, uintptr_t brk_addr,
                         uintptr_t scratch, const char* lib_path,
                         int expected_sig, uintptr_t* handle) {
  if (write_mem(pid, scratch, lib_path, strlen(lib_path) + 1) < 0) {
    fprintf(stderr, "write path to 0x%zx failed: %s\n", (size_t)scratch,
            strerror(errno));
    return -1;
  }
  struct arm64_regs regs = *saved;
  regs.regs[0] = scratch;
  regs.regs[1] = RTLD_NOW_LOCAL | RTLD_GLOBAL_LOCAL;
  regs.pc = dlopen_addr;
  regs.regs[30] = brk_addr;

  if (set_regs(pid, &regs) < 0) {
    fprintf(stderr, "set regs failed: %s\n", strerror(errno));
    return -1;
  }
  if (ptrace(PTRACE_CONT, pid, NULL, NULL) < 0) {
    fprintf(stderr, "PTRACE_CONT failed: %s\n", strerror(errno));
    return -1;
  }

  int status = 0;
  if (waitpid(pid, &status, 0) < 0) {
    fprintf(stderr, "waitpid failed: %s\n", strerror(errno));
    return -1;
  }
  if (!WIFSTOPPED(status)) {
    fprintf(stderr, "%s: process exited during dlopen\n", lib_path);
    return -2;
  }
  if (WSTOPSIG(status) != expected_sig) {
    fprintf(stderr, "%s: unexpected signal %d during dlopen\n", lib_path,
            WSTOPSIG(status));
    set_regs(pid, saved);
    return -2;
  }

  struct arm64_regs after = {0};
  if (get_regs(pid, &after) < 0) return -1;
  *handle = after.regs[0];
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 2) {
    fprintf(stderr,
            "usage: %s <pid> [lib1.so lib2.so ...]\n"
            "       default: /data/local/tmp/hook-native/liblsplant.so "
            "/data/local/tmp/hook-native/libnhpayload.so\n",
            argv[0]);
    return 2;
  }
  pid_t pid = (pid_t)atoi(argv[1]);
  if (pid <= 0) {
    fprintf(stderr, "invalid pid: %s\n", argv[1]);
    return 2;
  }

  const char* default_libs[] = {
      "/data/local/tmp/hook-native/liblsplant.so",
      "/data/local/tmp/hook-native/libnhpayload.so",
  };
  const char** libs = default_libs;
  int lib_count = 2;
  if (argc > 2) {
    libs = (const char**)&argv[2];
    lib_count = argc - 2;
  }

  // 已经注入过的进程直接跳过，避免二次 dlopen / 二次 LSPlant Init。
  const char* pending[8];
  int pending_count = 0;
  for (int i = 0; i < lib_count && i < 8; ++i) {
    if (is_module_mapped(pid, libs[i])) {
      fprintf(stderr, "[skip] %s already mapped in pid %d\n", libs[i], pid);
      continue;
    }
    pending[pending_count++] = libs[i];
  }
  if (pending_count == 0) {
    fprintf(stderr, "pid %d already injected, nothing to do\n", pid);
    return 0;
  }
  libs = pending;
  lib_count = pending_count;

  uintptr_t dl_base = find_module_base(pid, "libdl.so");
  uintptr_t libc_base = find_module_base(pid, "libc.so");
  uintptr_t stack_start = 0, stack_end = 0;
  if (dl_base == 0 || libc_base == 0 ||
      find_stack(pid, &stack_start, &stack_end) != 0) {
    fprintf(stderr, "failed to locate libdl/libc/stack for pid %d\n", pid);
    return 1;
  }

  uint64_t dlopen_off = 0, brk_off = 0;
  const int dlopen_rc =
      elf_find_symbol("/system/lib64/libdl.so", "dlopen", &dlopen_off);
  fprintf(stderr, "dlopen: rc=%d off=0x%zx\n", dlopen_rc,
          (size_t)dlopen_off);
  if (dlopen_rc != 0) {
    fprintf(stderr, "dlopen symbol not found in /system/lib64/libdl.so\n");
    return 1;
  }

  int expected_sig = SIGTRAP;
  uintptr_t trap_base = 0;
  const int brk_rc = elf_find_brk("/system/lib64/libc.so", &brk_off);
  fprintf(stderr, "brk(libc): rc=%d off=0x%zx\n", brk_rc, (size_t)brk_off);
  if (brk_rc == 0) {
    trap_base = libc_base;
  } else {
    // libc 没有 brk 时用 libc 的 udf #0：不改 x0，且 caller 落在 default namespace。
    const int udf_rc = elf_find_udf("/system/lib64/libc.so", 0x10000, &brk_off);
    fprintf(stderr, "udf(libc): rc=%d off=0x%zx\n", udf_rc, (size_t)brk_off);
    if (udf_rc == 0) {
      trap_base = libc_base;
      expected_sig = SIGILL;
    } else {
      const int linker_rc = elf_find_brk("/system/bin/linker64", &brk_off);
      fprintf(stderr, "brk(linker64): rc=%d off=0x%zx\n", linker_rc,
              (size_t)brk_off);
      if (linker_rc == 0) {
        trap_base = find_module_base(pid, "linker64");
        if (trap_base == 0) {
          fprintf(stderr, "linker64 base not found\n");
          return 1;
        }
      } else {
        const int abort_rc =
            elf_find_symbol("/system/lib64/libc.so", "abort", &brk_off);
        fprintf(stderr, "abort fallback: rc=%d off=0x%zx\n", abort_rc,
                (size_t)brk_off);
        if (abort_rc != 0) {
          fprintf(stderr, "no brk/udf/abort found for return breakpoint\n");
          return 1;
        }
        // abort 会改写 x0，handle 不可信；仅作为最后手段。
        trap_base = libc_base;
        expected_sig = SIGABRT;
      }
    }
  }

  uintptr_t dlopen_addr = dl_base + dlopen_off;
  uintptr_t brk_addr = trap_base + brk_off;
  // 栈底附近远离当前 SP，dlopen 的栈增长不会覆盖。
  uintptr_t scratch = (stack_start + 0x1000) & ~(uintptr_t)0xF;
  if (scratch + 512 > stack_end) {
    fprintf(stderr, "no room on stack\n");
    return 1;
  }

  fprintf(stderr,
          "pid=%d libdl=0x%zx dlopen=0x%zx libc=0x%zx return=0x%zx sig=%d "
          "stack=0x%zx\n",
          pid, (size_t)dl_base, (size_t)dlopen_addr, (size_t)libc_base,
          (size_t)brk_addr, expected_sig, (size_t)scratch);

  if (ptrace(PTRACE_ATTACH, pid, NULL, NULL) < 0) {
    fprintf(stderr, "PTRACE_ATTACH %d: %s\n", pid, strerror(errno));
    return 1;
  }
  int status = 0;
  if (waitpid(pid, &status, 0) < 0 || !WIFSTOPPED(status)) {
    fprintf(stderr, "attach wait failed\n");
    ptrace(PTRACE_DETACH, pid, NULL, NULL);
    return 1;
  }

  struct arm64_regs saved = {0};
  if (get_regs(pid, &saved) < 0) {
    fprintf(stderr, "get regs failed: %s\n", strerror(errno));
    ptrace(PTRACE_DETACH, pid, NULL, NULL);
    return 1;
  }

  int failures = 0;
  for (int i = 0; i < lib_count; ++i) {
    uintptr_t handle = 0;
    int rc = remote_dlopen(pid, &saved, dlopen_addr, brk_addr, scratch, libs[i],
                           expected_sig, &handle);
    if (rc == 0 && handle != 0) {
      fprintf(stderr, "[ok] %s -> handle 0x%zx\n", libs[i], (size_t)handle);
    } else {
      fprintf(stderr, "[fail] %s rc=%d handle=0x%zx\n", libs[i], rc,
              (size_t)handle);
      ++failures;
    }
    // 每次调用后都把寄存器恢复到 attach 时的状态，下一次从原位置重新出发。
    if (set_regs(pid, &saved) < 0) {
      fprintf(stderr, "restore regs failed: %s\n", strerror(errno));
      ++failures;
      break;
    }
  }

  if (ptrace(PTRACE_DETACH, pid, NULL, NULL) < 0) {
    fprintf(stderr, "PTRACE_DETACH failed: %s\n", strerror(errno));
    return 1;
  }
  return failures == 0 ? 0 : 1;
}