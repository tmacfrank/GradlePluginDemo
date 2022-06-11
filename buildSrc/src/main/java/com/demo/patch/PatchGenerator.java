package com.demo.patch;

import com.android.build.gradle.AppExtension;

import org.apache.tools.ant.taskdefs.condition.Os;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.process.ExecSpec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class PatchGenerator {

    private Map<String, String> prevHexes = new HashMap<>();
    private Project project;
    private File patchFile;
    private File jarFile;
    private JarOutputStream jarOutputStream;
    private String buildToolsVersion;

    public PatchGenerator(Project project, File patchFile, File jarFile, File hexFile) {
        this.project = project;
        this.patchFile = patchFile;
        this.jarFile = jarFile;
        // 从 android{} 中获取 buildToolsVersion 属性
        buildToolsVersion = project.getExtensions().getByType(AppExtension.class).getBuildToolsVersion();
        // 从备份文件中读取上一次编译生成的 class 文件名和 md5 值
        if (hexFile.exists()) {
            prevHexes = Utils.readHex(hexFile);
            project.getLogger().info("从备份文件 " + hexFile.getAbsolutePath() + " 中读取md5值");
        } else {
            // 如果备份文件不存在，可能是首次编译，直接创建备份文件
            try {
                if (hexFile.createNewFile()) {
                    project.getLogger().info("创建备份文件成功：" + hexFile.getAbsolutePath());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 检查本次编译的 md5 与上一次的是否相同，如果不同说明文件
     * 有变化，需要打包进补丁包
     *
     * @param className class 文件全类名对应的路径
     * @param newHex    新编译后 class 文件的 md5 值
     * @param bytes     新编译后 class 文件的字节内容
     */
    public void checkClass(String className, String newHex, byte[] bytes) {
        if (Utils.isEmpty(prevHexes)) {
            return;
        }

        // 如果 newHex 不在缓存中或者与缓存中的值不相等，就要放入补丁包
        String oldHex = prevHexes.get(className);
        if (oldHex == null || !oldHex.equals(newHex)) {
            JarOutputStream jarOutputStream = getJarOutputStream();
            try {
                jarOutputStream.putNextEntry(new JarEntry(className));
                jarOutputStream.write(bytes);
                jarOutputStream.closeEntry();
                project.getLogger().info("放入补丁包，文件路径：" + className);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private JarOutputStream getJarOutputStream() {
        if (jarOutputStream == null) {
            try {
                jarOutputStream = new JarOutputStream(new FileOutputStream(jarFile));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return jarOutputStream;
    }

    /**
     * 运行 dx 命令将 class/jar 文件打包成 dex 文件，Java Runtime 和
     * Gradle 都提供了运行 Java 命令的方法
     */
    public void generate() throws Exception {
        if (!jarFile.exists()) {
            return;
        }

        // 关流 jar 包才会去写数据
        getJarOutputStream().close();

        Properties properties = new Properties();
        File localPropFile = project.getRootProject().file("local.properties");
        // dx 命令在 sdk 中，先获取 sdk 路径，再拼接出 dx 命令的绝对路径
        String sdkDir;
        if (localPropFile.exists()) {
            properties.load(new FileInputStream(localPropFile));
            sdkDir = properties.getProperty("sdk.dir");
        } else {
            sdkDir = System.getenv("ANDROID_HOME");
        }

        // Windows 使用 dx.bat 命令，linux/mac 使用 dx 命令
        String cmdExt = Os.isFamily(Os.FAMILY_WINDOWS) ? ".bat" : "";
        String dxPath = sdkDir + "/build-tools/" + buildToolsVersion + "/dx" + cmdExt;
        String patch = "--output=" + patchFile.getAbsolutePath();

        project.exec(new Action<ExecSpec>() {
            @Override
            public void execute(ExecSpec execSpec) {
                execSpec.commandLine(dxPath, "--dex", patch, jarFile.getAbsolutePath());
                project.getLogger().info("执行了命令：" + (dxPath + " --dex" + patch + jarFile.getAbsolutePath()));
            }
        });

        // 删除 class 组成的 jar 包
        jarFile.delete();

        /*// 使用 Java Runtime 执行 dx 命令
        final String cmd = dxPath + " --dex " + patch + " " + jarFile.getAbsolutePath();
        Process process = Runtime.getRuntime().exec(cmd);
        process.waitFor();
        // 命令执行失败
        if (process.exitValue() != 0) {
            throw new IOException("generate patch error:" + cmd);
        }*/
        project.getLogger().info("\npatch generated in : " + patchFile);
    }
}
