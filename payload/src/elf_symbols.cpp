#include "elf_symbols.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>

#define LOG_TAG "NativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct ModuleSearch {
  uintptr_t bias = 0;
  std::string path;
};

int FindModuleCallback(struct dl_phdr_info* info, size_t, void* data) {
  auto* search = static_cast<ModuleSearch*>(data);
  if (!info->dlpi_name || info->dlpi_name[0] == '\0') {
    return 0;
  }
  std::string name(info->dlpi_name);
  if (name.find("libart.so") == std::string::npos) {
    return 0;
  }
  search->bias = info->dlpi_addr;
  search->path = std::move(name);
  return 1;
}

bool InRange(const uint8_t* data, size_t size, size_t offset, size_t need) {
  return offset <= size && need <= size - offset;
}

}  // namespace

ElfSymbolResolver::ElfSymbolResolver(std::string path) : path_(std::move(path)) {}

ElfSymbolResolver::~ElfSymbolResolver() {
  if (data_ && mapped_) {
    munmap(const_cast<uint8_t*>(data_), size_);
  } else if (data_) {
    free(const_cast<uint8_t*>(data_));
  }
}

bool ElfSymbolResolver::Open() {
  ModuleSearch search;
  dl_iterate_phdr(FindModuleCallback, &search);
  load_bias_ = search.bias;
  if (!search.path.empty() && search.path != path_) {
    LOGI("libart loaded as %s (requested %s)", search.path.c_str(), path_.c_str());
  }

  int fd = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    LOGI("open %s failed: %s", path_.c_str(), strerror(errno));
    return false;
  }

  struct stat st {};
  if (fstat(fd, &st) != 0 || st.st_size <= 0) {
    LOGI("fstat %s failed", path_.c_str());
    close(fd);
    return false;
  }
  size_ = static_cast<size_t>(st.st_size);

  void* mapped = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (mapped != MAP_FAILED) {
    data_ = static_cast<const uint8_t*>(mapped);
    mapped_ = true;
  } else {
    // mmap 失败时退化为读入内存。
    auto* buf = static_cast<uint8_t*>(malloc(size_));
    if (!buf) {
      LOGI("malloc %zu failed", size_);
      return false;
    }
    int fd2 = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd2 < 0) {
      free(buf);
      return false;
    }
    size_t got = 0;
    while (got < size_) {
      ssize_t n = read(fd2, buf + got, size_ - got);
      if (n <= 0) break;
      got += static_cast<size_t>(n);
    }
    close(fd2);
    if (got != size_) {
      free(buf);
      return false;
    }
    data_ = buf;
    mapped_ = false;
  }

  if (size_ < sizeof(Elf64_Ehdr)) {
    return false;
  }
  const auto* eh = reinterpret_cast<const Elf64_Ehdr*>(data_);
  if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 ||
      eh->e_ident[EI_CLASS] != ELFCLASS64) {
    LOGI("%s is not a 64-bit ELF", path_.c_str());
    return false;
  }

  if (search.bias == 0) {
    // 极少数情况下 dl_iterate_phdr 没匹配到，用第一个 PT_LOAD 的 vaddr 反推。
    if (eh->e_phoff && InRange(data_, size_, eh->e_phoff,
                               eh->e_phnum * sizeof(Elf64_Phdr))) {
      const auto* ph = reinterpret_cast<const Elf64_Phdr*>(data_ + eh->e_phoff);
      for (int i = 0; i < eh->e_phnum; ++i) {
        if (ph[i].p_type == PT_LOAD) {
          load_bias_ = 0;  // 无法从文件反推运行时基址，保留 0 并提示
          break;
        }
      }
    }
    LOGI("warning: libart load bias not found, symbol addresses may be wrong");
  }

  LOGI("opened %s (%zu bytes), load bias 0x%zx", path_.c_str(), size_, load_bias_);
  return true;
}

void* ElfSymbolResolver::Resolve(std::string_view name) const {
  return ResolveImpl(name, false);
}

void* ElfSymbolResolver::ResolvePrefix(std::string_view prefix) const {
  return ResolveImpl(prefix, true);
}

void* ElfSymbolResolver::ResolveImpl(std::string_view needle, bool prefix) const {
  if (!data_ || !size_) {
    return nullptr;
  }
  const auto* eh = reinterpret_cast<const Elf64_Ehdr*>(data_);
  if (!eh->e_shoff || eh->e_shentsize != sizeof(Elf64_Shdr) ||
      !InRange(data_, size_, eh->e_shoff, eh->e_shnum * sizeof(Elf64_Shdr))) {
    return nullptr;
  }
  const auto* sections = reinterpret_cast<const Elf64_Shdr*>(data_ + eh->e_shoff);

  // 先扫 .symtab（更完整），再扫 .dynsym。
  for (int pass = 0; pass < 2; ++pass) {
    const uint32_t wanted = pass == 0 ? SHT_SYMTAB : SHT_DYNSYM;
    for (int i = 0; i < eh->e_shnum; ++i) {
      const Elf64_Shdr& sh = sections[i];
      if (sh.sh_type != wanted || sh.sh_entsize != sizeof(Elf64_Sym)) {
        continue;
      }
      if (!InRange(data_, size_, sh.sh_offset, sh.sh_size) ||
          sh.sh_link >= static_cast<uint32_t>(eh->e_shnum)) {
        continue;
      }
      const Elf64_Shdr& str_sh = sections[sh.sh_link];
      if (!InRange(data_, size_, str_sh.sh_offset, str_sh.sh_size)) {
        continue;
      }
      const auto* syms = reinterpret_cast<const Elf64_Sym*>(data_ + sh.sh_offset);
      const char* strtab = reinterpret_cast<const char*>(data_ + str_sh.sh_offset);
      const size_t str_size = str_sh.sh_size;
      const size_t count = sh.sh_size / sizeof(Elf64_Sym);

      for (size_t j = 0; j < count; ++j) {
        const Elf64_Sym& sym = syms[j];
        if (sym.st_shndx == SHN_UNDEF || sym.st_name >= str_size) {
          continue;
        }
        const char* sym_name = strtab + sym.st_name;
        bool match;
        if (prefix) {
          match = strncmp(sym_name, needle.data(), needle.size()) == 0;
        } else {
          match = needle.size() == strlen(sym_name) &&
                  memcmp(sym_name, needle.data(), needle.size()) == 0;
        }
        if (match) {
          return reinterpret_cast<void*>(load_bias_ + sym.st_value);
        }
      }
    }
  }
  return nullptr;
}