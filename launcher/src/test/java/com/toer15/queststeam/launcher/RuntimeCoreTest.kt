package com.toer15.queststeam.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class RuntimeCoreTest {
    private val pkg = "com.qssteam"
    private val to = RuntimeCore.replacementPrefix(pkg)

    @Test fun replacementHasSameLength() {
        assertEquals("/data/data/com.qssteam//", to)
        assertEquals(RuntimeCore.FROM_PREFIX.length, to.length)
    }

    @Test fun streamingPatchHandlesChunkBoundaries() {
        val from = RuntimeCore.FROM_PREFIX.toByteArray()
        for (offset in listOf(0, 1, 65_530, 65_535, 65_536, 65_540, 131_070)) {
            val data = ByteArray(200_000) { 'x'.code.toByte() }
            System.arraycopy(from, 0, data, offset, from.size)
            System.arraycopy(from, 0, data, 199_000, from.size)
            val out = ByteArrayOutputStream()
            RuntimeCore.copyPatched(ByteArrayInputStream(data), out, from, to.toByteArray())
            val s = String(out.toByteArray(), Charsets.ISO_8859_1)
            assertEquals(data.size, s.length)
            assertFalse("offset $offset", s.contains(RuntimeCore.FROM_PREFIX))
            assertEquals(2, Regex(Regex.escape(to)).findAll(s).count())
        }
    }

    /** Full extraction against the real Winlator APK: pass -Dwinlator.apk=/path/to/Winlator_11.2.apk */
    @Test fun extractRealWinlatorApk() {
        val path = System.getProperty("winlator.apk").orEmpty()
        assumeTrue("set -Dwinlator.apk to run", path.isNotEmpty())
        val apk = File(path)
        assertTrue("APK must verify (size + SHA-256)", RuntimeCore.isValidApk(apk))
        val rootfs = Files.createTempDirectory("qss-rootfs").toFile()
        try {
            RuntimeCore.extractAll(apk, rootfs, pkg) { _, _ -> }
            val box64 = File(rootfs, "usr/local/bin/box64")
            assertTrue(box64.isFile); assertTrue(box64.canExecute())
            assertTrue(File(rootfs, "opt/wine/bin/wine").canExecute())
            assertTrue(File(rootfs, "usr/lib/ld-linux-aarch64.so.1").exists() || Files.isSymbolicLink(File(rootfs, "usr/lib/ld-linux-aarch64.so.1").toPath()))
            // ELF interpreter patched
            val bytes = String(box64.readBytes(), Charsets.ISO_8859_1)
            assertTrue(bytes.contains("/data/data/com.qssteam//files/rootfs/lib/ld-linux-aarch64.so.1"))
            assertFalse(bytes.contains("com.winlator"))
            // Wine prefix + patched z: symlink
            val z = File(rootfs, "home/xuser/.wine/dosdevices/z:").toPath()
            assertTrue(Files.isSymbolicLink(z))
            assertEquals("/data/data/com.qssteam/files/rootfs", Files.readSymbolicLink(z).toString()) // NIO normalizes the // padding
            assertTrue(File(rootfs, "home/xuser/.wine/drive_c/windows/system32").isDirectory)
            // Wine's own DLLs linked into the prefix (missing -> "could not load kernel32.dll", exit 53)
            for (dir in listOf("system32", "syswow64")) {
                val k32 = File(rootfs, "home/xuser/.wine/drive_c/windows/$dir/kernel32.dll")
                assertTrue("$dir/kernel32.dll", k32.exists())
            }
            // DXVK replaced Wine's d3d11 with a real file; Turnip installed
            val d3d11 = File(rootfs, "home/xuser/.wine/drive_c/windows/system32/d3d11.dll").toPath()
            assertFalse("DXVK d3d11.dll should be a real file", Files.isSymbolicLink(d3d11))
            assertTrue(File(rootfs, "usr/lib/libvulkan_freedreno.so").isFile)
            // No leftover hardcoded paths anywhere
            // Don't follow links: dosdevices/z: points at the real runtime path, which another test rewrites.
            val leftovers = rootfs.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .count { f -> String(f.readBytes(), Charsets.ISO_8859_1).contains("/data/data/com.winlator/") }
            assertEquals(0, leftovers)
            println("Extracted files: " + rootfs.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.count())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    /**
     * Runs the app's smoke test (`box64 wine cmd /c ver`) on the real runtime, installed at the app's
     * real path. Needs arm64 emulation (qemu-user binfmt) and a writable /data/data/com.qssteam/files:
     *   -Dwinlator.apk=... -Dwinlator.smoke=1
     */
    @Test fun smokeTestRealRuntime() {
        val path = System.getProperty("winlator.apk").orEmpty()
        assumeTrue("set -Dwinlator.apk and -Dwinlator.smoke=1", path.isNotEmpty() && System.getProperty("winlator.smoke") == "1")
        val rootfs = File("/data/data/$pkg/files/rootfs")
        rootfs.deleteRecursively(); rootfs.mkdirs()
        RuntimeCore.extractAll(File(path), rootfs, pkg) { _, _ -> }
        val r = rootfs.path
        val pb = ProcessBuilder("$r/usr/local/bin/box64", "$r/opt/wine/bin/wine", "cmd", "/c", "ver")
            .directory(File(r, "home/xuser/.wine")).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(RuntimeCore.wineEnvironment(r, null, "test"))
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        println(out)
        assertEquals("exit code (53 = Wine couldn't load kernel32.dll)", 0, code)
        assertTrue("Windows version printed", out.contains("Microsoft Windows"))
    }

    /** Real network download + verification: -Dwinlator.download=1 */
    @Test fun downloadAndVerify() {
        assumeTrue(System.getProperty("winlator.download") == "1")
        val dir = Files.createTempDirectory("qss-dl").toFile()
        val dest = File(dir, "Winlator_11.2.apk")
        // Simulate a corrupt partial left over from a bad resume: wrong bytes appended
        File(dest.path + ".part").writeBytes(ByteArray(1_000_000) { 0x41 })
        val f = RuntimeCore.downloadVerified(dest, ::println) { }
        assertEquals(RuntimeCore.WINLATOR_APK_SHA256, RuntimeCore.sha256(f))
        dir.deleteRecursively()
    }
}
