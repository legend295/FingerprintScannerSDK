package com.scanner.utils.helper

import com.scanner.utils.ReaderStatus

internal interface ReaderSessionHelper {
    fun onSessionChanges(readerStatus: ReaderStatus)
}