package com.blackannin.drone.client;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.ClientConfig;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.system.JNI.invokeP;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.Pointer.POINTER_SIZE;
import static org.lwjgl.system.libffi.LibFFI.FFI_DEFAULT_ABI;
import static org.lwjgl.system.libffi.LibFFI.FFI_OK;
import static org.lwjgl.system.libffi.LibFFI.ffi_call;
import static org.lwjgl.system.libffi.LibFFI.ffi_prep_cif;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_sint32;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_uint32;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_uint8;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;

/**
 * 用 LWJGL JNI + libffi 调用 SpoutLibrary.dll，避开 Java 21 预览 FFM，
 * 也不依赖当前 LWJGL 版本没有的 invokePI 长参数重载。
 *
 * <p>发送约定：纹理已由调用方预先垂直翻转（glBlitFramebuffer 反向 Y 实现），
 * 因此 SendTexture 以 invert=false 调用，让 Spout 走直接拷贝路径、
 * 不触碰其内部 FBO（invert=true 会触发库内部 FBO 绑定，
 * 在部分驱动上产生 GL_INVALID_OPERATION 且画面无法送达）。</p>
 */
public final class SpoutSender {
    private static boolean attempted;
    private static boolean available;
    private static SharedLibrary library;
    private static long spout;
    private static long createSenderFn;
    private static long releaseSenderFn;
    private static long sendTextureFn;
    private static int senderWidth;
    private static int senderHeight;
    private static String senderName = "";

    private SpoutSender() {
    }

    public static boolean isAvailable() {
        return available;
    }

    public static void initIfNeeded() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            BlackAnninsDrone.LOGGER.warn("Spout 仅支持 Windows，已跳过");
            return;
        }
        try {
            Path dll = resolveLibrary();
            if (dll == null) {
                BlackAnninsDrone.LOGGER.warn("未找到 SpoutLibrary.dll。请放到游戏目录 natives/ 下，或在配置中填写路径");
                return;
            }
            library = Library.loadNative(SpoutSender.class, BlackAnninsDrone.MODID, dll.toAbsolutePath().toString());
            long getSpout = library.getFunctionAddress("GetSpout");
            if (getSpout == NULL) {
                getSpout = library.getFunctionAddress("getSpout");
            }
            if (getSpout == NULL) {
                throw new IllegalStateException("DLL 中没有 GetSpout");
            }
            spout = invokeP(getSpout);
            if (spout == NULL) {
                throw new IllegalStateException("GetSpout 返回空指针");
            }
            long vtable = MemoryUtil.memGetAddress(spout);
            createSenderFn = MemoryUtil.memGetAddress(vtable);
            releaseSenderFn = MemoryUtil.memGetAddress(vtable + POINTER_SIZE);
            sendTextureFn = MemoryUtil.memGetAddress(vtable + 3L * POINTER_SIZE);
            available = true;
            BlackAnninsDrone.LOGGER.info("Spout 发送器已加载: {}", dll);
        } catch (Throwable t) {
            BlackAnninsDrone.LOGGER.warn("Spout 初始化失败: {}", t.toString());
            available = false;
        }
    }

    /** 纹理必须为已翻转的成品帧；分辨率变化时自动重建发送器（免重启即时生效） */
    public static void send(int textureId, int width, int height) {
        if (!available || textureId <= 0 || spout == NULL) {
            return;
        }
        String name = ClientConfig.SPOUT_SENDER_NAME.get();
        try {
            if (!name.equals(senderName) || senderWidth != width || senderHeight != height) {
                if (!senderName.isEmpty()) {
                    callRelease();
                    if (senderWidth != width || senderHeight != height) {
                        BlackAnninsDrone.LOGGER.info("Spout 输出分辨率切换: {}x{} -> {}x{}", senderWidth, senderHeight, width, height);
                    }
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    long namePtr = MemoryUtil.memAddress(stack.UTF8(name));
                    if (callCreateSender(namePtr, width, height) == 0) {
                        BlackAnninsDrone.LOGGER.warn("CreateSender 失败: {}", name);
                        return;
                    }
                }
                senderName = name;
                senderWidth = width;
                senderHeight = height;
                // 输出分辨率写入日志：OBS 的 Spout 源需按此尺寸（或重新添加源）才能满屏无黑边
                BlackAnninsDrone.LOGGER.info("Spout 输出分辨率已就绪: {}x{}（发送器 {}）", width, height, name);
            }
            callSendTexture(textureId, GL11.GL_TEXTURE_2D, width, height, false, 0);
        } catch (Throwable t) {
            BlackAnninsDrone.LOGGER.warn("Spout 发送失败: {}", t.toString());
            available = false;
        }
    }

    public static void release() {
        if (!available || spout == NULL) {
            return;
        }
        try {
            callRelease();
        } catch (Throwable ignored) {
        }
        senderName = "";
        senderWidth = 0;
        senderHeight = 0;
    }

    /** CreateSender(this, name, w, h, format) */
    private static int callCreateSender(long namePtr, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer types = stack.mallocPointer(5);
            types.put(ffi_type_pointer.address());
            types.put(ffi_type_pointer.address());
            types.put(ffi_type_uint32.address());
            types.put(ffi_type_uint32.address());
            types.put(ffi_type_uint32.address());
            types.flip();

            FFICIF cif = FFICIF.malloc(stack);
            if (ffi_prep_cif(cif, FFI_DEFAULT_ABI, ffi_type_sint32, types) != FFI_OK) {
                throw new IllegalStateException("ffi_prep_cif CreateSender 失败");
            }

            PointerBuffer args = stack.mallocPointer(5);
            args.put(argPtr(stack, spout));
            args.put(argPtr(stack, namePtr));
            args.put(argInt(stack, width));
            args.put(argInt(stack, height));
            args.put(argInt(stack, 0));
            args.flip();

            ByteBuffer result = stack.calloc(4);
            ffi_call(cif, createSenderFn, result, args);
            return result.getInt(0);
        }
    }

    /** ReleaseSender(this, dwMsec) */
    private static void callRelease() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer types = stack.mallocPointer(2);
            types.put(ffi_type_pointer.address());
            types.put(ffi_type_uint32.address());
            types.flip();

            FFICIF cif = FFICIF.malloc(stack);
            if (ffi_prep_cif(cif, FFI_DEFAULT_ABI, ffi_type_void, types) != FFI_OK) {
                throw new IllegalStateException("ffi_prep_cif ReleaseSender 失败");
            }

            PointerBuffer args = stack.mallocPointer(2);
            args.put(argPtr(stack, spout));
            args.put(argInt(stack, 0));
            args.flip();

            ByteBuffer ignored = stack.calloc(8);
            ffi_call(cif, releaseSenderFn, ignored, args);
        }
    }

    /** SendTexture(this, texId, target, w, h, invert, hostFbo) */
    private static void callSendTexture(int textureId, int target, int width, int height, boolean invert, int hostFbo) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer types = stack.mallocPointer(7);
            types.put(ffi_type_pointer.address());
            types.put(ffi_type_uint32.address());
            types.put(ffi_type_uint32.address());
            types.put(ffi_type_uint32.address());
            types.put(ffi_type_uint32.address());
            types.put(ffi_type_uint8.address());
            types.put(ffi_type_uint32.address());
            types.flip();

            FFICIF cif = FFICIF.malloc(stack);
            if (ffi_prep_cif(cif, FFI_DEFAULT_ABI, ffi_type_sint32, types) != FFI_OK) {
                throw new IllegalStateException("ffi_prep_cif SendTexture 失败");
            }

            PointerBuffer args = stack.mallocPointer(7);
            args.put(argPtr(stack, spout));
            args.put(argInt(stack, textureId));
            args.put(argInt(stack, target));
            args.put(argInt(stack, width));
            args.put(argInt(stack, height));
            args.put(argByte(stack, invert ? (byte) 1 : (byte) 0));
            args.put(argInt(stack, hostFbo));
            args.flip();

            ByteBuffer result = stack.calloc(4);
            ffi_call(cif, sendTextureFn, result, args);
        }
    }

    private static long argPtr(MemoryStack stack, long value) {
        long addr = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
        MemoryUtil.memPutAddress(addr, value);
        return addr;
    }

    private static long argInt(MemoryStack stack, int value) {
        long addr = stack.nmalloc(Integer.BYTES, Integer.BYTES);
        MemoryUtil.memPutInt(addr, value);
        return addr;
    }

    private static long argByte(MemoryStack stack, byte value) {
        long addr = stack.nmalloc(Byte.BYTES, Byte.BYTES);
        MemoryUtil.memPutByte(addr, value);
        return addr;
    }

    private static Path resolveLibrary() {
        List<Path> candidates = new ArrayList<>();
        String configured = ClientConfig.SPOUT_LIBRARY_PATH.get();
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        Path gameDir = Path.of("").toAbsolutePath();
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.gameDirectory != null) {
            gameDir = mc.gameDirectory.toPath();
        }
        candidates.add(gameDir.resolve("natives").resolve("SpoutLibrary.dll"));
        candidates.add(gameDir.resolve("SpoutLibrary.dll"));
        String prog = System.getenv("ProgramFiles");
        if (prog != null) {
            candidates.add(Path.of(prog, "Spout2", "SpoutLibrary.dll"));
            candidates.add(Path.of(prog, "Spout", "SpoutLibrary.dll"));
        }
        for (Path path : candidates) {
            if (path != null && Files.isRegularFile(path)) {
                return path;
            }
        }

        // 从模组资源提取DLL到临时目录
        try {
            Path nativeDir = gameDir.resolve("natives");
            if (!Files.exists(nativeDir)) {
                Files.createDirectories(nativeDir);
            }

            Path extractedDll = nativeDir.resolve("SpoutLibrary.dll");
            // 如果已存在且大小正常，直接使用
            if (Files.exists(extractedDll) && Files.size(extractedDll) > 100000) {
                BlackAnninsDrone.LOGGER.info("使用已提取的 SpoutLibrary.dll: {}", extractedDll);
                return extractedDll;
            }

            // 从资源提取
            var resource = SpoutSender.class.getResourceAsStream("/native/SpoutLibrary.dll");
            if (resource != null) {
                Files.copy(resource, extractedDll, StandardCopyOption.REPLACE_EXISTING);
                BlackAnninsDrone.LOGGER.info("从模组资源提取 SpoutLibrary.dll 到: {}", extractedDll);

                // 同时提取依赖的DLL
                extractDependency(nativeDir, "Spout.dll");
                extractDependency(nativeDir, "SpoutDX.dll");

                return extractedDll;
            }
        } catch (Throwable t) {
            BlackAnninsDrone.LOGGER.warn("从模组资源提取 SpoutLibrary.dll 失败: {}", t.toString());
        }

        return null;
    }

    private static void extractDependency(Path nativeDir, String dllName) {
        try {
            Path target = nativeDir.resolve(dllName);
            if (Files.exists(target) && Files.size(target) > 50000) {
                return; // 已存在且大小正常
            }

            var resource = SpoutSender.class.getResourceAsStream("/native/" + dllName);
            if (resource != null) {
                Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING);
                BlackAnninsDrone.LOGGER.info("提取依赖DLL: {}", dllName);
            }
        } catch (Throwable t) {
            BlackAnninsDrone.LOGGER.warn("提取依赖DLL {} 失败: {}", dllName, t.toString());
        }
    }
}
