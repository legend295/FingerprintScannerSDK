package com.scanner.test2;

import java.lang.reflect.Field;

import com.sun.jna.Platform;

public class LibraryLoader {
    // ===========================================================
    // Public static methods
    // ===========================================================
    
    public static void initLibraryPath() {
        String libraryPath = getLibraryPath();
        String jnaLibraryPath = System.getProperty("jna.library.path");
        if (jnaLibraryPath == null || "".equals(jnaLibraryPath)) {
            System.setProperty("jna.library.path", libraryPath.toString());
        } else {
            System.setProperty("jna.library.path", combinePath(jnaLibraryPath, libraryPath.toString()));
        }
        System.setProperty("java.library.path", combinePath(System.getProperty("java.library.path"), libraryPath.toString()));
        try {
            Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
            fieldSysPath.setAccessible(true);
            fieldSysPath.set(null, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // ===========================================================
    // Private static methods
    // ===========================================================
    
    private static String getParentPath(String path) {
        String fileSeparator = System.getProperty("file.separator");
        int index = path.lastIndexOf(fileSeparator);
        return index == -1 ? path : path.substring(0, index);
    }
            
    private static String combinePath(String path1, String path2) {
        String fileSeparator = System.getProperty("file.separator");
        return String.format("%s%s%s", path1, fileSeparator, path2);
    }

    private static String getArchitecture() {
        String arch = Platform.ARCH;
        if (arch.equals("aarch64"))
            return "arm64";
        else if (arch.equals("arm"))
            return "armhf";
        else if (arch.equals("x86-64"))
            return "x86_64";
        return arch;
    }

    private static String getLibraryPath() {
        String parent = getParentPath(getParentPath(System.getProperty("user.dir")));
        if (Platform.isWindows()) {
            return combinePath(parent, combinePath("Bin", combinePath("Windows", Platform.is64Bit() ? "x64" : "x86")));
        } else if (Platform.isLinux()) {
            return combinePath(parent, combinePath("Lib", combinePath("Linux", getArchitecture())));
        }
        return null;
    }
    
    private LibraryLoader() {
    }
}
