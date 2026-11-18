#include <android/log.h>
#include <stdio.h>
#include <unistd.h>

__attribute__((constructor))
static void poc_init(void) {
    __android_log_print(ANDROID_LOG_INFO, "NativeHookPOC",
                        "payload loaded pid=%d", getpid());
    FILE *f = fopen("/data/local/tmp/hook-native/poc.loaded", "w");
    if (f) {
        fprintf(f, "pid=%d\n", getpid());
        fclose(f);
    }
}
