package nl.beargineers.platform.ftc

import nl.beargineers.platform.input.RemoteCommands
import nl.beargineers.platform.telemetry.RobotTelemetry
import kotlin.time.DurationUnit

/** The telemetry of the opmode running now, or of the last one, for the graphs page */
internal object LiveTelemetry {
    @Volatile
    var current: RobotTelemetry? = null
        private set

    /** Counts opmode runs, so the page knows when to start its graphs over */
    @Volatile
    var run = 0
        private set

    @Volatile
    var running = false

    /** The commands the opmode offers; none are, once it stops */
    @Volatile
    var commands: RemoteCommands? = null
        private set

    fun publish(telemetry: RobotTelemetry, commands: RemoteCommands) {
        current = telemetry
        this.commands = commands
        run++
    }
}

/**
 * Live graphs of what code records with `graph(...)`, and the Driver Station lines, drawn in the browser.
 * No charting library: the page has to work offline, on the robot's WiFi.
 */
internal object GraphsPage {
    const val PATH = "/bear/graphs"
    const val DATA_PATH = "/bear/graphs/data"
    const val COMMAND_PATH = "/bear/command"

    /**
     * What the page polls for: samples after [since], the Driver Station lines, the commands offered, the current
     * time in seconds, and which opmode run it all belongs to.
     */
    fun data(
        telemetry: RobotTelemetry?,
        run: Int,
        running: Boolean,
        since: Long,
        commands: List<RemoteCommands.Status> = emptyList(),
    ): String {
        val samples = telemetry?.samplesSince(since)
        val time = if (running) telemetry?.now?.toDouble(DurationUnit.SECONDS) else samples?.lastTime
        return buildString {
            append("{\"run\":").append(run)
            append(",\"running\":").append(running)
            append(",\"seq\":").append(samples?.seq ?: 0)
            append(",\"time\":").append(rounded(time ?: 0.0))
            append(",\"lines\":[")
            telemetry?.lines.orEmpty().joinTo(this, ",") { json(it) }
            append("],\"commands\":[")
            commands.joinTo(this, ",") {
                "{\"name\":${json(it.name)},\"started\":${it.started},\"finished\":${it.finished}}"
            }
            append("],\"series\":{")
            samples?.series.orEmpty().entries.joinTo(this, ",") { (name, points) ->
                json(name) + ":[" + points.joinToString(",") { "[${rounded(it.time)},${it.value}]" } + "]"
            }
            append("}}")
        }
    }

    fun html(): String = """
        |<!DOCTYPE html>
        |<html lang="en">
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<title>Robot graphs</title>
        |<style>
        |${SettingsPage.STYLE}
        |.chart { margin: 10px 0; }
        |.chart-head { display: flex; justify-content: space-between; font-size: 13px; }
        |.chart-head .value { font-family: ui-monospace, Menlo, monospace; }
        |canvas { display: block; width: 100%; height: 110px; border: 1px solid var(--line); border-radius: 6px; }
        |#lines { font: 13px/1.4 ui-monospace, Menlo, monospace; white-space: pre-wrap; margin: 0; }
        |</style>
        |</head>
        |<body>
        |${SettingsPage.nav(PATH)}
        |<h1>Robot graphs</h1>
        |<div class="row">
        |  <button id="pause" type="button">Pause</button>
        |  <label>Last <select id="window"><option>5</option><option selected>20</option><option>60</option></select> s</label>
        |  <span id="status" class="muted"></span>
        |</div>
        |<div id="commands" class="row"></div>
        |<div id="charts"></div>
        |<h2>Driver Station</h2>
        |<pre id="lines"></pre>
        |<script>
        |$SCRIPT
        |</script>
        |</body>
        |</html>
    """.trimMargin()

    private fun rounded(seconds: Double) = Math.round(seconds * 1000) / 1000.0

    fun json(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private val SCRIPT = """
        const chartsEl = document.getElementById('charts');
        const linesEl = document.getElementById('lines');
        const statusEl = document.getElementById('status');
        const pauseEl = document.getElementById('pause');
        const windowEl = document.getElementById('window');
        const commandsEl = document.getElementById('commands');
        let commandNames = '';
        const charts = new Map();
        const HINT = 'Values show up here as code records them with graph("name", value).';
        let since = 0, run = null, now = 0, paused = false;

        pauseEl.onclick = () => {
          paused = !paused;
          pauseEl.textContent = paused ? 'Resume' : 'Pause';
        };

        function chart(name) {
          let c = charts.get(name);
          if (!c) {
            if (charts.size === 0) chartsEl.textContent = '';
            const box = document.createElement('div');
            box.className = 'chart';
            const head = document.createElement('div');
            head.className = 'chart-head';
            const title = document.createElement('span');
            title.textContent = name;
            const value = document.createElement('span');
            value.className = 'value';
            head.append(title, value);
            const canvas = document.createElement('canvas');
            box.append(head, canvas);
            chartsEl.append(box);
            c = { points: [], canvas, value };
            charts.set(name, c);
          }
          return c;
        }

        function format(v) {
          return String(Number(v.toPrecision(4)));
        }

        function draw() {
          const span = Number(windowEl.value);
          const style = getComputedStyle(document.body);
          const accent = style.getPropertyValue('--accent').trim();
          const line = style.getPropertyValue('--line').trim();
          const muted = style.getPropertyValue('--muted').trim();
          for (const c of charts.values()) {
            c.points = c.points.filter(p => p[0] >= now - 60);
            const points = c.points.filter(p => p[0] >= now - span);
            const last = c.points[c.points.length - 1];
            c.value.textContent = last ? format(last[1]) : '';

            const ratio = window.devicePixelRatio || 1;
            const w = c.canvas.clientWidth, h = c.canvas.clientHeight;
            c.canvas.width = w * ratio;
            c.canvas.height = h * ratio;
            const g = c.canvas.getContext('2d');
            g.scale(ratio, ratio);
            g.clearRect(0, 0, w, h);
            if (points.length === 0) continue;

            let min = Infinity, max = -Infinity;
            for (const p of points) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); }
            const top = max === min ? max + 1 : max + (max - min) * 0.1;
            const bottom = max === min ? min - 1 : min - (max - min) * 0.1;
            const x = t => (t - (now - span)) / span * w;
            const y = v => h - (v - bottom) / (top - bottom) * h;

            if (bottom < 0 && top > 0) {
              g.strokeStyle = line;
              g.beginPath(); g.moveTo(0, y(0)); g.lineTo(w, y(0)); g.stroke();
            }
            g.fillStyle = muted;
            g.font = '11px system-ui, sans-serif';
            g.fillText(format(max), 4, 12);
            g.fillText(format(min), 4, h - 4);

            g.strokeStyle = accent;
            g.lineWidth = 1.5;
            g.beginPath();
            points.forEach((p, i) => i ? g.lineTo(x(p[0]), y(p[1])) : g.moveTo(x(p[0]), y(p[1])));
            g.stroke();
          }
        }

        // Buttons for the commands the opmode offers; "…" while one runs
        function showCommands(commands) {
          const names = commands.map(c => c.name).join('\n');
          if (names !== commandNames) {
            commandNames = names;
            commandsEl.textContent = '';
            for (const c of commands) {
              const button = document.createElement('button');
              button.type = 'button';
              button.dataset.name = c.name;
              button.onclick = () => fetch('$COMMAND_PATH?name=' + encodeURIComponent(c.name), { method: 'POST' });
              commandsEl.append(button);
            }
          }
          commands.forEach((c, i) => {
            commandsEl.children[i].textContent = c.name + (c.finished < c.started ? ' …' : '');
          });
        }

        async function poll() {
          try {
            const response = await fetch('$DATA_PATH?since=' + since, { cache: 'no-store' });
            const data = await response.json();
            if (data.run !== run) {
              // Another opmode: start over, asking for all of its samples
              run = data.run;
              since = 0;
              charts.clear();
              chartsEl.innerHTML = '<p class="muted"></p>';
              chartsEl.firstChild.textContent = HINT;
            } else {
              since = data.seq;
              for (const [name, points] of Object.entries(data.series)) chart(name).points.push(...points);
            }
            now = data.time;
            linesEl.textContent = data.lines.join('\n');
            showCommands(data.running ? data.commands : []);
            statusEl.textContent = data.running ? '' :
              data.run === 0 ? 'No opmode has run yet' : 'No opmode running, showing the last one';
          } catch (e) {
            statusEl.textContent = 'Not connected to the robot';
          }
          if (!paused) draw();
          setTimeout(poll, 100);
        }

        poll();
    """.trimIndent()
}
