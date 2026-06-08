package com.onlyti.rtkrouter.desktop.serial

import com.onlyti.rtkrouter.desktop.platform.Platform
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/**
 * Serial device permission helpers. Linux needs dialout/pkexec; Windows COM ports need no extra step.
 */
object SerialPermission {
    class PermissionException(val devicePath: String, message: String) : Exception(message)

    fun needsPermissionFix(): Boolean = Platform.isLinux

    fun isPermissionError(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase()
        return msg.contains("permission denied") ||
            msg.contains("access denied") ||
            msg.contains("error 13") ||
            t is PermissionException
    }

    fun canReadWrite(devicePath: String): Boolean {
        if (devicePath.isBlank()) return false
        if (Platform.isWindows) return true
        return linuxCanReadWrite(devicePath)
    }

    private fun linuxCanReadWrite(devicePath: String): Boolean {
        val f = File(devicePath)
        if (!f.exists()) return false
        return try {
            Files.getPosixFilePermissions(f.toPath()).let { perms ->
                val user = System.getProperty("user.name")
                val owner = Files.getOwner(f.toPath()).toString()
                val ownerRw = owner == user &&
                    PosixFilePermission.OWNER_READ in perms &&
                    PosixFilePermission.OWNER_WRITE in perms
                val worldRw = PosixFilePermission.OTHERS_READ in perms &&
                    PosixFilePermission.OTHERS_WRITE in perms
                val groupRw = PosixFilePermission.GROUP_READ in perms &&
                    PosixFilePermission.GROUP_WRITE in perms
                ownerRw || worldRw || groupRw
            }
        } catch (_: UnsupportedOperationException) {
            f.canRead() && f.canWrite()
        } catch (_: Throwable) {
            false
        }
    }

    /** pkexec shows the system polkit password dialog (Linux only). */
    fun fixWithPkexec(devicePath: String): Result<String> {
        if (!Platform.isLinux) return Result.failure(Exception("not supported on this OS"))
        return runCommand(listOf("pkexec", "chmod", "a+rw", devicePath), timeoutSec = 120)
    }

    /** In-app sudo password prompt fallback when pkexec is unavailable (Linux only). */
    fun fixWithSudo(devicePath: String, password: CharArray): Result<String> {
        if (!Platform.isLinux) return Result.failure(Exception("not supported on this OS"))
        val proc = ProcessBuilder("sudo", "-S", "chmod", "a+rw", devicePath)
            .redirectErrorStream(true)
            .start()
        return try {
            proc.outputStream.bufferedWriter().use { w ->
                w.write(password.concatToString())
                w.newLine()
                w.flush()
            }
            password.fill('\u0000')
            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                proc.destroyForcibly()
                Result.failure(Exception("sudo timed out"))
            } else if (proc.exitValue() == 0) {
                Result.success(out.ifBlank { "permissions updated" })
            } else {
                Result.failure(Exception(out.ifBlank { "sudo failed (exit ${proc.exitValue()})" }))
            }
        } catch (t: Throwable) {
            password.fill('\u0000')
            Result.failure(t)
        }
    }

    /** Permanent fix: add current user to dialout (Linux only, requires re-login). */
    fun addUserToDialoutPkexec(): Result<String> {
        if (!Platform.isLinux) return Result.failure(Exception("not supported on this OS"))
        val user = System.getProperty("user.name")
        return runCommand(
            listOf("pkexec", "usermod", "-aG", "dialout", user),
            timeoutSec = 120,
        ).map { "added $user to dialout — log out and back in" }
    }

    fun hasPkexec(): Boolean {
        if (!Platform.isLinux) return false
        return runCommand(listOf("which", "pkexec"), timeoutSec = 5).isSuccess
    }

    private fun runCommand(cmd: List<String>, timeoutSec: Long): Result<String> {
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                proc.destroyForcibly()
                Result.failure(Exception("command timed out: ${cmd.joinToString(" ")}"))
            } else if (proc.exitValue() == 0) {
                Result.success(out)
            } else {
                Result.failure(Exception(out.ifBlank { "exit ${proc.exitValue()}: ${cmd.joinToString(" ")}" }))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
