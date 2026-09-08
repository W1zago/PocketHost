cmake_minimum_required(VERSION 3.22.1)

project("pockethost_jvm")

# In-process JVM launcher, ported from MojoLauncher/PojavLauncher
# app_pojavlauncher/src/main/jni/jre_launcher/jre_launcher.c
# Simplified for headless Minecraft *servers*:
#  - no graphics/LWJGL hooks, no bytehook, no mojoexec dependency
#  - persistent VM + setjmp/longjmp exit trap so System.exit()
#    from the server does NOT kill the whole app process
add_library(pockethost_jvm SHARED
    jvm_launcher.c
)

find_library(log-lib log)
target_link_libraries(pockethost_jvm ${log-lib} dl)

# Android 15+ з 16KB-сторінками відмовляється вантажити .so з 4KB-вирівнюванням
# (UnsatisfiedLinkError -> in-process старт мертвий). Прапорець безпечний і на
# 4KB-ядрах (лише вирівнює сегменти), тому ставимо для всіх ABI.
target_link_options(pockethost_jvm PRIVATE "LINKER:-z,max-page-size=16384")
