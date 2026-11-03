package net.kdt.patchshadermgr;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Locale;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * MC 26.3+ ShaderManager.apply still treats missing OIT / clouds pipelines as fatal
 * even when graphicsMode/transparency are forced to 0. Swallow those failures so
 * ANGLE/OpenGL can finish resource reload and reach gameplay on iOS.
 */
public final class PatchShaderManagerAgent implements ClassFileTransformer, Opcodes {
    private static final String TARGET = "net/minecraft/client/renderer/ShaderManager";
    private static final String SELF = "net/kdt/patchshadermgr/PatchShaderManagerAgent";
    private static final String SWALLOW_DESC = "(Ljava/lang/Throwable;)Z";

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[PatchShaderManager] premain active (OIT/clouds pipeline skip)");
        inst.addTransformer(new PatchShaderManagerAgent(), true);
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    /**
     * Return true when every failed pipeline named in the exception message is an
     * OIT/clouds/flat_clouds entry that Amethyst intentionally cannot compile on ANGLE.
     */
    public static boolean shouldSwallowShaderFailure(Throwable t) {
        if (t == null) {
            return false;
        }
        String msg = t.getMessage();
        if (msg == null || !msg.contains("Failed to load required shader programs")) {
            Throwable cause = t.getCause();
            if (cause != null && cause != t) {
                return shouldSwallowShaderFailure(cause);
            }
            return false;
        }

        boolean sawPipeline = false;
        String[] lines = msg.split("\\R");
        for (String raw : lines) {
            String line = raw.trim();
            if (!line.startsWith("-")) {
                continue;
            }
            String name = line.substring(1).trim();
            int slash = name.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < name.length()) {
                name = name.substring(slash + 1);
            }
            String shortName = name.toLowerCase(Locale.ROOT);
            sawPipeline = true;
            if (shortName.startsWith("oit_")) {
                continue;
            }
            if ("clouds".equals(shortName) || "flat_clouds".equals(shortName)) {
                continue;
            }
            if (shortName.contains("clouds")) {
                continue;
            }
            System.out.println("[PatchShaderManager] refusing to swallow non-OIT failure: " + shortName);
            return false;
        }
        if (sawPipeline) {
            System.out.println("[PatchShaderManager] swallowed ShaderManager OIT/clouds pipeline failure");
        }
        return sawPipeline;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className == null || !TARGET.equals(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null || !"apply".equals(name)) {
                        return mv;
                    }
                    // Only wrap void instance methods (ShaderManager.apply overload family).
                    if (Type.getReturnType(descriptor) != Type.VOID_TYPE) {
                        return mv;
                    }
                    if ((access & (ACC_STATIC | ACC_ABSTRACT | ACC_NATIVE)) != 0) {
                        return mv;
                    }
                    return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
                        private final Label start = new Label();
                        private final Label end = new Label();
                        private final Label handler = new Label();

                        @Override
                        protected void onMethodEnter() {
                            visitLabel(start);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            visitLabel(end);
                            visitLabel(handler);
                            visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
                            // stack: RuntimeException
                            visitInsn(DUP);
                            visitMethodInsn(INVOKESTATIC, SELF, "shouldSwallowShaderFailure",
                                    SWALLOW_DESC, false);
                            Label rethrow = new Label();
                            visitJumpInsn(IFEQ, rethrow);
                            visitInsn(POP);
                            visitInsn(RETURN);
                            visitLabel(rethrow);
                            visitInsn(ATHROW);
                            super.visitMaxs(0, 0);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            byte[] out = writer.toByteArray();
            System.out.println("[PatchShaderManager] instrumented " + TARGET + ".apply (" + out.length + " bytes)");
            return out;
        } catch (Throwable e) {
            System.err.println("[PatchShaderManager] transform failed: " + e);
            e.printStackTrace();
            return null;
        }
    }
}
