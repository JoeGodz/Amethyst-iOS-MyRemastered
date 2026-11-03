package net.kdt.patchjna;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class PatchJNAAgent implements ClassFileTransformer {
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
    ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        byte[] transformeredByteCode = classfileBuffer;
        if (className.equals("com/sun/jna/Platform")) {
            System.out.println("PatchJNAAgent: Replacing com/sun/jna/Platform");
            transformeredByteCode = loadPatch("com/sun/jna/Platform.class.patch", classfileBuffer);
        } else if (className.equals("com/mojang/blaze3d/platform/MacosUtil")) {
            // 26.3: real MacosUtil pulls JNA/objc; unsigned jna*.tmp fails on
            // unjailbroken iOS. Replace with iOS no-op stub (see MacosUtil.java).
            System.out.println("PatchJNAAgent: Replacing com/mojang/blaze3d/platform/MacosUtil (iOS stub)");
            transformeredByteCode = loadPatch(
                "com/mojang/blaze3d/platform/MacosUtil.class.patch", classfileBuffer);
        }
        return transformeredByteCode;
    }

    private static byte[] loadPatch(String resourceName, byte[] fallback) {
        try {
            InputStream inputStream = PatchJNAAgent.class.getClassLoader().getResourceAsStream(resourceName);
            if (inputStream == null) {
                System.err.println("PatchJNAAgent: missing resource " + resourceName);
                return fallback;
            }
            try (DataInputStream dataInputStream = new DataInputStream(inputStream)) {
                byte[] bytes = new byte[inputStream.available()];
                dataInputStream.readFully(bytes);
                return bytes;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return fallback;
        }
    }

    public static void premain(String args, Instrumentation instrumentation) {
        System.out.println("PatchJNAAgent: premain called");
        instrumentation.addTransformer(new PatchJNAAgent());
    }
}
