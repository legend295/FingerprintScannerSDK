package com.scanner.utils.readers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.os.Process
import android.util.Base64
import android.util.Log
import com.nextbiometrics.biometrics.NBBiometricsContext
import com.nextbiometrics.biometrics.NBBiometricsExtractResult
import com.nextbiometrics.biometrics.NBBiometricsFingerPosition
import com.nextbiometrics.biometrics.NBBiometricsIdentifyResult
import com.nextbiometrics.biometrics.NBBiometricsSecurityLevel
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.biometrics.NBBiometricsTemplate
import com.nextbiometrics.biometrics.NBBiometricsTemplateType
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewEvent
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewListener
import com.nextbiometrics.devices.NBDevice
import com.nextbiometrics.devices.NBDeviceEncodeFormat
import com.nextbiometrics.devices.NBDeviceFingerPosition
import com.nextbiometrics.devices.NBDeviceImageQualityAlgorithm
import com.nextbiometrics.devices.NBDeviceScanFormatInfo
import com.nextbiometrics.devices.NBDeviceScanResult
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.nextbiometrics.devices.NBDeviceSecurityModel
import com.nextbiometrics.system.NextBiometricsException
import com.scanner.utils.KeyStore.decryptData
import com.scanner.utils.KeyStore.encryptData
import com.scanner.utils.enums.PreviewListenerType
import com.scanner.utils.enums.ScanningType
import com.scanner.utils.helper.FingerprintListener
import com.scanner.utils.helper.OnFileSavedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.IntBuffer
import java.text.SimpleDateFormat
import java.util.AbstractMap
import java.util.Date
import java.util.Locale


internal class FingerprintReader(
    private val context: Context,
    private val readerNo: Int

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
    private lateinit var scanningType: ScanningType
    private var bvnNumber: String = ""
    private var dirPath = ""
    private lateinit var fingerprintListener: FingerprintListener
    private lateinit var listener: OnFileSavedListener
    private lateinit var listOfTemplate: ArrayList<NBBiometricsTemplate>
    private var reader: NBDevice? = null

    //    private val DEFAULT_SPI_NAME = "/dev/spidev0.0"
//    private val DEFAULT_SYSFS_PATH = "/sys/class/gpio"
//    private val DEFAULT_SPICLK = 8000000
//    private val DEFAULT_AWAKE_PIN_NUMBER = 0
//    private val DEFAULT_RESET_PIN_NUMBER = 0
//    private val DEFAULT_CHIP_SELECT_PIN_NUMBER = 0
    private var FLAGS = 0
    private val bufferBytes: ByteArray = byteArrayOf()
    private var isScanAndExtractInProgress = false
    private var previewListener: PreviewListener = PreviewListener()
    var success = false

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

    fun setBvnNumber(bvnNumber: String) {
        this.bvnNumber = bvnNumber
        dirPath = context.filesDir.path + "/$bvnNumber/"
    }

    fun setScanningType(scanningType: ScanningType) {
        this.scanningType = scanningType
    }

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
//                        saveTemplates()
                        scan(compression, path)
//                        scanAndExtracts(compression, path)
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

        return try {
            timeStop = 0
            timeStart = timeStop
            quality = 0
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
//            identifyFingers()
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

    private fun createFileName(): String? {
        val defaultFilePattern = "yyyy-MM-dd-HH-mm-ss"
        val date = Date(System.currentTimeMillis())
        val format = SimpleDateFormat(defaultFilePattern, Locale.ENGLISH)
        return format.format(date)
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
            listener.onBitmapSaveSuccess(filePath, readerNo)
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
            listener.onSuccess(filePath, readerNo)
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::SaveImage -> Saved image to $filePath"
            )
            true
        } catch (e: java.lang.Exception) {
            listener.onFailure(e)
            Log.e(
                "WaxdPosLib",
                "FingerprintReader[" + readerNo + "]::SaveImage -> Exception: " + e.message
            )
            false
        }
    }

    private fun readAllBytes(fileName: String): ByteArray? {
        var ous: ByteArrayOutputStream? = null
        var ios: InputStream? = null
        try {
            val buffer = ByteArray(4096)
            ous = ByteArrayOutputStream()
            ios = FileInputStream(fileName)
            var read = 0
            while (ios.read(buffer).also { read = it } != -1) {
                ous.write(buffer, 0, read)
            }
        } finally {
            ous?.close()
            ios?.close()
        }
        return ous?.toByteArray()
    }

    @Throws(IOException::class)
    private fun loadTemplate(
        fileName: String,
        templateType: NBBiometricsTemplateType = NBBiometricsTemplateType.ISO,
        callback: (NBBiometricsTemplate?) -> Unit
    ) {
        try {
            val context = NBBiometricsContext(reader)
            println(
                String.format(
                    "Reading template from file %s (template type %s)...",
                    fileName,
                    templateType.toString()
                )
            )
            this.context.decryptData(fileName) {
                it?.let { array ->
                    Log.d(FingerprintReader::class.simpleName, "byte array size - ${array.size}")
//            val template = context.loadTemplate(templateType, readAllBytes(fileName))
                    val template = context.loadTemplate(templateType, array)
                    println("Template loaded successfully.")
                    callback(template)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            callback(null)
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

    fun getPreviewListener() = previewListener

    inner class PreviewListener : NBBiometricsScanPreviewListener {
        private var counter = 0
        private var sequence = 0
        var lastImage: ByteArray? = null
            private set
        var timeFDET: Long = 0
            private set
        var timeScanStart: Long = 0
            private set
        var timeScanEnd: Long = 0
            private set
        var timeOK: Long = 0
            private set
        var fdetScore = 0
            private set
        private var lastStatus = NBDeviceScanStatus.NONE
        var previewListenerType = PreviewListenerType.NONE


        fun reset() {
            showMessage("") // Placeholder for preview
            lastStatus = NBDeviceScanStatus.NONE
            previewListenerType = PreviewListenerType.NONE
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
                /*updateMessage(
                    String.format(
                        "PREVIEW #%d: Status: %s, Finger detect score: %d, Spoof Score: %d, image %d bytes",
                        ++counter, event.scanStatus.toString(), event.fingerDetectValue, spoofScore,
                        image?.size ?: 0
                    )
                )*/
            } else {
                if (lastStatus != event.scanStatus) {
                    lastStatus = event.scanStatus
                    fingerprintListener.onReaderStatusChange(
                        lastStatus,
                        readerNo,
                        previewListenerType
                    )
                }
                /*updateMessage(
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
                    ),
                    event.fingerDetectValue
                )*/
                previewStartTime = System.currentTimeMillis()
            }
        }
    }

    fun scanAndExtract(path: String): Boolean {
        return try {
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                //Hide Menu before scan and extract starts
                scanExtract(path)
            }.start()
            true
        } catch (e: Exception) {
            false
        }

    }

    fun startIdentification(): Boolean {
        return try {
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                //Hide Menu before scan and extract starts
                identification()
            }.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun scanExtract(path: String): Boolean {
        isScanAndExtractInProgress = true
        var context: NBBiometricsContext? = null
        try {
            if (reader != null) {
                context = NBBiometricsContext(reader)
                //Antispoof check
                val tmpSpoofThreshold = ANTISPOOF_THRESHOLD.toInt()
                if (scanningType == ScanningType.REGISTRATION) {
                    var extractResult: NBBiometricsExtractResult? = null
                    showMessage("")
                    showMessage("Extracting fingerprint template, please put your finger on sensor!")
                    previewListener.reset()
                    previewListener.previewListenerType = PreviewListenerType.EXTRACTION
                    timeStop = 0
                    timeStart = timeStop
                    quality = 0
                    success = false
//                    try {
                    timeStart = System.currentTimeMillis()
                    //Hide Menu before scan and extract starts.
                    isScanAndExtractInProgress = true
//                    if (isSpoofEnabled) enableSpoof()
                    //Enable Image preview for FAP20
                    //device.setParameter(410,1);
                    extractResult = context.extract(
                        NBBiometricsTemplateType.ISO,
                        NBBiometricsFingerPosition.UNKNOWN,
                        scanFormatInfo,
                        previewListener
                    )
                    timeStop = System.currentTimeMillis()
//                    } catch (ex: Exception) {
//                        ex.printStackTrace()
//                    }
                    if (extractResult?.status != NBBiometricsStatus.OK) {
                        fingerprintListener.extractionResult(extractResult?.status, readerNo)
                        return false
//                    throw Exception("Extraction failed, reason: " + extractResult?.status)
                    }

                    if (isSpoofEnabled && isValidSpoofScore && previewListener.getSpoofScore() <= tmpSpoofThreshold) {
                        return false
//                    throw Exception("Extraction failed, reason: $spoofCause")
                    }
                    showMessage("Extracted successfully!")
                    val template = extractResult.template

                    quality = NBDevice.GetImageQuality(
                        previewListener.lastImage,
                        scanFormatInfo!!.width,
                        scanFormatInfo!!.height,
                        500,
                        NBDeviceImageQualityAlgorithm.NFIQ
                    )

                    if (!saveImage(template.data, "wsq", path)) {
                        Log.e(
                            "WaxdPosLib",
                            "FingerprintReader[$readerNo]::Scan -> SaveImage WSQ FAILED"
                        )
                        success = false
                        return false
                    }

                    previewListener.lastImage?.let {
                        val bitmap = convertToBitmaps(scanFormatInfo, it)
                        if (!saveOnlyBitmap(bitmap, path)) {
                            Log.e(
                                "WaxdPosLib",
                                "FingerprintReader[$readerNo]::Scan -> SaveBitmap FAILED"
                            )
                            success = false
                            return false
                        }
                    }


                    showResultOnUiThread(
                        previewListener.lastImage, String.format(
                            "Last scan = %d msec, Image process = %d msec, Extract = %d msec, Total time = %d msec\nTemplate quality = %d, Last finger detect score = %d",
                            previewListener.timeScanEnd - previewListener.timeScanStart,
                            previewListener.timeOK - previewListener.timeScanEnd,
                            timeStop - previewListener.timeOK,
                            timeStop - previewListener.timeScanStart,
                            template.quality,
                            previewListener.fdetScore
                        ),
                        quality
                    )

                    template.saveTemplate(context)
                    context.dispose()
                    context = null
                }

                if (scanningType == ScanningType.VERIFICATION) {
                    /*// Verification
                    //
                    showMessage("")
                    showMessage("Verifying fingerprint, please put your finger on sensor!")
                    previewListener.reset()
                    context?.dispose()
                    context = null
                    context = NBBiometricsContext(reader)
                    timeStart = System.currentTimeMillis()
                    //Enable Image preview for FAP20
                    //reader.setParameter(410,1);
                    val verifyResult = context.verify(
                        NBBiometricsTemplateType.ISO,
                        NBBiometricsFingerPosition.UNKNOWN,
                        scanFormatInfo,
                        previewListener,
                        template,
                        NBBiometricsSecurityLevel.NORMAL
                    )
                    timeStop = System.currentTimeMillis()
                    if (verifyResult.status != NBBiometricsStatus.OK) {
                        throw Exception("Not verified, reason: " + verifyResult.status)
                    }
                    if (isSpoofEnabled && isValidSpoofScore && previewListener.getSpoofScore() <= tmpSpoofThreshold) {
                        throw Exception("Not verified, reason: $spoofCause")
                    }
                    showMessage("Verified successfully!")
                    showResultOnUiThread(
                        previewListener.lastImage, String.format(
                            "Last scan = %d msec, Image process = %d msec, Extract+Verify = %d msec, Total time = %d msec\nMatch score = %d, Last finger detect score = %d",
                            previewListener.timeScanEnd - previewListener.timeScanStart,
                            previewListener.timeOK - previewListener.timeScanEnd,
                            timeStop - previewListener.timeOK,
                            timeStop - previewListener.timeScanStart,
                            verifyResult.score,
                            previewListener.fdetScore
                        ),
                        quality
                    )*/
                    // Identification
                    //
                    showMessage("")
                    val file = File(dirPath)
                    Log.d(
                        "WaxdPosLib",
                        "FingerprintReader[$readerNo]::identifyFingers -> Adding templates to list"
                    )
                    val fileLists = file.listFiles()
                    Log.d(
                        "WaxdPosLib",
                        "FingerprintReader[$readerNo]::identifyFingers -> Files found in directory - ${fileLists?.size}"
                    )
                    fileLists?.forEach {
                        loadTemplate(it.path) { template ->
                            template?.let { it1 -> listOfTemplate.add(it1) }
                        }
                        Thread.sleep(300)
                    }
                    if (listOfTemplate.isEmpty()) {
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintReader[$readerNo]::identifyFingers -> result = listOfTemplate is empty"
                        )
                        return false
                    }
                    val templates: ArrayList<AbstractMap.SimpleEntry<Any, NBBiometricsTemplate>> =
                        ArrayList()
                    Log.d(
                        "WaxdPosLib",
                        "FingerprintReader[$readerNo]::identifyFingers -> result = size of templates - ${listOfTemplate.size}"
                    )
                    for (i in 0 until listOfTemplate.size/* - 2*/) {
                        Log.d(
                            "WaxdPosLib",
                            "FingerprintReader[$readerNo]::Scan -> result - adding template to list"
                        )
                        templates.add(
                            AbstractMap.SimpleEntry<Any, NBBiometricsTemplate>(
                                "Template$i",
                                listOfTemplate[i]
                            )
                        )
                    }
                    // add more templates
                    showMessage("Identifying fingerprint, please put your finger on sensor!")
                    previewListener.reset()
                    previewListener.previewListenerType = PreviewListenerType.IDENTIFICATION
                    context?.dispose()
                    context = null
                    context = NBBiometricsContext(reader)
                    timeStart = System.currentTimeMillis()
                    //Enable Image preview for FAP20
                    //reader.setParameter(410,1);
                    val identifyResult = context.identify(
                        NBBiometricsTemplateType.ISO,
                        NBBiometricsFingerPosition.UNKNOWN,
                        scanFormatInfo,
                        previewListener,
                        templates.iterator(),
                        NBBiometricsSecurityLevel.NORMAL
                    )
                    timeStop = System.currentTimeMillis()
                    fingerprintListener.identificationResult(result = identifyResult, readerNo)
                    if (identifyResult.status != NBBiometricsStatus.OK) {
                        fingerprintListener.identificationStatus(
                            status = identifyResult.status,
                            readerNo
                        )
//                        throw Exception("Not identified, reason: " + identifyResult.status)
                    }
                    if (isSpoofEnabled && isValidSpoofScore && previewListener.getSpoofScore() <= tmpSpoofThreshold) {
                        throw Exception("Not identified, reason: $spoofCause")
                    }
                    showMessage("Identified successfully with fingerprint: " + identifyResult.templateId)
                    showResultOnUiThread(
                        previewListener.lastImage, String.format(
                            "Last scan = %d msec, Image process = %d msec, Extract+Identify = %d msec, Total time = %d msec\nMatch score = %d, Last finger detect score = %d",
                            previewListener.timeScanEnd - previewListener.timeScanStart,
                            previewListener.timeOK - previewListener.timeScanEnd,
                            timeStop - previewListener.timeOK,
                            timeStop - previewListener.timeScanStart,
                            identifyResult.score,
                            previewListener.fdetScore
                        ),
                        quality
                    )

                }
                success = true
            }
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
        } catch (ex: Throwable) {
            showMessage("ERROR: " + ex.message, true)
            ex.printStackTrace()
        }
        if (context != null) {
            context.dispose()
            context = null
        }
        fingerprintListener.onScanExtractCompleted(readerNo)
        return success
    }

    private fun NBBiometricsTemplate.saveTemplate(context: NBBiometricsContext) {
        // Save template
        val files = File(dirPath)
        if (files.isDirectory &&
            (files.listFiles().isNullOrEmpty() || (files.listFiles()?.size ?: 0) <= 1) ||
            !files.exists()
        ) {
            val binaryTemplate = context.saveTemplate(this)
            this@FingerprintReader.context.encryptData(binaryTemplate) {
                showMessage(
                    String.format(
                        "Extracted template length: %d bytes",
                        it.size
                    )
                )
                val base64Template = Base64.encodeToString(binaryTemplate, 0)
                showMessage("Extracted template: $base64Template")

                // Store template to file
//                val dirPath = this.context.filesDir.path + "/NBCapturedImages/"
                files.mkdirs()

                val filePath = dirPath + createFileName() + readerNo + "-ISO-Template.bin"
                showMessage("Saving ISO template to $filePath")
                val fos = FileOutputStream(filePath)
                fos.write(it)
                fos.close()


                listener.onTemplateSaveSuccess(filePath, readerNo)
            }
        }
    }

    private fun identification() {

    }

    private fun showMessage(message: String) {
        showMessage(message, false)
    }

    private fun showMessage(message: String?, isErrorMessage: Boolean) {
        fingerprintListener.showMessage(message, isErrorMessage, readerNo)
    }

    private fun updateMessage(message: String) {
        fingerprintListener.updateMessage(message, readerNo)
    }

    private fun showResultOnUiThread(image: ByteArray?, text: String, quality: Int) {
        showResult(image, text, quality)
    }

    private fun showResult(image: ByteArray?, text: String?, quality: Int) {
        if (image != null) {
            fingerprintListener.showResult(
                image,
                text,
                convertToBitmaps(scanFormatInfo, image),
                readerNo,
                quality
            )
        }
    }

    private fun convertToBitmaps(formatInfo: NBDeviceScanFormatInfo?, image: ByteArray): Bitmap {
        val buf = IntBuffer.allocate(image.size)
        for (pixel in image) {
            val grey = pixel.toInt() and 0x0ff
            buf.put(Color.argb(255, grey, grey, grey))
        }
        val bufferBytes = image
        return Bitmap.createBitmap(
            buf.array(),
            formatInfo!!.width,
            formatInfo.height,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun saveOnlyBitmap(bitmap: Bitmap, path: String): Boolean {
        return try {
            val filePath = "$path$readerNo.jpg"
            Log.d(
                "WaxdPosLib",
                "FingerprintReader[$readerNo]::SaveBitmap -> Saving Bitmap to $filePath"
            )
            val out = FileOutputStream(filePath)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()
            listener.onBitmapSaveSuccess(filePath, readerNo)
            true
        } catch (e: java.lang.Exception) {
            Log.e(
                "WaxdPosLib",
                "FingerprintReader[" + readerNo + "]::SaveBitmap -> Exception: " + e.message
            )
            false
        }
    }

    fun setListener(fingerprintListener: FingerprintListener) {
        this.fingerprintListener = fingerprintListener
    }

    fun setOnFileSaveListener(listener: OnFileSavedListener) {
        this.listener = listener
    }

    fun setListOfTemplate(listOfTemplate: ArrayList<NBBiometricsTemplate>) {
        this.listOfTemplate = listOfTemplate
    }

    fun setReader(reader: NBDevice) {
        this.reader = reader
    }


}