package com.scanner.utils.readers

import android.content.Context
import android.os.Looper
import android.os.Messenger
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.common.apiutil.powercontrol.PowerControl
import com.nextbiometrics.biometrics.NBBiometricsExtractResult
import com.nextbiometrics.devices.NBDevices
import com.scanner.utils.ReaderSessionHelper
import com.scanner.utils.ReaderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class FingerprintHelper(
    private val context: Context,
    private var sessionHelper: ReaderSessionHelper,
    private val listOfTemplate: ArrayList<NBBiometricsExtractResult>
) {

    private var reader = arrayOf<FingerprintReader?>(null, null)
    private var numReaders = 0
    private var terminate = false

    private var readerInfo = ""
    private var err = ""
    private var init = false
    private var started = false
    private var run = false
    private var released = true
    private var cancelled = true
    private var readerStatus: ReaderStatus = ReaderStatus.NONE

    private var timeout = 0
    private val defaultTimeout = 0
    private var level = 100
    private val defaultLevel = 100
    private var compression = 1.0
    private val defaultCompression = 1.0
    private var savePath: String? = null
    private val defaultSavePath = "/storage/emulated/0/DCIM/fpd/"

    init {
        sessionHelper.onSessionChanges(readerStatus)
    }

    fun init(): Boolean {
        Log.d("WaxdPosLib", "FingerprintService::Init...")
        val info = JSONObject()
        init = false
        return try {
            Log.d("WaxdPosLib", "FingerPrintService::Init -> Power USB OFF")
            PowerControl(context).usbPower(0)
            Thread.sleep(1000)
            Log.d("WaxdPosLib", "FingerPrintService::Init -> Power USB ON")
            PowerControl(context).usbPower(1)
            Thread.sleep(1000)
            Log.d("WaxdPosLib", "FingerPrintService::Init -> NBDevices.initialize...")
            //NBDevices.initialize(context.getApplicationContext());
            NBDevices.initialize(context)
            Log.d("WaxdPosLib", "FingerPrintService::Init -> NBDevices.initialize... Done")
            terminate = true
            Log.d("WaxdPosLib", "FingerPrintService::Init -> Waiting for USB devices ...")
            Thread.sleep(1000)
            //            int numDevicesFound = NBDevices.getDevices().length;
            var numDevicesFound = 0
            for (i in 0..49) {
                Thread.sleep(500)
                if (NBDevices.getDevices().size > 1) {
                    numDevicesFound = NBDevices.getDevices().size
                    break
                }
            }
            Log.d(
                "WaxdPosLib",
                "FingerPrintService::Init -> numDevicesFound = $numDevicesFound"
            )
            val devices = NBDevices.getDevices()
            Log.d("WaxdPosLib", "FingerPrintService::Init -> " + devices.size + " devices found")
            if (devices.isEmpty()) {
                err = "No fingerprint reader found"
                Log.d("WaxdPosLib", "FingerPrintService::Init -> No fingerprint reader found")
                readerInfo = "No finderprint reader"
                readerStatus = ReaderStatus.INIT_FAILED
                sessionHelper.onSessionChanges(readerStatus)
                return false
            }
            numReaders = 0
            for (i in 0..1) {
                reader[i] = FingerprintReader(context, devices[i], i, listOfTemplate)
                if (reader[i]?.init() != true) {
                    Log.d(
                        "WaxdPosLib",
                        "FingerPrintService::Init -> Init of reader$i failed"
                    )
                    info.put("reader[$i]", "INIT FAILED")
                } else {
                    Log.d(
                        "WaxdPosLib",
                        "FingerPrintService::Init -> Init of reader$i was successful"
                    )
                    info.put("reader[$i]", JSONObject(reader[i]?.readerInfo() ?: ""))
                    numReaders++
                }
            }
            readerInfo = info.toString()
            Log.d("WaxdPosLib", "FingerPrintService::Init -> readerInfo = $readerInfo")
            Log.d(
                "WaxdPosLib",
                "FingerPrintService::Init -> $numReaders readers initialized"
            )
            if (numReaders < 2) {
                err = "Not all readers initialized ($numReaders)"
                Log.d(
                    "WaxdPosLib",
                    "FingerPrintService::Init -> Not all readers initialized ($numReaders)"
                )
                readerStatus = ReaderStatus.INIT_FAILED
                sessionHelper.onSessionChanges(readerStatus)
                return false
            }
            Log.d("WaxdPosLib", "FingerPrintService::Init -> OK")
            Log.d("WaxdPosLib", "FingerprintService::onBind -> readerInfo = $readerInfo")
//            handler.sendMessage("SERVICE BOUND", readerInfo)
            init = true
            readerStatus = ReaderStatus.SERVICE_BOUND
            sessionHelper.onSessionChanges(readerStatus)
            true
        } catch (e: Exception) {
            readerStatus = ReaderStatus.INIT_FAILED
            sessionHelper.onSessionChanges(readerStatus)
            Log.e("WaxdPosLib", "FingerprintService::Init -> Exception: " + e.message)
            false
        } catch (e: ExceptionInInitializerError) {
            readerStatus = ReaderStatus.INIT_FAILED
            sessionHelper.onSessionChanges(readerStatus)
            Log.e("WaxdPosLib", "FingerprintService::Init -> Exception: " + e.message)
            false
        } catch (e: NoClassDefFoundError) {
            readerStatus = ReaderStatus.INIT_FAILED
            sessionHelper.onSessionChanges(readerStatus)
            Log.e("WaxdPosLib", "FingerprintService::Init -> Exception: " + e.message)
            false
        }
    }

    fun start(): Boolean {
        Log.d("WaxdPosLib", "FingerprintService::Start")
        return try {
            if (started) {
                Log.d("WaxdPosLib", "FingerprintService::Start -> already started")
                return true
            }
            if (init) {
                Log.d("WaxdPosLib", "FingerprintService::Start -> already inited")
                started = true
                return true
            }
            Thread {
                if (!init()) {
                    Log.e("WaxdPosLib", "FingerprintService::Start -> Init FAILED")
                    started = false
                    init = false
//                    handler.sendMessage("INIT FAILED")
                    readerStatus = ReaderStatus.INIT_FAILED
                    sessionHelper.onSessionChanges(readerStatus)
                }
            }.start()

            //Run();
            started = true
            true
        } catch (e: java.lang.Exception) {
            Log.d("WaxdPosLib", "FingerprintService::Start -> Exception " + e.message)
            false
        }
    }

    fun waitFingerRead(data: String?) {
        Log.d("WaxdPosLib", "FingerprintService::WaitFingerRead -> data = $data")
        level = defaultLevel
        timeout = defaultTimeout
        compression = defaultCompression
        savePath = defaultSavePath
        try {
            if (data != null) {
                //data = level, timeout, compression, path
                Log.d(
                    "WaxdPosLib",
                    "FingerprintService::WaitFingerRead -> splitting data into fields"
                )
                val fields = data.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val numFields = fields.size
                Log.d(
                    "WaxdPosLib",
                    "FingerprintService::WaitFingerRead -> splitting data into fields -> $numFields fields"
                )
                if (numFields > 0) {
                    val levelStr = fields[0]
                    if (levelStr != null) {
                        level = levelStr.toInt()
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> level = $level"
                        )
                    }
                }
                if (numFields > 1) {
                    val timeoutStr = fields[1]
                    if (timeoutStr != null) {
                        timeout = timeoutStr.toInt()
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> timeout = $timeout"
                        )
                    }
                }
                if (numFields > 2) {
                    val compressionStr = fields[2]
                    if (compressionStr != null) {
                        compression = compressionStr.toDouble()
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> compression = $compression"
                        )
                    }
                }
                if (numFields > 3) {
                    val savePathStr: String = fields[3]
                    if (savePathStr.isNotEmpty()) {
                        savePath = savePathStr
                        if (!savePath!!.endsWith("/")) savePath += "/"
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> savePathStr = $savePath"
                        )
                    }
                }
            }
        } catch (e: java.lang.Exception) {
            Log.e("WaxdPosLib", "FingerprintService::WaitFingerRead -> Exception " + e.message)
        }
        try {
            Thread {
                if (waitFingersDetect(level, timeout)) {
                    if (!cancelled) {
                        val data: String =
                            "${reader[0]!!.getDetectLevel()},${reader[1]!!.getDetectLevel()}"
//                        handler.sendMessage("FINGERS DETECTED", data)
                        readerStatus = ReaderStatus.FINGERS_DETECTED
                        sessionHelper.onSessionChanges(readerStatus)
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> Fingers Detected -> Reading ..."
                        )
                        readFingers(level, compression, savePath)
                    } else {
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> Fingers Detected -> TAP CANCELLED ..."
                        )
//                        handler.sendMessage("TAP CANCELLED")
                        readerStatus = ReaderStatus.TAP_CANCELLED
                        sessionHelper.onSessionChanges(readerStatus)
                    }
                } else {
                    if (!reader[0]!!.isSessionOpen()) {
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> Fingers Detection FAILED -> SESSION CLOSED"
                        )
//                        handler.sendMessage("SESSION CLOSED")
                        readerStatus = ReaderStatus.SESSION_CLOSED
                        sessionHelper.onSessionChanges(readerStatus)
                        started = false
                        init = false
                    } else if (!cancelled) {
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> Fingers Detection FAILED ..."
                        )
                        err = "FINGERS NOT DETECTED"
                        val data: String =
                            "${reader[0]!!.getDetectLevel()},${reader[1]!!.getDetectLevel()}"
//                        handler.sendMessage("FINGERS READ FAILED", data)
                        readerStatus = ReaderStatus.FINGERS_READ_FAILED
                        sessionHelper.onSessionChanges(readerStatus)
                    } else {
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintService::WaitFingerRead -> Fingers Detection FAILED -> TAP CANCELLED"
                        )
//                        handler.sendMessage("TAP CANCELLED")
                        readerStatus = ReaderStatus.TAP_CANCELLED
                        sessionHelper.onSessionChanges(readerStatus)
                    }
                }
                Log.d("WaxdPosLib", "FingerprintService::WaitFingerTap -> Done")
            }.start()
        } catch (e: java.lang.Exception) {
            Log.d("WaxdPosLib", "FingerprintService::WaitFingerTap -> Exception " + e.message)
        }
    }

    fun stop() {
        Log.d("WaxdPosLib", "FingerprintService::Stop")
        run = false
        started = false
        close()
    }

    fun setSavePath(path: String) {
        savePath = path
    }

    fun close() {
        Log.d("WaxdPosLib", "FingerPrintService::Close...")
        try {
            for (i in 0 until numReaders) {
                if (reader[i] != null) {
                    reader[i]?.close()
                    reader[i] = null
                }
            }
            if (terminate) {
                NBDevices.terminate()
            }
            PowerControl(context).usbPower(0)
        } catch (e: java.lang.Exception) {
            Log.e("WaxdPosLib", "FingerprintService::Close -> Exception: " + e.message)
        }
    }

    fun cancelTap() {
        try {
            Log.d("WaxdPosLib", "FingerprintService::CancelTap")
            cancelled = true
            reader[0]!!.cancelTap()
            reader[1]!!.cancelTap()
        } catch (e: java.lang.Exception) {
            Log.e("WaxdPosLib", "FingerprintService::CancelTap -> Exception " + e.message)
        }
    }

    fun enableLowPowerMode() {
        try {
            Log.d("WaxdPosLib", "FingerprintService::enableLowPowerMode")
            reader[0]?.enableLowPowerMode()
            reader[1]?.enableLowPowerMode()
        } catch (e: java.lang.Exception) {
            Log.e("WaxdPosLib", "FingerprintService::enableLowPowerMode -> Exception " + e.message)
        }
    }

    fun isSessionOpen() {
        if (!init) return
        val isFirstOpen: Boolean
        val isSecondOpen: Boolean
        try {
            Log.d("WaxdPosLib", "FingerprintService::isSessionOpen")
            isFirstOpen = reader[0]?.isSessionOpen() ?: false
            isSecondOpen = reader[1]?.isSessionOpen() ?: false
            if (isFirstOpen && isSecondOpen) {
                readerStatus = ReaderStatus.SESSION_OPEN
                sessionHelper.onSessionChanges(readerStatus)
            } else {
                init = false
                started = false
//                handler.sendMessage("SESSION CLOSED")
                readerStatus = ReaderStatus.SESSION_CLOSED
                sessionHelper.onSessionChanges(readerStatus)
                PowerControl(context).usbPower(0)
            }
        } catch (e: java.lang.Exception) {
            Log.e("WaxdPosLib", "FingerprintService::isSessionOpen -> Exception " + e.message)
//            handler.sendMessage("SESSION CLOSED")
            readerStatus = ReaderStatus.SESSION_CLOSED
            sessionHelper.onSessionChanges(readerStatus)
        }
    }

    private fun waitFingersDetect(level: Int, timeout: Int): Boolean {
        Log.d("WaxdPosLib", "FingerPrintService::WaitFingersDetect")
        cancelled = false
        return try {
            run = true
            val s = System.currentTimeMillis()
            while (run && !cancelled) {
                if (!reader[0]!!.isSessionOpen()) {
                    run = false
                    Log.d(
                        "WaxdPosLib",
                        "FingerPrintService::WaitFingersDetect -> Reader session is closed."
                    )
                    return false
                }
                if (reader[0]?.detectFinger(level) == true && reader[1]?.detectFinger(level) == true) {
                    Log.d("WaxdPosLib", "FingerPrintService::WaitFingersDetect -> Finger Detected")
                    run = false
                    return true
                }
                if (timeout > 0 && System.currentTimeMillis() - s > timeout * 1000) {
                    Log.d("WaxdPosLib", "FingerPrintService::WaitFingersDetect -> timeout")
                    run = false
                    return false
                }
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Log.d("WaxdPosLib", "FingerPrintService::WaitFingersDetect -> Interrupted")
                    run = false
                    return false
                }
                Log.d("WaxdPosLib", "FingerPrintService::WaitFingersDetect -> Still waiting ... ")
            }
            run = false
            Log.d("WaxdPosLib", "FingerPrintService::WaitFingersDetect -> Done")
            false
        } catch (e: java.lang.Exception) {
            Log.e("WaxdPosLib", "FingerPrintService::WaitFingersDetect -> Exception: " + e.message)
            false
        }
    }

    fun readFingers(level: Int, compression: Double, path: String?) {
        Log.d("WaxdPosLib", "FingerprintService::ReadFingers")
        released = false
        try {
            reader[0]!!.done = false
            reader[1]!!.done = false
            if (!reader[0]!!.readFinger(level, compression, path!!) || !reader[1]!!
                    .readFinger(level, compression, path)
            ) {
                err = "FINGERS READ FAILED"
                Log.d(
                    "WaxdPosLib",
                    "FingerprintService::WaitFingerTap FAILED -> err = $err"
                )
//                handler.sendMessage("FINGERS READ FAILED", err)
                readerStatus = ReaderStatus.FINGERS_READ_FAILED
                sessionHelper.onSessionChanges(readerStatus)
                return
            }
            Log.d("WaxdPosLib", "FingerPrintService::ReadFingers -> waiting for taps")
            try {
                while (!(reader[0]!!.done && reader[1]!!.done)) {
                    Thread.sleep(100)
                }
            } catch (e: InterruptedException) {
                Log.d("WaxdPosLib", "FingerPrintService::ReadFingers -> Interrupt Exception ...")
            }
            Log.d("WaxdPosLib", "FingerPrintService::ReadFingers -> while done ...")
            var fingersRead = 0
            if (reader[0]!!.getFingerRead()) fingersRead++
            if (reader[1]!!.getFingerRead()) fingersRead++
            if (fingersRead < 2) {
                err = "$fingersRead finger read"
                Log.d(
                    "WaxdPosLib",
                    "FingerprintService::ReadFingers -> FAILED -> err = $err"
                )
//                handler.sendMessage("FINGERS READ FAILED", err)
                readerStatus = ReaderStatus.FINGERS_READ_FAILED
                sessionHelper.onSessionChanges(readerStatus)
            } else {
                val data: String = "${reader[0]!!.getQuality()},${reader[1]!!.getQuality()}"
//                handler.sendMessage("FINGERS READ SUCCESS", data)
                readerStatus = ReaderStatus.FINGERS_READ_SUCCESS
                sessionHelper.onSessionChanges(readerStatus)
            }
            released = false
            Log.d("WaxdPosLib", "FingerPrintService::ReadFingers -> Done")
        } catch (e: java.lang.Exception) {
            Log.e("WaxdPosLib", "FingerPrintService::ReadFingers -> Exception: " + e.message)
        }
    }

    fun waitFingersRelease(): Boolean {
        Log.d("WaxdPosLib", "FingerPrintService::WaitFingersRelease")
        cancelled = false
        return try {
            val `val` = 0
            run = true
            val s = System.currentTimeMillis()
            while (run) {
                if (reader[0]?.detectFinger(level) != true && reader[1]?.detectFinger(level) != true) {
                    Log.d("WaxdPosLib", "FingerPrintService::WaitFingersRelease -> Finger Released")
                    run = false
//                    handler.sendMessage("FINGERS RELEASED")
                    readerStatus = ReaderStatus.FINGERS_RELEASED
                    sessionHelper.onSessionChanges(readerStatus)
                    return true
                }
                if (timeout > 0 && System.currentTimeMillis() - s > timeout * 1000) {
                    Log.d("WaxdPosLib", "FingerPrintService::WaitFingersRelease -> timeout")
                    run = false
                    return false
                }
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Log.d("WaxdPosLib", "FingerPrintService::WaitFingersRelease -> Interrupted")
                    run = false
                    return false
                }
                Log.d("WaxdPosLib", "FingerPrintService::WaitFingerRelease -> Still waiting ... ")
            }
            run = false
            Log.d("WaxdPosLib", "FingerPrintService::WaitFingerRelease -> Done")
            false
        } catch (e: java.lang.Exception) {
            Log.e("WaxdPosLib", "FingerPrintService::WaitFingerRelease -> Exception: " + e.message)
            false
        }
    }
}