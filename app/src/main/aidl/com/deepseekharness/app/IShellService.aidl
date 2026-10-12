package com.deepseekharness.app;

interface IShellService {
    String exec(String cmd) = 0;
    // Native-only typed launcher; generic shell text may not invoke app_process.
    String execVirtualScreen(String command) = 1;
    // Shizuku 约定的销毁事务；升级或解绑时退出旧的特权服务进程。
    // AIDL adds FIRST_CALL_TRANSACTION (1) to the declared ID, so this declaration becomes binder txn
    // 16777115, which is what Shizuku's server actually sends (ShizukuApiConstants
    // .USER_SERVICE_TRANSACTION_destroy, "use 16777114 in aidl"). If you change this ID, update the
    // hand-maintained equivalent stub in tools/aidl-stub/com/deepseekharness/app/IShellService.java too.
    void destroy() = 16777114;
}
