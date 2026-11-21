// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.snippets

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity started from the snippets panel's import button: opens the system file
 * picker (Storage Access Framework), imports the chosen Expandroid export, finishes. An IME
 * can't receive activity results itself, hence this trampoline.
 */
class SnippetImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // picker already open (e.g. after rotation)
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            // exports saved by other apps sometimes carry a generic type; still allow those
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        try {
            startActivityForResult(pick, REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Activity result API not used in this tiny trampoline")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST && resultCode == RESULT_OK && uri != null) {
            val msg = try {
                "Imported ${SnippetStore.import(this, uri)} snippets"
            } catch (e: kotlinx.serialization.SerializationException) {
                "Snippet import failed: not a valid JSON export" // parser messages quote file content
            } catch (e: Exception) {
                "Snippet import failed: ${e.message ?: e.javaClass.simpleName}"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val REQUEST = 1

        fun start(context: Context) {
            context.startActivity(Intent(context, SnippetImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
