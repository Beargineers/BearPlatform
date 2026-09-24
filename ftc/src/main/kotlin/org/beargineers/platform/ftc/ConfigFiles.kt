package org.beargineers.platform.ftc

import java.io.File

/**
 * Where the robot's config text comes from: the config bundled with the app, unless it was edited on the robot
 * since this build was installed. Installing a new build starts from its bundled config again, so copy edits worth
 * keeping into the project's config file before installing.
 *
 * @param directory where edits are kept
 * @param build identifies the installed build
 * @param bundledText reads the config bundled with the app
 */
class ConfigFiles(private val directory: File, build: String, private val bundledText: () -> String) {
    private val editsFile = File(directory, "$PREFIX$build$SUFFIX")

    /** Whether the config was edited on the robot since this build was installed */
    val hasEdits: Boolean get() = editsFile.exists()

    fun bundled(): String = bundledText()

    /** The config text to use: edits made on the robot for this build, or else the bundled config */
    fun read(): String = if (hasEdits) editsFile.readText() else bundled()

    /** Keeps [text] as this build's edits, and forgets edits made for earlier builds */
    fun save(text: String) {
        directory.mkdirs()
        deleteEdits(except = editsFile)
        // Write a separate file and swap it in, so losing power halfway can't leave a broken config behind
        val temporary = File(directory, editsFile.name + ".tmp")
        temporary.writeText(text)
        check(temporary.renameTo(editsFile)) { "Couldn't save the config to $editsFile" }
    }

    /** Goes back to the bundled config */
    fun discardEdits() = deleteEdits(except = null)

    private fun deleteEdits(except: File?) {
        directory.listFiles()
            ?.filter { it.name.startsWith(PREFIX) && it != except }
            ?.forEach { it.delete() }
    }

    private companion object {
        const val PREFIX = "config-"
        const val SUFFIX = ".properties"
    }
}
