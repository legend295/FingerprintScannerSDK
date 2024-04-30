package com.scanner.utils.helper

import android.graphics.Bitmap
import com.nextbiometrics.biometrics.NBBiometricsIdentifyResult
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.scanner.utils.enums.PreviewListenerType

internal interface FingerprintListener {
    fun showResult(image: ByteArray?, text: String?, bitmap: Bitmap, readerNo: Int, quality: Int)

    //    fun showResultOnUiThread(image: ByteArray?, text: String)
    fun updateMessage(message: String, readerNo: Int)
    fun showMessage(message: String?, isErrorMessage: Boolean, readerNo: Int)

    fun onScanExtractCompleted(readerNo: Int)

    fun onReaderStatusChange(
        status: NBDeviceScanStatus?,
        readerNo: Int,
        previewListenerType: PreviewListenerType
    )

    fun extractionResult(status: NBBiometricsStatus?, readerNo: Int)

    fun identificationStatus(status: NBBiometricsStatus?, readerNo: Int)
    fun identificationResult(result: NBBiometricsIdentifyResult?, readerNo: Int)
}