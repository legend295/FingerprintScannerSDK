package com.scanner.utils

interface ReaderSessionHelper {
    fun onSessionChanges(readerStatus: ReaderStatus)
}