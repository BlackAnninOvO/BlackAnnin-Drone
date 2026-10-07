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
 * <p>虚表槽位以官方 SDK 2.007.017 的 SpoutLibrary.h 为准：
 * 0=SetSenderName · 1=SetSenderFormat · 2=ReleaseSender · 3=SendFbo · 4=SendTexture。
 * （0.7.0 排障发现：曾误按"0=CreateSender/3=SendTexture"调用——实际调到了
 * SetSenderName 与 SendFbo，后者参数错位后回退读"当前绑定帧缓冲"，恰为 main，
 * 推流内容碰巧一致而未暴露，OSD 等纹理内改动则永远丢失。）</p>
 *
 * <p>发送流程：SetSenderName 命名后，SendTexture 按当前名称/尺寸自动创建、
 * 更新发送端，无需显式 CreateSender。</p>
 *
 * <p>发送约定：纹理已由调用方预先垂直翻转（glBlitFramebuffer 反向 Y 实现），
 * 因此 SendTexture 以 invert=false 调用，让 Spout 走直接拷贝路径、
 * 不触碰其内部 FBO（invert=true 会触发库内部 FBO 绑定，
 * 在部分驱动上产生 GL_INVALID_OPERATION 且画面无法送达）。</p>
 */
public final class SpoutSender {
    private static boolean attempted;
    private static boolean available;
    /** 当前是否存在已创建、未释放的 Spout 发送端（available 因发送失败置 false 后它仍为 true） */
    private static boolean senderActive;
    /** 是否已注册 JVM 关闭钩子（仅需注册一次） */
    private static boolean hookRegistered;
    private static SharedLibrary library;
    private static long spout;
    private static long setSenderNameFn;
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
            // 官方 SpoutLibrary.h（SDK 2.007.017）虚表槽位：
            // 0=SetSenderName(const char*) 1=SetSenderFormat(DWORD) 2=ReleaseSender(DWORD)
            // 3=SendFbo(FboID,w,h,invert) 4=SendTexture(TexID,Target,w,h,invert,HostFBO)
            setSenderNameFn = MemoryUtil.memGetAddress(vtable);
            releaseSenderFn = MemoryUtil.memGetAddress(vtable + 2L * POINTER_SIZE);
            sendTextureFn = MemoryUtil.memGetAddress(vtable + 4L * POINTER_SIZE);
            available = true;
            registerShutdownHook();
            BlackAnninsDrone.LOGGER.info("Spout 发送器已加载: {}", dll);
        } catch (Throwable t) {
            BlackAnninsDrone.LOGGER.warn("Spout 初始化失败: {}", t.toString());
            available = false;
        }
    }

    /**
     * 纹理必须为已翻转的成品帧；分辨率变化时自动重建发送器（免重启即时生效）。
     *
     * @param hostFboId 承载该纹理的帧缓冲 ID（SendTexture 的 hostFbo 契约：Spout 从它拷贝；
     *                  传 0 时部分回退路径会改读"当前绑定的帧缓冲"，在推流管线中恰为
     *                  main 而非 saveTarget，导致 OSD 等纹理内改动丢失）
     */
    public static void send(int textureId, int hostFboId, int width, int height) {
        if (!available || textureId <= 0 || spout == NULL) {
            return;
        }
        String name = ClientConfig.SPOUT_SENDER_NAME.get();
        try {
            if (!name.equals(senderName)) {
                // 名称变化：释放旧发送端 → 设置新名称（之后的 SendTexture 按新名自动重建）
                callRelease();
                senderActive = false;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    callSetSenderName(MemoryUtil.memAddress(stack.UTF8(name)));
                }
                BlackAnninsDrone.LOGGER.info("Spout 发送器名称: {}", name);
                senderName = name;
                senderWidth = 0;
                senderHeight = 0;
            }
            if (senderWidth != width || senderHeight != height) {
                if (senderWidth != 0 && senderHeight != 0) {
                    BlackAnninsDrone.LOGGER.info("Spout 输出分辨率切换: {}x{} -> {}x{}", senderWidth, senderHeight, width, height);
                }
                senderWidth = width;
                senderHeight = height;
                // 输出分辨率写入日志：OBS 的 Spout 源需按此尺寸（或重新添加源）才能满屏；
                // 画面按窗口宽高比居中，四周多余区域为全透明（spoutKeepAspect）
                BlackAnninsDrone.LOGGER.info("Spout 输出分辨率已就绪: {}x{}（发送器 {}）", width, height, name);
            }
            // SendTexture 会按 SetSenderName 设置的名称与传入尺寸自动创建/更新发送端
            callSendTexture(textureId, hostFboId, width, height, false);
            senderActive = true;
        } catch (Throwable t) {
            BlackAnninsDrone.LOGGER.warn("Spout 发送失败: {}", t.toString());
            // 本会话停止重试；发送端仍由 senderActive 跟踪，release() 时会被真正释放
            available = false;
        }
    }

    /**
     * 释放发送端并复位初始化门闩。
     *
     * <p>available 已为 false（如发送失败后）时仍必须执行：此时 native 侧发送端可能仍被
     * 注册（OBS 侧源还在），跳过 ReleaseSender 会造成 native 资源泄漏。
     * 复位 attempted 后，关闭再打开推流可重新初始化，无需重启游戏。</p>
     */
    public static void release() {
        if (spout != NULL && releaseSenderFn != NULL && senderActive) {
            try {
                callRelease();
            } catch (Throwable ignored) {
            }
        }
        senderActive = false;
        senderName = "";
        senderWidth = 0;
        senderHeight = 0;
        attempted = false;
    }

    /**
     * 注册 JVM 关闭钩子：正常退出（窗口关闭/游戏内退出）时注销 Spout 发送端名。
     * 若进程被强制终止而未注销，共享注册表中的残留名会让 Spout 在下次启动时
     * 另建 "名字_1" 副本，OBS 侧则留下定格的旧源。
     */
    private static void registerShutdownHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(SpoutSender::release, "BlackAnninDrone-SpoutCleanup"));
    }

    /** SetSenderName(this, name)：虚表槽位 0。先命名，之后所有发送函数按此名称创建/更新发送端 */
    private static void callSetSenderName(long namePtr) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer types = stack.mallocPointer(2);
            types.put(ffi_type_pointer.address());
            types.put(ffi_type_pointer.address());
            types.flip();

            FFICIF cif = FFICIF.malloc(stack);
            if (ffi_prep_cif(cif, FFI_DEFAULT_ABI, ffi_type_void, types) != FFI_OK) {
                throw new IllegalStateException("ffi_prep_cif SetSenderName 失败");
            }

            PointerBuffer args = stack.mallocPointer(2);
            args.put(argPtr(stack, spout));
            args.put(argPtr(stack, namePtr));
            args.flip();

            ffi_call(cif, setSenderNameFn, null, args);
        }
    }

    /** ReleaseSender(this, dwMsec)：虚表槽位 2 */
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

    /** SendTexture(this, texId, target, w, h, invert, hostFbo)：虚表槽位 4，纹理所在 FBO 一并传入 */
    private static void callSendTexture(int textureId, int hostFbo, int width, int height, boolean invert) {
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
            args.put(argInt(stack, GL11.GL_TEXTURE_2D));
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
