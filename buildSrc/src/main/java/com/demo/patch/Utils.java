package com.demo.patch;

import org.apache.commons.codec.digest.DigestUtils;
import org.gradle.api.Project;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class Utils {

    public static String capitalize(String name) {
        return name.length() == 0 ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    public static boolean isEmpty(String string) {
        return string == null || string.isEmpty();
    }

    public static boolean isEmpty(Map<String, String> map) {
        return map == null || map.isEmpty();
    }

    public static boolean isAndroidClass(String className) {
        return className.contains("android") || className.contains("androidx");
    }

    public static String hex(byte[] bytes) {
        return DigestUtils.md5Hex(bytes);
    }

    public static void writeHex(Map<String, String> hexes, File hexFile) {
        try {
            if (!hexFile.exists()) {
                hexFile.getParentFile().mkdirs();
                hexFile.createNewFile();
            }
            FileOutputStream fileOutputStream = new FileOutputStream(hexFile);
            for (String key : hexes.keySet()) {
                String line = key + ":" + hexes.get(key) + "\n";
                fileOutputStream.write(line.getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Map<String, String> readHex(File hexFile) {
        Map<String, String> hashMap = new HashMap<>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(hexFile)));
            String line;
            while ((line = br.readLine()) != null) {
                String[] list = line.split(":");
                if (list != null && list.length == 2) {
                    hashMap.put(list[0], list[1]);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return hashMap;
    }

    public static File getOrCreateOutputDir(Project project, String variantName, PatchExtension patchExtension) {
        File outputDir;
        // 如果 build.gradle 中没有指定 patch -> output 就用默认值 /build/patch/[variantName]
        if (!Utils.isEmpty(patchExtension.getOutput())) {
            outputDir = new File(patchExtension.getOutput(), variantName);
        } else {
            outputDir = new File(project.getBuildDir(), "patch/" + variantName);
        }

        project.getLogger().info("补丁输出路径：" + outputDir.getAbsolutePath());

        outputDir.mkdirs();
        return outputDir;
    }
}
