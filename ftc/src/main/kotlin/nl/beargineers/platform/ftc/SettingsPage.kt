package nl.beargineers.platform.ftc

import nl.beargineers.platform.config.Config

/** The config page of the settings web pages. Plain HTML, since whoever opens it is on the robot's WiFi, offline. */
internal object SettingsPage {
    const val PATH = "/bear/config"
    const val RAW_PATH = "/bear/config.properties"
    const val RESET_PATH = "/bear/config/reset"
    const val MATCH_LOGS_PATH = "/bear/matchLogs"

    sealed interface Outcome {
        object Saved : Outcome
        object Reset : Outcome
        class Rejected(val problems: List<String>) : Outcome

        /** Saving went wrong on the robot, like the file not being written */
        class Failed(val error: Throwable) : Outcome
    }

    /** The page showing [text] in the editor: the config in effect, or what was posted if [outcome] rejected it */
    fun html(config: RobotConfig, outcome: Outcome? = null, text: String = config.text): String {
        val declarations = Config.declarations()
        val source = if (config.isEdited) "edited on the robot since this build was installed" else "bundled with the app"

        val outcomeHtml = when (outcome) {
            null -> ""
            Outcome.Saved -> """<p class="ok">Saved. The new values are in effect now and stay until the next install.</p>"""
            Outcome.Reset -> """<p class="ok">Back to the config bundled with the app.</p>"""
            is Outcome.Rejected -> """
                <div class="bad"><p>Not saved: some values can't be read. The robot keeps using its previous config.</p>
                ${list(outcome.problems)}</div>
            """.trimIndent()
            is Outcome.Failed -> """
                <div class="bad"><p>Not saved: the robot couldn't store it. It keeps using its previous config.</p>
                ${list(listOf(outcome.error.toString()))}</div>
            """.trimIndent()
        }

        val problemsHtml = if (declarations.isEmpty()) {
            """<p class="muted">Run an opmode to see the settings its robot has, and to have values checked.</p>"""
        } else {
            Config.problems().takeIf { it.isNotEmpty() }?.let { """<div class="warn">${list(it)}</div>""" } ?: ""
        }

        val resetHtml = if (!config.isEdited) "" else """
            <form method="post" action="$RESET_PATH" onsubmit="return confirm('Throw away the edits made on the robot?')">
              <button type="submit">Go back to the bundled config</button>
            </form>
        """.trimIndent()

        val installedHtml = config.appInstalledAt?.let {
            """<p class="muted">App installed ${installedAgo(it)}.</p>"""
        } ?: ""

        val robotHtml = config.activeRobot?.let {
            """<p>Robot configuration <b>${escape(it)}</b>: its sections of the config apply, after every robot's settings.</p>"""
        } ?: ""

        val settingsHtml = if (declarations.isEmpty()) "" else buildString {
            append("<h2>Settings</h2><div class=\"scroll\"><table><tr><th>Key</th><th>Type</th><th>Default</th><th>In config</th></tr>")
            for (declaration in declarations) {
                val default = declaration.defaultText?.let { "<code>${escape(it)}</code>" }
                    ?: if (declaration.isRequired) "<span class=\"warn\">required</span>" else "<span class=\"muted\">optional</span>"
                val value = Config.valueText(declaration.key)?.let { text ->
                    val section = Config.valueSection(declaration.key)?.let { " <span class=\"muted\">${escape(it)}</span>" } ?: ""
                    "<code>${escape(text)}</code>$section"
                } ?: ""
                append("<tr><td><code>${escape(declaration.key)}</code></td><td>${escape(declaration.type.name)}</td>")
                append("<td>$default</td><td>$value</td></tr>")
            }
            append("</table></div>")
        }

        // trimMargin rather than trimIndent: the inserted parts span several lines with their own indentation
        return """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |<meta charset="utf-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1">
            |<title>Robot config</title>
            |<style>
            |$STYLE
            |</style>
            |</head>
            |<body>
            |${nav(PATH)}
            |<h1>Robot config</h1>
            |<p class="muted">Using the config $source.
            |  <a href="$RAW_PATH" download="config.properties">Download it</a> to copy edits into the project.</p>
            |$robotHtml
            |$outcomeHtml
            |$problemsHtml
            |<form method="post" action="$PATH">
            |  <textarea name="settings" id="settings" spellcheck="false">$TEXT_PLACEHOLDER</textarea>
            |  <div class="row"><button type="submit">Save</button>
            |    <span id="unsaved" class="warn"${if (outcome is Outcome.Rejected) "" else " hidden"}>Not saved yet</span></div>
            |</form>
            |$resetHtml
            |$settingsHtml
            |$installedHtml
            |<script>
            |  document.getElementById('settings').addEventListener('input', function () {
            |    document.getElementById('unsaved').hidden = false;
            |  });
            |</script>
            |</body>
            |</html>
        """.trimMargin().replace(TEXT_PLACEHOLDER, escape(text)) // after trimMargin, which must not touch the config text
    }

    private const val TEXT_PLACEHOLDER = "\u0000config-text\u0000"

    /**
     * How long ago [installedAt] was, like "12 min ago". Relative, so it stays right when the Control Hub's clock
     * is off: it tells whether the app on the robot is the build just installed.
     */
    fun installedAgo(installedAt: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - installedAt) / 60_000
        return when {
            minutes < 1 -> "less than a minute ago"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** Links between the robot's pages, with the current one, at [path], not linked */
    fun nav(path: String): String = listOf(PATH to "Config", GraphsPage.PATH to "Graphs").joinToString(
        " · ", "<p class=\"muted\">", "</p>"
    ) { (target, title) -> if (target == path) "<strong>$title</strong>" else "<a href=\"$target\">$title</a>" }

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun list(items: List<String>) = items.joinToString("", "<ul>", "</ul>") { "<li>${escape(it)}</li>" }

    /** Styles shared by the robot's pages */
    internal val STYLE = """
        :root { color-scheme: light dark; --fg: #1d1d1f; --bg: #ffffff; --muted: #6e6e73; --line: #d2d2d7;
                --ok: #1a7f37; --bad: #c62828; --warn: #9a6700; --accent: #2f6fde; }
        @media (prefers-color-scheme: dark) {
          :root { --fg: #f5f5f7; --bg: #161618; --muted: #a1a1a6; --line: #3a3a3c;
                  --ok: #3fb950; --bad: #ff6b6b; --warn: #d29922; --accent: #6ea8ff; }
        }
        body { font: 15px/1.45 system-ui, sans-serif; color: var(--fg); background: var(--bg);
               max-width: 960px; margin: 0 auto; padding: 16px; }
        textarea { width: 100%; min-height: 24em; box-sizing: border-box; padding: 8px; color: inherit;
                   background: transparent; border: 1px solid var(--line); border-radius: 6px;
                   font: 13px/1.4 ui-monospace, Menlo, monospace; }
        code { font-family: ui-monospace, Menlo, monospace; }
        table { border-collapse: collapse; width: 100%; font-size: 13px; }
        th, td { text-align: left; padding: 4px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
        button { font: inherit; padding: 6px 14px; }
        .row { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin: 8px 0 16px; }
        .scroll { overflow-x: auto; }
        .muted { color: var(--muted); }
        .ok { color: var(--ok); }
        .bad { color: var(--bad); }
        .warn { color: var(--warn); }
    """.trimIndent()
}
