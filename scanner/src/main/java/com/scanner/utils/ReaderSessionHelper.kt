package com.scanner.utils

internal interface ReaderSessionHelper {
    fun onSessionChanges(readerStatus: ReaderStatus)
}