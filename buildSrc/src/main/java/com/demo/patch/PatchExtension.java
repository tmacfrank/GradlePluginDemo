package com.demo.patch;

public class PatchExtension {

    /**
     * 是否在 debug 模式下开启热修复，默认为 false
     */
    private boolean debugOn;

    /**
     * Application 的全类名。由于热修复一般是在 Application 中执行的，执行热修复代码时 Application
     * 已经被系统 ClassLoader 加载了，无法再替换，所以热修复时要刨除掉 Application 的 class 文件。
     * 虽然 Application 信息可以通过解析插件中的 AndroidManifest 获取，但是通过 Java 实现的插件
     * 解析 xml 很麻烦（Groovy 简单些），因此直接要求作为配置项获取
     */
    private String applicationName;

    /**
     * 可选项，补丁的输出目录，默认为 app/build/patch
     */
    private String output;

    public PatchExtension() {
        this.debugOn = false;
    }

    public boolean isDebugOn() {
        return debugOn;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getOutput() {
        return output;
    }

    public void setDebugOn(boolean debugOn) {
        this.debugOn = debugOn;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public void setOutput(String output) {
        this.output = output;
    }
}
