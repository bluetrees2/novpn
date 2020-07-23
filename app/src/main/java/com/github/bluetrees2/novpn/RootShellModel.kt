package com.github.bluetrees2.novpn

import android.os.SystemClock.uptimeMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.lang.IllegalArgumentException
import java.util.concurrent.TimeoutException

data class CommandResult(val returnCode: Int, val stdout: String, val stderr: String) {
    override fun toString(): String =
        "{ returnCode: $returnCode, stdout: \"${stdout.trim()}\", stderr: \"${stderr.trim()}\" }"
}

class RootError : Exception()

class RootShellModel {
    private var shell: Process? = null
    private var stdoutStream: BufferedReader? = null
    private var stderrStream: BufferedReader? = null
    private var stdinStream: OutputStreamWriter? = null

    companion object {
        val instance = RootShellModel()

        private const val END_MARKER = "___com.github.bluetrees2.novpn.RootShellModel___"
    }

    private val mutex = Mutex()

    suspend fun runCmd(cmd: String, timeoutSeconds: Int = 15) : CommandResult {
        val cmd2 = cmd.trim()
        if (cmd2 == "")
            throw IllegalArgumentException("Empty command")

        return mutex.withLock {
            Log.d("Root", "{ command: \"$cmd2\" }")
            withContext(Dispatchers.IO) {
                try {
                    if (shell == null || shell!!.hasExited()) {
                        try {
                            shell = Runtime.getRuntime().exec("su")
                        } catch (e: IOException) {
                            throw RootError()
                        }

                        stdoutStream =
                            InputStreamReader(shell!!.inputStream, "UTF-8").buffered()
                        stderrStream =
                            InputStreamReader(shell!!.errorStream, "UTF-8").buffered()
                        stdinStream = OutputStreamWriter(shell!!.outputStream, "UTF-8")
                    }

                    try {
                        stdinStream!!.write("$cmd; echo $END_MARKER $?\n")
                        stdinStream!!.flush()
                    } catch (e: IOException) {
                        throw RootError()
                    }

                    val stdoutBuilder = StringBuilder()
                    val stderrBuilder = StringBuilder()
                    var returnCode = 0

                    val endMarkerRegex = Regex("""$END_MARKER [^\n]*\n$""")

                    val buf = CharArray(1024)
                    var done = false
                    val timeStart = uptimeMillis()
                    while (true) {
                        if (shell!!.hasExited())
                            throw RootError()
                        while (stdoutStream!!.ready()) {
                            val size: Int = stdoutStream!!.read(buf, 0, buf.size)
                            if (size == -1)
                                throw RootError()
                            if (size == 0)
                                break
                            val s = String(buf.slice(0 until size).toCharArray())
                            stdoutBuilder.append(s)
                        }
                        if (endMarkerRegex.find(stdoutBuilder) != null) {
                            done = true
                            val i = stdoutBuilder.lastIndexOf(END_MARKER)
                            returnCode = stdoutBuilder
                                .slice(i + END_MARKER.length + 1 until stdoutBuilder.length)
                                .toString()
                                .trim()
                                .toInt()
                            stdoutBuilder.delete(i, stdoutBuilder.length)
                        }
                        while (stderrStream!!.ready()) {
                            val size: Int = stderrStream!!.read(buf, 0, buf.size)
                            if (size == -1)
                                throw RootError()
                            if (size == 0)
                                break
                            val s2 = String(buf.slice(0 until size).toCharArray())
                            stderrBuilder.append(s2)
                        }
                        if (done)
                            break
                        if (uptimeMillis() - timeStart >= timeoutSeconds * 1000)
                            throw TimeoutException()
                        delay(10)
                    }

                    CommandResult(
                        returnCode,
                        stdoutBuilder.toString(),
                        stderrBuilder.toString()
                    ).also { result ->
                        Log.d("Root", "{ result: \"$result\" }")
                    }
                } catch (e: Throwable) {
                    try { shell?.destroy() } catch (_: Throwable) {}
                    shell = null
                    Log.d("Root", "{ result: \"$e\" }")
                    throw e
                }
            }
        }
    }

    @Suppress("unused")
    suspend fun shutdown() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                shell?.destroy()
                shell = null
            }
        }
    }
}
