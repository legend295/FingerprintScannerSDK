package com.scanner.utils.readers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.os.Process
import android.util.Log
import com.nextbiometrics.biometrics.NBBiometricsContext
import com.nextbiometrics.biometrics.NBBiometricsExtractResult
import com.nextbiometrics.biometrics.NBBiometricsFingerPosition
import com.nextbiometrics.biometrics.NBBiometricsSecurityLevel
import com.nextbiometrics.biometrics.NBBiometricsTemplate
import com.nextbiometrics.biometrics.NBBiometricsTemplateType
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewEvent
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewListener
import com.nextbiometrics.biometrics.jna.NBBiometricsTemplateIterator
import com.nextbiometrics.devices.NBDevice
import com.nextbiometrics.devices.NBDeviceEncodeFormat
import com.nextbiometrics.devices.NBDeviceFingerPosition
import com.nextbiometrics.devices.NBDeviceImageQualityAlgorithm
import com.nextbiometrics.devices.NBDeviceScanFormatInfo
import com.nextbiometrics.devices.NBDeviceScanResult
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.nextbiometrics.devices.NBDeviceSecurityModel
import com.nextbiometrics.system.NextBiometricsException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.IntBuffer
import java.util.AbstractMap

internal class FingerprintReader(
    val context: Context,
    private var reader: NBDevice?,
    private val readerNo: Int, private val listOfTemplate: ArrayList<NBBiometricsExtractResult>
) {
    private var scanFormatInfo: NBDeviceScanFormatInfo? = null
    private var serialNo = ""
    private var readerInfo = ""
    private var quality = 0
    private var detectLevel = 0
    private var timeStart: Long = 0
    private var timeStop: Long = 0
    private var init = false
    private var run = false
    var fingerDetected = false
    var fingerReleased = false
    private var fingerRead = false
    var previewStartTime: Long = 0
    var previewEndTime: Long = 0
    var done = false

    //    private val DEFAULT_SPI_NAME = "/dev/spidev0.0"
//    private val DEFAULT_SYSFS_PATH = "/sys/class/gpio"
//    private val DEFAULT_SPICLK = 8000000
//    private val DEFAULT_AWAKE_PIN_NUMBER = 0
//    private val DEFAULT_RESET_PIN_NUMBER = 0
//    private val DEFAULT_CHIP_SELECT_PIN_NUMBER = 0
    private var FLAGS = 0
    private val bufferBytes: ByteArray = byteArrayOf()
    private val isScanAndExtractInProgress = false
    private var previewListener: PreviewListener = PreviewListener()

    // Enable and Antispoof Support
//    private val CONFIGURE_ANTISPOOF = 108
//    private val CONFIGURE_ANTISPOOF_THRESHOLD = 109
//    private val ENABLE_ANTISPOOF = 1
//    private val DISABLE_ANTISPOOF = 0
//    private val MIN_ANTISPOOF_THRESHOLD = 0

    private var spoofScore = MAX_ANTISPOOF_THRESHOLD

    //    private val ANTISPOOF_THRESHOLD = DEFAULT_ANTISPOOF_THRESHOLD
    private val isSpoofEnabled = false
    private val isBackgroundRefreshEnabled = false
    private var isValidSpoofScore = false
    private val isAutoSaveEnabled = false
    private val spoofCause = "Spoof Detected."
    private val spoofThreshold = 0

    companion object {
        const val MAX_ANTISPOOF_THRESHOLD = 1000
        private const val DEFAULT_ANTISPOOF_THRESHOLD = "363"
        const val ANTISPOOF_THRESHOLD = DEFAULT_ANTISPOOF_THRESHOLD
        const val CONFIGURE_ANTISPOOF = 108
        const val CONFIGURE_ANTISPOOF_THRESHOLD = 109
        const val ENABLE_ANTISPOOF = 1
        const val DISABLE_ANTISPOOF = 0
        const val MIN_ANTISPOOF_THRESHOLD = 0
        const val DEFAULT_SPI_NAME = "/dev/spidev0.0"
        const val DEFAULT_SYSFS_PATH = "/sys/class/gpio"
        const val DEFAULT_SPICLK = 8000000
        var DEFAULT_AWAKE_PIN_NUMBER = 0
        var DEFAULT_RESET_PIN_NUMBER = 0
        var DEFAULT_CHIP_SELECT_PIN_NUMBER = 0
    }

    fun readerInfo() = readerInfo
    fun getQuality() = quality

    fun getFingerRead() = fingerRead

    fun getDetectLevel() = detectLevel

    fun init(): Boolean {
        Log.d("WaxdPosLib", "FingerPrintReader[$readerNo]::Init...")
        init = false
        return try {
            val info = JSONObject()
            val PIN_OFFSET = 902
            DEFAULT_AWAKE_PIN_NUMBER = PIN_OFFSET + 69
            DEFAULT_RESET_PIN_NUMBER = PIN_OFFSET + 12
            DEFAULT_CHIP_SELECT_PIN_NUMBER = PIN_OFFSET + 18
            FLAGS = NBDevice.DEVICE_CONNECT_TO_SPI_SKIP_GPIO_INIT_FLAG
            if (!openSession()) {
                Log.e(
                    "WaxdPosLib",
                    "FingerPrintReader[$readerNo]::Init -> openSession FAILED"
                )
                return false
            }
            if (reader == null || reader?.isSessionOpen != true) {
                Log.d(
                    "WaxdPosLib",
                    "FingerPrintReader[$readerNo]::Init -> Reader not ready"
                )
                return false
            }
            Log.d("WaxdPosLib", "FingerPrintReader[$readerNo]::Init -> Reader ready")
            if (reader?.capabilities?.requiresExternalCalibrationData == true) {
                Log.d(
                    "WaxdPosLib",
                    "FingerPrintReader[$readerNo]::Init -> Reader needs calibration..."
                )
                reader?.let {
                    solveCalibrationData(it)
                } ?: run {
                    Log.d(
                        "WaxdPosLib",
                        "FingerPrintReader[$readerNo]::Init -> Reader is null"
                    )
                    return false
                }
            }
            val scanFormats = reader?.supportedScanFormats
            if (scanFormats.isNullOrEmpty()) {
                Log.d(
                    "WaxdPosLib",
                    "FingerPrintReader[$readerNo]::Init -> scanFormats is null or empty"
                )
                return false
            }
            scanFormatInfo = scanFormats[0]
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> ScanFormat = " + scanFormatInfo!!.formatType.toString()
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> Height = " + scanFormatInfo!!.height
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> Width = " + scanFormatInfo!!.width
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> HorizontalResolution = " + scanFormatInfo!!.horizontalResolution
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> VerticalResolution = " + scanFormatInfo!!.verticalResolution
            )
            info.put("ScanFormat", scanFormatInfo!!.formatType.toString())
            info.put("Height", scanFormatInfo!!.height)
            info.put("Width", scanFormatInfo!!.width)
            info.put("HorizontalResolution", scanFormatInfo!!.horizontalResolution)
            info.put("VerticalResolution", scanFormatInfo!!.verticalResolution)
            serialNo = reader?.serialNumber ?: ""
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[$readerNo]::Init -> SN = $serialNo"
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> ID = " + reader?.id
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> Manufacturer = " + reader?.manufacturer
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> Product = " + reader?.product
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> Model = " + reader?.model
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init ->    Module SN = " + reader?.moduleSerialNumber
            )
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> Firmware Ver = " + reader?.firmwareVersion.toString()
            )
            info.put("SerialNo", serialNo)
            info.put("ID", reader?.id)
            info.put("Manufacturer", reader?.manufacturer)
            info.put("Product", reader?.product)
            info.put("Model", reader?.model)
            info.put("Module SN", reader?.moduleSerialNumber)
            info.put("Firmware Ver", reader?.firmwareVersion.toString())
            readerInfo = info.toString()
            if (isSpoofEnabled) {
                Log.d(
                    "WaxdPosLib",
                    "FingerPrintReader[$readerNo]::Init -> Enable Spoof ..."
                )
                enableSpoof()
            }
            init = true
            Log.d("WaxdPosLib", "FingerPrintReader[$readerNo]::Init -> Done")
            true
        } catch (e: Exception) {
            Log.e(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::Init -> Exception: " + e.message
            )
            e.printStackTrace()
            false
        }
    }

    fun detectFinger(level: Int): Boolean {
        Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::DetectFinger...")
        return try {
            detectLevel = reader!!.fingerDetectValue
            //Log.d("WaxdPosLib", "FingerPrintReader[" + readerNo + "]::Detect -> val = " + val);
            if (detectLevel >= level) {
                Log.d(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::WaitFingerDetect -> Finger Detected -> $detectLevel"
                )
                return true
            }
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::WaitFingerDetect -> Finger NOT Detected -> $detectLevel"
            )
            false
        } catch (e: java.lang.Exception) {
            Log.e(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::DetectCard -> Exception -> $e"
            )
            false
        }
    }

    fun readFinger(level: Int, compression: Double, path: String): Boolean {
        Log.d("WaxdPosLib", "FingerPrintReader[$readerNo]::ReadFinger...")
        if (!init) {
            Log.e(
                "WaxdPosLib",
                "FingerPrintReader[$readerNo]::ReadFinger -> Not initialized"
            )
            return false
        }
        return try {
            Thread {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    done = false
                    fingerRead = if (detectFinger(level)) {
                        Log.d(
                            "WaxdPosLib",
                            "FingerPrintReader[$readerNo]::ReadFinger -> Scan..."
                        )
                        scan(compression, path)
                    } else {
                        Log.e(
                            "WaxdPosLib",
                            "FingerPrintReader[$readerNo]::ReadFinger -> Scan Failed"
                        )
                        false
                    }
                    done = true
                    Log.d(
                        "WaxdPosLib",
                        "FingerPrintReader[$readerNo]::ReadFinger -> Done"
                    )
                } catch (e: java.lang.Exception) {
                    Log.d(
                        "WaxdPosLib",
                        "FingerPrintService::ReadFinger -> Exception: " + e.message
                    )
                }
            }.start()
            Log.d(
                "WaxdPosLib",
                "FingerPrintReader[$readerNo]::ReadFinger -> Read thread started"
            )
            true
        } catch (e: java.lang.Exception) {
            Log.e(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::ReadFinger -> Exception: " + e.message
            )
            false
        }
    }

    fun cancelTap(): Boolean {
        Log.d("WaxdPosLib", "FingerPrintReader[$readerNo]::CancelTap ...")
        return try {
            run = false
            true
        } catch (e: java.lang.Exception) {
            Log.e(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::CancelTap -> Exception " + e.message
            )
            false
        }
    }

    fun close() {
        Log.d("WaxdPosLib", "FingerPrintReader[$readerNo]::Close...")
        try {
            run = false
            Thread.sleep(10)
            if (reader != null) {
                reader?.dispose()
                reader = null
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    fun enableLowPowerMode() {
        try {
            val context = NBBiometricsContext(reader)
            try {
                context.cancelOperation()
                reader?.lowPowerMode()
                reader?.reset()
            } catch (ex: NextBiometricsException) {
                Log.e(
                    "WaxdPosLib",
                    "FingerPrintReader[$readerNo]::enableLowPowerMode Low power mode enabled"
                )
            }
        } catch (ex: NextBiometricsException) {
            Log.e(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::enableLowPowerMode NEXT Biometrics SDK error EXCEPTION--> " + ex.localizedMessage + "Exception code --> " + ex.code
            )
        } catch (ex: Throwable) {
            Log.e(
                "WaxdPosLib",
                "FingerPrintReader[" + readerNo + "]::enableLowPowerMode EXCEPTION--> " + ex.localizedMessage
            )
            ex.printStackTrace()
        }
    }

    fun isSessionOpen(): Boolean {
        return reader?.isSessionOpen ?: false
    }

    private fun openSession(): Boolean {
        Log.d("WaxdPosLib", "FingerPrintReader[$readerNo]::openSession...")
        if (reader != null && reader?.isSessionOpen != true) {
            val cakId = "DefaultCAKKey1\u0000".toByteArray()
            val cak = byteArrayOf(
                0x05.toByte(),
                0x4B.toByte(),
                0x38.toByte(),
                0x3A.toByte(),
                0xCF.toByte(),
                0x5B.toByte(),
                0xB8.toByte(),
                0x01.toByte(),
                0xDC.toByte(),
                0xBB.toByte(),
                0x85.toByte(),
                0xB4.toByte(),
                0x47.toByte(),
                0xFF.toByte(),
                0xF0.toByte(),
                0x79.toByte(),
                0x77.toByte(),
                0x90.toByte(),
                0x90.toByte(),
                0x81.toByte(),
                0x51.toByte(),
                0x42.toByte(),
                0xC1.toByte(),
                0xBF.toByte(),
                0xF6.toByte(),
                0xD1.toByte(),
                0x66.toByte(),
                0x65.toByte(),
                0x0A.toByte(),
                0x66.toByte(),
                0x34.toByte(),
                0x11.toByte()
            )
            val cdkId = "Application Lock\u0000".toByteArray()
            val cdk = byteArrayOf(
                0x6B.toByte(),
                0xC5.toByte(),
                0x51.toByte(),
                0xD1.toByte(),
                0x12.toByte(),
                0xF7.toByte(),
                0xE3.toByte(),
                0x42.toByte(),
                0xBD.toByte(),
                0xDC.toByte(),
                0xFB.toByte(),
                0x5D.toByte(),
                0x79.toByte(),
                0x4E.toByte(),
                0x5A.toByte(),
                0xD6.toByte(),
                0x54.toByte(),
                0xD1.toByte(),
                0xC9.toByte(),
                0x90.toByte(),
                0x28.toByte(),
                0x05.toByte(),
                0xCF.toByte(),
                0x5E.toByte(),
                0x4C.toByte(),
                0x83.toByte(),
                0x63.toByte(),
                0xFB.toByte(),
                0xC2.toByte(),
                0x3C.toByte(),
                0xF6.toByte(),
                0xAB.toByte()
            )
            val defaultAuthKey1Id = "AUTH1\u0000".toByteArray()
            val defaultAuthKey1 = byteArrayOf(
                0xDA.toByte(),
                0x2E.toByte(),
                0x35.toByte(),
                0xB6.toByte(),
                0xCB.toByte(),
                0x96.toByte(),
                0x2B.toByte(),
                0x5F.toByte(),
                0x9F.toByte(),
                0x34.toByte(),
                0x1F.toByte(),
                0xD1.toByte(),
                0x47.toByte(),
                0x41.toByte(),
                0xA0.toByte(),
                0x4D.toByte(),
                0xA4.toByte(),
                0x09.toByte(),
                0xCE.toByte(),
                0xE8.toByte(),
                0x35.toByte(),
                0x48.toByte(),
                0x3C.toByte(),
                0x60.toByte(),
                0xFB.toByte(),
                0x13.toByte(),
                0x91.toByte(),
                0xE0.toByte(),
                0x9E.toByte(),
                0x95.toByte(),
                0xB2.toByte(),
                0x7F.toByte()
            )
            when (NBDeviceSecurityModel.get((reader?.capabilities?.securityModel ?: 0).toInt())) {
                NBDeviceSecurityModel.Model65200CakOnly -> {
                    reader?.openSession(cakId, cak)
                }

                NBDeviceSecurityModel.Model65200CakCdk -> {
                    try {
                        reader?.openSession(cdkId, cdk)
                        reader?.SetBlobParameter(NBDevice.BLOB_PARAMETER_SET_CDK, null)
                        reader?.closeSession()
                    } catch (ex: RuntimeException) {
                        Log.e(
                            "WaxdPosLib",
                            "FingerPrintReader[" + readerNo + "]::openSession -> RuntimeException " + ex.message
                        )
                        return false
                    }
                    reader?.openSession(cakId, cak)
                    reader?.SetBlobParameter(NBDevice.BLOB_PARAMETER_SET_CDK, cdk)
                    reader?.closeSession()
                    reader?.openSession(cdkId, cdk)
                }

                NBDeviceSecurityModel.Model65100 -> {
                    reader?.openSession(defaultAuthKey1Id, defaultAuthKey1)
                }

                NBDeviceSecurityModel.ModelNone -> {}
                null -> {

                }
            }
        }
        Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::openSession -> Done")
        return true
    }

    private fun scan(compression: Double, path: String): Boolean {
        Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::Scan...")
        val scanResult: NBDeviceScanResult?
        var extractResult: NBBiometricsExtractResult? = null
        return try {
            timeStop = 0
            timeStart = timeStop
            quality = 0
            val nbContext = NBBiometricsContext(reader)
            try {
                timeStart = System.currentTimeMillis()
                Log.d(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::Scan -> timeStart = $timeStart"
                )
                scanResult = reader?.scan()
                timeStop = System.currentTimeMillis()
                Log.d(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::Scan -> timeStop = $timeStop"
                )
                /*extractResult = nbContext.extract(
                    NBBiometricsTemplateType.ISO,
                    NBBiometricsFingerPosition.UNKNOWN,
                    scanFormatInfo,
                    previewListener
                )
                Log.d(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::Scan -> result = Extract Result - ${extractResult?.status}"
                )
                listOfTemplate.add(extractResult)
                if (listOfTemplate.size > 5) {
                    val otherTemplate =
                        ArrayList<AbstractMap.SimpleEntry<Any, NBBiometricsTemplate>>()
                    listOfTemplate.forEach {
                        otherTemplate.add(AbstractMap.SimpleEntry(it.hashCode(), it.template))
                    }
                    val result = nbContext.identify(
                        extractResult?.template,
                        otherTemplate.iterator(),
                        NBBiometricsSecurityLevel.NORMAL
                    )
                    Log.d(
                        "WaxdPosLib",
                        "FingerprintReader[$readerNo]::Scan -> result = Score - ${result.score}"
                    )
                }

                Log.d(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::Scan -> result = listOfTemplate - ${listOfTemplate.size}"
                )*/
            } catch (e: java.lang.Exception) {
                Log.e(
                    "WaxdPosLib",
                    "FingerprintReader[" + readerNo + "]::Scan -> Exception: " + e.message
                )
                return false
            }
            if (scanResult?.status != NBDeviceScanStatus.OK) {
                Log.e(
                    "WaxdPosLib",
                    "FingerprintReader[" + readerNo + "]::Scan -> Extraction failed, reason: " + scanResult?.status
                )
                throw java.lang.Exception("Extraction failed, reason: " + scanResult?.status)
            }

            val tmpSpoofThreshold = ANTISPOOF_THRESHOLD.toInt()
            if (isSpoofEnabled && isValidSpoofScore && spoofScore <= tmpSpoofThreshold) {
                Log.e(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::Scan -> Extraction failed, reason: $spoofCause"
                )
                throw java.lang.Exception("Extraction failed, reason: $spoofCause")
            }
            val image = scanResult.image
            quality = NBDevice.GetImageQuality(
                image,
                scanFormatInfo!!.width,
                scanFormatInfo!!.height,
                500,
                NBDeviceImageQualityAlgorithm.NFIQ
            )
            Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::Scan -> quality = $quality")
            Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::Scan -> Convert to WSQ ...")
            val wsqTemplate = reader?.ConvertImage(
                image,
                scanFormatInfo!!.width,
                scanFormatInfo!!.height,
                500,
                NBDeviceEncodeFormat.WSQ,
                compression.toFloat(),
                NBDeviceFingerPosition.Unknown,
                0
            )
            if (!saveImage(wsqTemplate, "wsq", path)) {
                Log.e(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::Scan -> SaveImage WSQ FAILED"
                )
                return false
            }
            if (!saveBitmap(image, path)) {
                Log.e(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::Scan -> SaveBitmap FAILED"
                )
            }
            Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::Scan -> Done")
            true
        } catch (e: NextBiometricsException) {
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[" + readerNo + "]::Scan -> Exception: NEXT Biometrics SDK error: " + e.message
            )
            e.printStackTrace()
            false
        } catch (e: Throwable) {
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[" + readerNo + "]::Scan -> Exception: " + e.message
            )
            e.printStackTrace()
            false
        }
    }

    private fun saveBitmap(image: ByteArray, path: String): Boolean {
        return try {
            val filePath = "$path$readerNo.jpg"
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::SaveBitmap -> Saving Bitmap to $filePath"
            )
            val templateBmp = convertToBitmap(scanFormatInfo!!, image)
            val out = FileOutputStream(filePath)
            templateBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()
            true
        } catch (e: java.lang.Exception) {
            Log.e(
                "WaxdPosLib",
                "FingerprintReader[" + readerNo + "]::SaveBitmap -> Exception: " + e.message
            )
            false
        }
    }


    private fun convertToBitmap(
        formatInfo: NBDeviceScanFormatInfo,
        image: ByteArray
    ): Bitmap {
        val buf = IntBuffer.allocate(image.size)
        for (pixel in image) {
            val grey = pixel.toInt() and 0x0ff
            buf.put(Color.argb(255, grey, grey, grey))
        }
        return Bitmap.createBitmap(
            buf.array(),
            formatInfo.width,
            formatInfo.height,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun saveImage(imageData: ByteArray?, ext: String, path: String): Boolean {
        Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::SaveImage...")
        return try {
            val filePath = "$path$readerNo.$ext"
            val files = File(path)
            files.mkdirs()
            val fos = FileOutputStream(filePath)
            fos.write(imageData)
            fos.close()
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::SaveImage -> Saved image to $filePath"
            )
            true
        } catch (e: java.lang.Exception) {
            Log.e(
                "WaxdPosLib",
                "FingerprintReader[" + readerNo + "]::SaveImage -> Exception: " + e.message
            )
            false
        }
    }

    @Throws(java.lang.Exception::class)
    private fun solveCalibrationData(device: NBDevice) {
        Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::solveCalibrationData...")
        val paths = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .toString() + "/NBData/" + device.serialNumber + "_calblob.bin"
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .toString() + "/NBData/"
        ).mkdirs()
        val file = File(paths)
        if (!file.exists()) {
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::solveCalibrationData -> Creating calibration data: $paths"
            )
            try {
                val data = device.GenerateCalibrationData()
                val fos = FileOutputStream(paths)
                fos.write(data)
                fos.close()
                Log.d(
                    "WaxdPosLib",
                    "FingerprintReader[$readerNo]::solveCalibrationData -> Calibration data created"
                )
            } catch (e: java.lang.Exception) {
                Log.e(
                    "WaxdPosLib",
                    "FingerprintReader[" + readerNo + "]::solveCalibrationData -> Exception: " + e.message
                )
            }
        }
        if (file.exists()) {
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::solveCalibrationData -> Calibration file exists"
            )
            val size = file.length().toInt()
            val bytes = ByteArray(size)
            try {
                val buf = BufferedInputStream(FileInputStream(file))
                buf.read(bytes, 0, bytes.size)
                buf.close()
            } catch (e: IOException) {
                Log.d(
                    "WaxdPosLib",
                    "FingerprintReader[" + readerNo + "]::solveCalibrationData -> Excetion reading file: " + e.message
                )
            }
            device.SetBlobParameter(NBDevice.BLOB_PARAMETER_CALIBRATION_DATA, bytes)
        } else {
            Log.e(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::solveCalibrationData -> Missing compensation data - $paths"
            )
            //throw new Exception("Missing compensation data - " + paths);
        }
        Log.d("WaxdPosLib", "FingerprintReader[$readerNo]::solveCalibrationData... Done")
    }

    private fun enableSpoof() {
        reader?.setParameter(CONFIGURE_ANTISPOOF.toLong(), ENABLE_ANTISPOOF)
        reader?.setParameter(CONFIGURE_ANTISPOOF_THRESHOLD.toLong(), spoofThreshold)
    }


    inner class PreviewListener : NBBiometricsScanPreviewListener {
        private var counter = 0
        private var sequence = 0
        var lastImage: ByteArray? = null
        var timeFDET: Long = 0
        var timeScanStart: Long = 0
        var timeScanEnd: Long = 0
        var timeOK: Long = 0
        var fdetScore = 0

        fun reset() {
//            showMessage("") // Placeholder for preview
            counter = 0
            sequence++
            lastImage = null
            timeScanStart = 0
            timeScanEnd = timeScanStart
            timeOK = timeScanEnd
            timeFDET = timeOK
            fdetScore = 0
            spoofScore = MAX_ANTISPOOF_THRESHOLD
        }

        fun getSpoofScore(): Int {
            return spoofScore
        }

        override fun preview(event: NBBiometricsScanPreviewEvent) {
            val image = event.image
            spoofScore = event.spoofScoreValue
            isValidSpoofScore = true
            if (spoofScore <= MIN_ANTISPOOF_THRESHOLD || spoofScore > MAX_ANTISPOOF_THRESHOLD) {
                spoofScore = MIN_ANTISPOOF_THRESHOLD
                isValidSpoofScore = false
            }
            if (isValidSpoofScore) {
                /* updateMessage(
                     String.format(
                         "PREVIEW #%d: Status: %s, Finger detect score: %d, Spoof Score: %d, image %d bytes",
                         ++counter, event.scanStatus.toString(), event.fingerDetectValue, spoofScore,
                         image?.size ?: 0
                     )
                 )*/
            } else {
                /* updateMessage(
                     String.format(
                         "PREVIEW #%d: Status: %s, Finger detect score: %d, image %d bytes",
                         ++counter,
                         event.scanStatus.toString(),
                         event.fingerDetectValue,
                         image?.size ?: 0
                     )
                 )*/
            }
            if (image != null) lastImage = image
            // Approx. time when finger was detected = last preview before operation that works with finger image
            if (event.scanStatus != NBDeviceScanStatus.BAD_QUALITY && event.scanStatus != NBDeviceScanStatus.BAD_SIZE && event.scanStatus != NBDeviceScanStatus.DONE && event.scanStatus != NBDeviceScanStatus.OK && event.scanStatus != NBDeviceScanStatus.KEEP_FINGER_ON_SENSOR && event.scanStatus != NBDeviceScanStatus.SPOOF && event.scanStatus != NBDeviceScanStatus.SPOOF_DETECTED && event.scanStatus != NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING && event.scanStatus != NBDeviceScanStatus.CANCELED) {
                timeFDET = System.currentTimeMillis()
            }
            if (event.scanStatus == NBDeviceScanStatus.DONE || event.scanStatus == NBDeviceScanStatus.OK || event.scanStatus == NBDeviceScanStatus.CANCELED || event.scanStatus == NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING || event.scanStatus == NBDeviceScanStatus.SPOOF_DETECTED) {
                // Approx. time when scan was finished = time of the first event OK, DONE, CANCELED or WAIT_FOR_DATA_PROCESSING
                if (timeScanEnd == 0L) timeScanEnd = System.currentTimeMillis()
            } else {
                // Last scan start = time of any event just before OK, DONE, CANCELED or WAIT_FOR_DATA_PROCESSING
                timeScanStart = System.currentTimeMillis()
            }
            // Time when scan was completed
            if (event.scanStatus == NBDeviceScanStatus.OK || event.scanStatus == NBDeviceScanStatus.DONE || event.scanStatus == NBDeviceScanStatus.CANCELED || event.scanStatus == NBDeviceScanStatus.SPOOF_DETECTED) {
                timeOK = System.currentTimeMillis()
                fdetScore = event.fingerDetectValue
            }
            if (previewListener.lastImage != null && (reader.toString()
                    .contains("65210") || reader.toString().contains("65200"))
            ) {
                if (previewStartTime == 0L) {
                    previewStartTime = System.currentTimeMillis()
                }
                previewEndTime = System.currentTimeMillis()
                /*showResultOnUiThread(
                    previewListener.lastImage,
                    String.format(
                        "Preview scan time = %d msec,\n Finger detect score = %d",
                        previewEndTime - previewStartTime,
                        event.fingerDetectValue
                    )
                )*/
                previewStartTime = System.currentTimeMillis()
            }
        }
    }

}