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
            // No leftover hardcoded paths anywhere
            val leftovers = rootfs.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .count { f -> String(f.readBytes(), Charsets.ISO_8859_1).contains("/data/data/com.winlator/") }
            assertEquals(0, leftovers)
            println("Extracted files: " + rootfs.walkTopDown().count())
        } finally {
            rootfs.deleteRecursively()
        }
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
