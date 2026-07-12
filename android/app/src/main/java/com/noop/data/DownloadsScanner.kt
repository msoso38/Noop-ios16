package com.noop.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Find user-facing imports on external storage (Downloads) without a picker —
 * critical for Fold usability when SAF is awkward across apps.
 */
object DownloadsScanner {
    data class Hits(
        val pcFiles: List<File>,
        val noopBakFiles: List<File>,
        val whoopCsvZips: List<File>,
    )

    fun scan(context: Context): Hits {
        val roots = linkedSetOf<File>()
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { roots.add(it) }
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { roots.add(it) }
        // Common OEM mirrors
        listOf("/sdcard/Download", "/storage/emulated/0/Download").forEach { roots.add(File(it)) }

        val pc = mutableListOf<File>()
        val bak = mutableListOf<File>()
        val zips = mutableListOf<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val n = f.name.lowercase()
                when {
                    n.endsWith(".pc") -> pc.add(f)
                    n.endsWith(".noopbak") || n.endsWith(".sqlite") -> bak.add(f)
                    n.endsWith(".zip") && (n.contains("noop") || n.contains("whoop") || n.contains("export")) ->
                        zips.add(f)
                }
            }
        }
        fun newest(a: List<File>) = a.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
        return Hits(newest(pc), newest(bak), newest(zips))
    }
}
