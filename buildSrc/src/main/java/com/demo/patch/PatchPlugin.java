package com.demo.patch;

import com.android.build.gradle.AppExtension;
import com.android.build.gradle.AppPlugin;
import com.android.build.gradle.api.ApplicationVariant;
import com.android.build.gradle.internal.pipeline.TransformTask;
import com.android.build.gradle.internal.transforms.ProGuardTransform;
import com.android.utils.FileUtils;

import org.apache.commons.io.IOUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskOutputs;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;

public class PatchPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // 作用在 application 插件上，而不是 library
        if (!project.getPlugins().hasPlugin(AppPlugin.class)) {
            throw new GradleException("本插件需要结合Android Application插件使用！！！");
        }

        // 创建一个 patch 扩展，支持的属性定义在 PatchExtension 中，其它模块
        // 在 build.gradle 中引入本插件后，就可以用 patch{} 进行配置
        project.getExtensions().create("patch", PatchExtension.class);

        // afterEvaluate() 在解析 build.gradle 文件完成后回调
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                PatchExtension patchExtension = project.getExtensions().findByType(PatchExtension.class);
                if (patchExtension != null) {
                    // debug 模式下是否打补丁包
                    boolean debugOn = patchExtension.isDebugOn();
                    project.getLogger().info("debugOn:" + debugOn + ", ApplicationName:" + patchExtension.getApplicationName());
                    // 获取 android 扩展
                    AppExtension android = project.getExtensions().getByType(AppExtension.class);
                    // 遍历 android -> buildTypes 下所有的变体，如 debug、release 等
                    android.getApplicationVariants().all(new Action<ApplicationVariant>() {
                        @Override
                        public void execute(ApplicationVariant applicationVariant) {
                            // 如果编译的是 debug 版本并且已经配置了 debug 不需要生成补丁包，就不作处理
                            if (applicationVariant.getName().contains("debug") && !debugOn) {
                                return;
                            }
                            // 开始编译配置与生成补丁工作
                            configTasks(project, applicationVariant, patchExtension);
                        }
                    });
                }
            }
        });
    }

    private void configTasks(Project project, ApplicationVariant variant, PatchExtension patchExtension) {
        // 1.创建补丁文件的输出路径
        String variantName = variant.getName();
        File outputDir = Utils.getOrCreateOutputDir(project, variantName, patchExtension);

        // 2.获取 Android 的混淆任务，并配置混淆任务使用的 mapping 文件
        String variantCapName = Utils.capitalize(variantName);
        Task proguardTask = project.getTasks().findByName("transformClassesAndResourcesWithProguardFor"
                + variantCapName);
        if (proguardTask != null) {
            configProguardTask(project, proguardTask);
        }

        // 3.配置任务，进行字节码插桩和补丁生成
        Task dexTask = getTransformTask(project, patchExtension, outputDir, variantCapName);

        // 4.创建打补丁的任务 patchDebug/patchRelease，依赖于 dex 打包任务
        Task task = project.getTasks().create("patch" + variantCapName);
        task.setGroup("patch");
        task.dependsOn(dexTask);
    }

    @NotNull
    private Task getTransformTask(Project project, PatchExtension patchExtension, File outputDir, String variantCapName) {
        // 保存 class 文件名及其 md5 值的文件
        File hexFile = new File(outputDir, "hex.txt");
        // 需要打补丁的类组成的 jar 包
        File patchClassFile = new File(outputDir, "patchClass.jar");
        // dx 命令打包 patchClassFile 后生成的补丁包，最终产物
        File patchFile = new File(outputDir, "patch.jar");

        // 获取将 class 打包成 dex 的任务
        Task dexTask = project.getTasks().findByName("transformClassesWithDexBuilderFor" + variantCapName);
        // 在开始打包之前，插桩并记录每个 class 的 md5 哈希值
        dexTask.doFirst(new Action<Task>() {
            @Override
            public void execute(Task task) {
                // 将 Application 全类名中的 . 替换成平台相关的斜杠，Windows 是 xx\xx\，Linux 是 xx/xx/
                String applicationName = patchExtension.getApplicationName();
                applicationName = applicationName.replaceAll("\\.", Matcher.quoteReplacement(File.separator));

                // 记录类本次编译的 md5 值
                Map<String, String> newHexes = new HashMap<>();

                // 负责生成补丁
                PatchGenerator patchGenerator = new PatchGenerator(project, patchFile, patchClassFile, hexFile);

                // 遍历 dexTask 任务的输入文件，对 class 和 jar 文件进行处理，像 app 中的 MainActivity
                // 的路径是：app\build\intermediates\transforms\proguard\debug\0.jar
                Set<File> files = dexTask.getInputs().getFiles().getFiles();
                for (File file : files) {
                    String filePath = file.getAbsolutePath();
                    // 插桩，并做 md5 值比较，不一致的放入补丁包
                    if (filePath.endsWith(".class")) {
                        processClass(project, applicationName, file, newHexes, patchGenerator);
                    } else if (filePath.endsWith(".jar")) {
                        processJar(project, applicationName, file, newHexes, patchGenerator);
                    }
                }

                // 保存本次编译的 md5
                Utils.writeHex(newHexes, hexFile);

                // 生成补丁文件
                try {
                    patchGenerator.generate();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        return dexTask;
    }

    private void configProguardTask(Project project, Task proguardTask) {
        if (proguardTask == null) {
            return;
        }

        // 如果有备份的 mapping 文件，那么本次编译还要使用上次的 mapping
        File backupMappingFile = new File(project.getBuildDir(), "mapping.txt");
        if (backupMappingFile.exists()) {
            TransformTask task = (TransformTask) proguardTask;
            ProGuardTransform transform = (ProGuardTransform) task.getTransform();
            // 相当于在 proguard-rules.pro 中配置了 -applymapping mapping.txt
            transform.applyTestedMapping(backupMappingFile);
        }

        // 只要开启了混淆，在混淆任务结束后就要把 mapping 文件备份
        proguardTask.doLast(new Action<Task>() {
            @Override
            public void execute(Task task) {
                // mapping 文件在 proguardTask 的输出之中
                TaskOutputs outputs = proguardTask.getOutputs();
                Set<File> files = outputs.getFiles().getFiles();
                for (File file : files) {
                    if (file.getName().endsWith("mapping.txt")) {
                        try {
                            FileUtils.copyFile(file, backupMappingFile);
                            project.getLogger().info("mapping: " + backupMappingFile.getCanonicalPath());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                }
            }
        });
    }

    /**
     * 对 class 文件执行插桩，并记录插装后的 md5，与上一次编译的备份 md5
     * 做比较，如果比较结果不相同，说明文件发生了变化，需要打包进补丁包中
     *
     * @param applicationName Application 全类名对应的路径名，如：com\demo\plugin\Application
     * @param file            待处理的 class 文件
     * @param newHexes        记录 Map<class,String> 类名与对应 md5 值的 Map
     * @param patchGenerator  生成补丁包
     */
    private void processClass(Project project, String applicationName, File file, Map<String, String> newHexes,
                              PatchGenerator patchGenerator) {
        // 截取文件的绝对路径，仅保留包名之后的部分，比如 filePath 为
        // app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes\com\demo\plugin\Test.class，
        // 那么截取后的 classPath 就是 com\demo\plugin\Test.class
        String filePath = file.getAbsolutePath();
        String classPath = filePath.split("classes")[1].substring(1);

        if (classPath.startsWith(applicationName) || Utils.isAndroidClass(classPath)) {
            return;
        }

        try {
            project.getLogger().info("开始处理 class 文件：" + filePath);
            FileInputStream fis = new FileInputStream(filePath);
            // 插桩
            byte[] bytes = ClassUtils.referHackWhenInit(fis);

            // 生成这个 class 文件的 16 进制 md5
            String hex = Utils.hex(bytes);
            fis.close();

            // 输出插桩后的 class 文件
            FileOutputStream fos = new FileOutputStream(filePath);
            fos.write(bytes);
            fos.close();

            // 将本次的 md5 值存入缓存，并与上一次的 md5 进行对比
            newHexes.put(classPath, hex);
            patchGenerator.checkClass(classPath, hex, bytes);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 对 jar 包中的 class 文件执行插桩，并记录插装后的 md5，与上一次编译的备份 md5
     * 做比较，如果比较结果不相同，说明文件发生了变化，需要打包进补丁包中
     *
     * @param applicationName
     * @param file            条件限定，这个 file 是个 jar 包
     * @param hexes           保存类名及其 md5 值的 Map
     * @param patchGenerator  生成补丁
     */
    private void processJar(Project project, String applicationName, File file, Map<String, String> hexes,
                            PatchGenerator patchGenerator) {
        try {
            applicationName = applicationName.replaceAll(Matcher.quoteReplacement(File.separator), "/");
            File backupJar = new File(file.getParent(), file.getName() + ".bak");

            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(backupJar));

            JarFile jarFile = new JarFile(file);
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                String className = jarEntry.getName();
                jarOutputStream.putNextEntry(new JarEntry(className));
                InputStream inputStream = jarFile.getInputStream(jarEntry);

                if (className.endsWith(".class") && !className.startsWith(applicationName) &&
                        !Utils.isAndroidClass(className) && !className.startsWith("com/demo/patch")) {
                    project.getLogger().info("开始处理 jar 包中的 class 文件：" + className);
                    byte[] bytes = ClassUtils.referHackWhenInit(inputStream);
                    String hex = Utils.hex(bytes);
                    hexes.put(className, hex);
                    // 对比缓存的 md5，不一致则放入补丁
                    patchGenerator.checkClass(className, hex, bytes);
                    jarOutputStream.write(bytes);
                } else {
                    // 输出到临时文件
                    jarOutputStream.write(IOUtils.toByteArray(inputStream));
                }

                inputStream.close();
                jarOutputStream.closeEntry();
            }

            jarOutputStream.close();
            jarFile.close();
            file.delete();
            backupJar.renameTo(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
