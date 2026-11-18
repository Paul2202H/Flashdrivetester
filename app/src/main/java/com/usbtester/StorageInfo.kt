
package com.usbtester

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager

data class StorageVolumeInfo(
    val description: String,
    val state: String,
    val removable: Boolean,
    val primary: Boolean,
    val path: String?
)

object StorageInfo {

    fun list(context: Context): List<StorageVolumeInfo> {
        return try {
            val manager = context.getSystemService(
                Context.STORAGE_SERVICE
            ) as StorageManager

            manager.storageVolumes.map { volume ->
                StorageVolumeInfo(
                    description = volume.getDescription(context),
                    state = volume.state,
                    removable = volume.isRemovable,
                    primary = volume.isPrimary,
                    path = if (Build.VERSION.SDK_INT >= 30) {
                        volume.directory?.absolutePath
                    } else {
                        null
                    }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

