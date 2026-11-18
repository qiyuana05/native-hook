#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

// 从磁盘上的 libart.so 解析符号地址，同时支持 .dynsym 和 .symtab。
// LSPlant 需要它来定位 ART 内部函数。
class ElfSymbolResolver {
 public:
  explicit ElfSymbolResolver(std::string path);
  ~ElfSymbolResolver();

  ElfSymbolResolver(const ElfSymbolResolver&) = delete;
  ElfSymbolResolver& operator=(const ElfSymbolResolver&) = delete;

  bool Open();

  void* Resolve(std::string_view name) const;
  void* ResolvePrefix(std::string_view prefix) const;

  const std::string& path() const { return path_; }
  bool valid() const { return data_ != nullptr; }

 private:
  void* ResolveImpl(std::string_view needle, bool prefix) const;

  std::string path_;
  const uint8_t* data_ = nullptr;
  size_t size_ = 0;
  uintptr_t load_bias_ = 0;
  bool mapped_ = false;
};