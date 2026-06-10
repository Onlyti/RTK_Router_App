package com.onlyti.rtkrouter.desktop.bridge

import java.io.File
import java.io.OutputStream

/**
 * Spawns the bundled rospy node (ros/rtcm_ros_pub.py) on START and pipes the relayed
 * RTCM3 byte stream to its stdin. The node connects to the running ROS master (inherited
 * ROS_MASTER_URI) and republishes each RTCM3 frame as rtcm_msgs/Message on the topic.
 *
 * rtk-router itself has no ROS dependency: this is only invoked in ROS connection mode, and
 * it merely launches an external `python3`. rospy (in the launched process) handles the ROS
 * protocol — master registration, TCPROS, message md5sum — so correctness does not rely on a
 * hand-rolled ROS implementation here. The app must run in a ROS-sourced environment so the
 * child `python3` finds rospy/rtcm_msgs and the master.
 */
class RosRtcmPublisher(
    private val topic: String,
    private val frameId: String,
    private val onStatus: (alive: Boolean, message: String) -> Unit,
) {
    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile var alive = false
        private set
    @Volatile var lastMessage = ""
        private set

    /** Launch the node. Returns null on success, or an error message on failure. */
    fun start(): String? {
        val script = extractScript() ?: return "rtcm_ros_pub.py 리소스를 찾을 수 없음"
        return try {
            val args = mutableListOf("python3", script.absolutePath, "_rtcm_topic:=$topic")
            if (frameId.isNotBlank()) args.add("_frame_id:=$frameId")
            val p = ProcessBuilder(args).redirectErrorStream(false).start()
            process = p
            stdin = p.outputStream
            alive = true
            Thread({ drainStderr(p) }, "ros-node-stderr").apply { isDaemon = true; start() }
            Thread({
                p.waitFor()
                alive = false
                val msg = "ROS 노드 종료 (exit ${p.exitValue()})"
                lastMessage = msg
                onStatus(false, msg)
            }, "ros-node-wait").apply { isDaemon = true; start() }
            null
        } catch (t: Throwable) {
            "python3 실행 실패 — ${t.message} (앱을 ROS source 된 환경에서 실행했는지 확인)"
        }
    }

    /** Pipe [len] bytes of [data] to the node's stdin. */
    fun write(data: ByteArray, len: Int) {
        val out = stdin ?: return
        try {
            out.write(data, 0, len)
            out.flush()
        } catch (_: Throwable) {
            // node likely exited; the wait thread reports it
        }
    }

    fun stop() {
        try { stdin?.close() } catch (_: Throwable) {}
        process?.destroy()
        process = null
        stdin = null
        alive = false
    }

    private fun drainStderr(p: Process) {
        try {
            p.errorStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) {
                    lastMessage = line
                    onStatus(alive, line)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun extractScript(): File? {
        val res = javaClass.getResourceAsStream("/ros/rtcm_ros_pub.py") ?: return null
        return try {
            val f = File.createTempFile("rtcm_ros_pub", ".py")
            f.deleteOnExit()
            res.use { input -> f.outputStream().use { input.copyTo(it) } }
            f
        } catch (_: Throwable) {
            null
        }
    }

    /** Result of the pre-flight ROS environment check. */
    sealed interface Preflight {
        data object Ok : Preflight
        /** python3/rospy not importable — app not launched from a ROS-sourced env. */
        data object NoRos : Preflight
        /** rospy ok but rtcm_msgs missing — offer `apt install ros-<distro>-rtcm-msgs`. */
        data object MissingRtcmMsgs : Preflight
        data class Error(val message: String) : Preflight
    }

    companion object {
        /** ROS distro from env, defaulting to noetic (the rodea target). */
        fun rosDistro(): String = System.getenv("ROS_DISTRO")?.takeIf { it.isNotBlank() } ?: "noetic"

        fun rtcmMsgsPackage(): String = "ros-${rosDistro()}-rtcm-msgs"

        /** Check that the spawned node will be able to import rospy + rtcm_msgs (same env). */
        fun preflight(): Preflight = try {
            val p = ProcessBuilder("python3", "-c", "import rospy, rtcm_msgs.msg")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() == 0) {
                Preflight.Ok
            } else {
                when {
                    "rtcm_msgs" in out -> Preflight.MissingRtcmMsgs
                    "rospy" in out -> Preflight.NoRos
                    else -> Preflight.Error(out.trim().take(300))
                }
            }
        } catch (_: Throwable) {
            Preflight.NoRos  // python3 not found -> ROS env not sourced
        }

        /** Install rtcm_msgs via pkexec (GUI auth prompt). Returns a user-facing result. */
        fun installRtcmMsgs(): Result<String> {
            val pkg = rtcmMsgsPackage()
            return try {
                val p = ProcessBuilder("pkexec", "apt-get", "install", "-y", pkg)
                    .redirectErrorStream(true)
                    .start()
                val out = p.inputStream.bufferedReader().readText()
                if (p.waitFor() == 0) {
                    Result.success("$pkg 설치 완료. START 를 다시 누르세요.")
                } else {
                    Result.failure(RuntimeException("설치 실패: ${out.trim().takeLast(300)}"))
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }
}
