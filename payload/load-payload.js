// 用 Frida 只作为临时加载器，验证 native payload。
// 两个库必须按顺序加载：payload 依赖 liblsplant.so 的 soname。
console.log('[nh] loading liblsplant.so');
try {
  Module.load('/data/local/tmp/hook-native/liblsplant.so');
  console.log('[nh] liblsplant loaded');
} catch (e) {
  console.log('[nh] liblsplant failed: ' + e);
}

console.log('[nh] loading libnhpayload.so');
try {
  Module.load('/data/local/tmp/hook-native/libnhpayload.so');
  console.log('[nh] payload loaded');
} catch (e) {
  console.log('[nh] payload failed: ' + e);
}