package com.scanner.utils.helper

internal interface OnFileSavedListener {
    fun onSuccess(path: String, readerNo: Int)
    fun onBitmapSaveSuccess(path: String, readerNo: Int)

    fun onFailure(e: Exception)
}