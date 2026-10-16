package com.eta.flymefreeform.framework

/**
 * @author bomo 「重启作用域」的结果；界面按它给出不同文案，不把「尝试」说成「成功」。
 */
internal enum class ScopeRestartOutcome {
    /** 作用域进程已重启，且配置已补推 —— 模块保持启用。 */
    Restarted,

    /**
     * 作用域进程已重启，但 App 与框架服务已断连（模块很可能在 LSPosed 里被停用），
     * 因此只发了硬停、没有补推配置 —— system_server 里的注入已停用，无需重启手机。
     */
    StoppedWithoutConnection,

    /** 没能把重启指令交给 su。 */
    Failed,
}
