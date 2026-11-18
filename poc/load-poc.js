// 由现有 frida-inject 加载，验证 system_server 能不能从 /data 直接 dlopen
var m = Module.load('/data/local/tmp/hook-native/libpoc.so');
console.log('[poc] loaded ' + m.path);
