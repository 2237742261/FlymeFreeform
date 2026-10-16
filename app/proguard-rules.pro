# LSPosed 按 META-INF/xposed/java_init.list 里的字符串加载模块入口类，
# R8 看不到这条引用，所以必须显式保留类名。
-keep class com.eta.flymefreeform.ModuleMain { *; }

# 模块入口基类由框架实例化，保留其子类不被裁掉（保险）。
-keepnames class com.eta.flymefreeform.ModuleMain
