package com.scanner.utils.nextcloud

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.github.legend295.fingerprintscanner.R
import com.nextcloud.common.NextcloudClient
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.OwnCloudClientFactory
import com.owncloud.android.lib.common.OwnCloudCredentials
import com.owncloud.android.lib.common.OwnCloudCredentialsFactory
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener
import com.owncloud.android.lib.common.operations.OnRemoteOperationListener
import com.owncloud.android.lib.common.operations.RemoteOperation
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.resources.files.CreateFolderRemoteOperation
import com.owncloud.android.lib.resources.files.DownloadFileRemoteOperation
import com.owncloud.android.lib.resources.files.FileUtils
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.ReadFolderRemoteOperation
import com.owncloud.android.lib.resources.files.UploadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import java.io.File


internal class NextCloudWrapper(val context: Context) {
    private var mClient: OwnCloudClient? = null
    private var nextCloudClient: NextcloudClient? = null
    private val mHandler: Handler = Handler(Looper.getMainLooper())

    init {
        // Create client object to perform remote operations /remote.php/dav/files/coopvest
        mClient = OwnCloudClientFactory.createOwnCloudClient(
            Uri.parse("https://waxed.hsadvanced.technology"),
            context,
            true
        )
        mClient?.userId = "coopvest"

        setCredentials()
    }

    private fun setCredentials() {
        mClient?.credentials =
            OwnCloudCredentialsFactory.newBasicCredentials("coopvest", "AD33!#hdg^\$fgd22")
    }

    fun startFolderCreation(newFolderPath: String, callback: (RemoteOperationResult<*>?) -> Unit) {
        setCredentials()
        val createOperation =
            CreateFolderRemoteOperation("/$newFolderPath", false)
        createOperation.execute(
            mClient,
            { p0, p1 ->
                if (p0 is CreateFolderRemoteOperation) {
                    callback(p1)
                    if (p1?.isSuccess == false) {
                        Log.e(
                            NextCloudWrapper::class.java.simpleName,
                            "Error Msg - ${p1.httpPhrase}"
                        )
                        Log.e(
                            NextCloudWrapper::class.java.simpleName,
                            "Error Code - ${p1.httpCode}"
                        )
                    }
                }
            }, mHandler
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun startReadRootFolder(path: String, callback: (ArrayList<RemoteFile>?) -> Unit) {
        val refreshOperation =
            ReadFolderRemoteOperation("/$path")
        // root folder
        refreshOperation.execute(
            mClient,
            { p0, p1 ->
                if (p0 is ReadFolderRemoteOperation) {
                    if (p1?.isSuccess == true) {
                        try {
                            val files: ArrayList<RemoteFile> =
                                p1.data as ArrayList<RemoteFile>
                            callback(files)
                        } catch (e: Exception) {
                            callback(null)
                        }
                        // do your stuff here
                    }
                }
            }, mHandler
        )
    }

    fun readFilesFromFolder(path: String, callback: (ArrayList<RemoteFile>?) -> Unit) {
        val refreshOperation =
            ReadFileRemoteOperation("/$path/")
        // root folder
        refreshOperation.execute(
            mClient,
            { p0, p1 ->
                if (p0 is ReadFileRemoteOperation) {
                    if (p1?.isSuccess == true) {
                        try {
                            val files: ArrayList<RemoteFile> =
                                p1.data as ArrayList<RemoteFile>
                            callback(files)
                        } catch (e: Exception) {
                            callback(null)
                        }
                        // do your stuff here
                    }
                }
            }, mHandler
        )
    }

    fun startDownload(
        filePath: String,
        targetDirectory: File,
        dataTransferProgress: OnDatatransferProgressListener,
        listener: OnRemoteOperationListener
    ) {
        val downloadOperation =
            DownloadFileRemoteOperation("/$filePath", targetDirectory.absolutePath)
        downloadOperation.addDatatransferProgressListener(dataTransferProgress)
        downloadOperation.execute(mClient, listener, mHandler)
    }

    fun startUpload(
        fileToUpload: File,
        remotePath: String,
        mimeType: String,
        dataTransferProgress: OnDatatransferProgressListener,
        listener: OnRemoteOperationListener
    ) {
        val uploadOperation = UploadFileRemoteOperation(
            fileToUpload.absolutePath,
            "/$remotePath",
            mimeType,
            System.currentTimeMillis()
        )
        uploadOperation.addDataTransferProgressListener(dataTransferProgress)
        uploadOperation.execute(mClient, listener, mHandler)
    }

    /*  override fun onRemoteOperationFinish(p0: RemoteOperation<*>?, p1: RemoteOperationResult<*>?) {
          if (p0 is CreateFolderRemoteOperation) {
              if (p1?.isSuccess == true) {
                  // do your stuff here
              }
          }
      }*/
}