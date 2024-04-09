package com.scanner.utils.helper

internal interface OnFileSavedListener {
    fun onSuccess(path: String)

    fun onFailure(e: Exception)
}