# BearPlatform

A reusable Kotlin platform for FTC robots from team Beargineers (#27628). It grew out of our DECODE season code,
and aims to make robot programming simpler for students: small composable pieces, real units instead of bare
numbers, and nothing tied to a particular season.

## Modules

| Module | What                                                                                     |
|--------|------------------------------------------------------------------------------------------|
| `core` | Pure Kotlin/JVM, no FTC SDK. Everything here runs in unit tests on a laptop.              |
| `ftc`  | Android library on top of the FTC SDK: opmodes, hardware, gamepads, robot web pages.     |

### core

- `units`: `Distance`, `Angle`, `Heading`, `LinearVelocity`, `AngularVelocity`. Durations are `kotlin.time.Duration`.
- `geometry`: `Point`, `Pose`, `RobotOffset`, `PoseVelocity`, `Field`, `Alliance`.

```kotlin
val speed = 2.tiles / 1.5.seconds                  // LinearVelocity
val start = field.tile("F6BL").withHeading(135.degrees)
val next = start + RobotOffset(forward = 30.cm, turn = 90.degrees)
val error = target.relativeTo(robotPose)           // forward / right / turn the robot still has to go
val turn = target.heading - robotPose.heading      // always the shortest turn, no normalizing needed
val blueStart = field.forAlliance(start, Alliance.BLUE)
```

- `component`: the robot is a tree of `Component`s with a `Robot` at the top:

```kotlin
class Shooter(parent: Component) : Component(parent) {  // named "shooter" after its class
    val latch = Latch(this)                             // path "shooter.latch"
    val kV by config(0.00036)                           // config key "shooter.kV"

    override fun update() { /* every loop, before coroutines run */ }

    suspend fun shoot() = takeOver("shoot") { /* stops any other shooter action */ }
}

class GammaRobot(host: RobotHost) : Robot(host) {
    val localizer = Localizer(this)                     // created first, so updated first
    val shooter = Shooter(this)
    val leftFlywheel = Flywheel(this, "left")           // same kind twice needs names
    val rightFlywheel = Flywheel(this, "right")
}
```

  Components `init()` before the first loop, `update()` every loop, and `stop()` at the end; parents before their
  parts, siblings in creation order. `warn("...")` reports a problem worth fixing, which the opmode shows at INIT.

- `runtime`: `LoopRuntime` runs coroutines on the robot loop, one step per `tick()`. Robot code is written as
  plain sequential `suspend` functions:

```kotlin
suspend fun GammaRobot.collectAndShoot() = withName("Collect and shoot") {
    race({ driveTo(SPIKE_1) }, { waitUntil { intake.isFull } })  // whichever happens first
    waitUntil(1.seconds) { shooter.isReady }                      // gives up after a second
    shooter.shoot()
}

// The label is just for logs: any new takeOver of the drive stops the action before it, whatever the target
suspend fun Drive.driveTo(target: Pose) = takeOver("driveTo $target") {
    while (!isAt(target)) {
        followTowards(target)
        nextTick()
    }
}
```

  `delay` and `withTimeout` work too; they measure time with the runtime's `TimeSource`, so tests control the clock.

- `config`: settings that change without rebuilding the code, declared right where they are used:

```kotlin
class Turret(parent: Component) : Component(parent) {
    val kP by config(0.02)                      // key: turret.kP
    val settleTime by config(300.milliseconds)
    val mode by config(TurretMode.FOLLOW)       // any enum works; a unique beginning like "FO" is enough
    val limit by optionalConfig<Angle>()        // null unless the config text sets it
}
```

  The config text uses the Java properties format, and values may carry units:

```properties
# Comments go on their own lines: text after a value becomes part of the value
turret.kP = 0.025
turret.settleTime = 250ms
# Tile codes need Config.field to be set to the season's Field
AutoPositions.NORTH_START = F6BL:135
# Plain numbers are cm for distances and degrees for angles
camera.forward = 4.5in
```

  Components use their path as the key prefix; other classes use their class name, or implement `ConfigScope`.
  `Config.load()` rejects text with unreadable values and keeps the old ones, and `Config.problems()` lists keys
  nothing uses, with "did you mean" hints for typos.

- `input`: buttons, triggers and sticks read once per loop, so all code in a loop sees the same presses. Button
  handlers are coroutines that live as long as the coroutine that set them up.

- `control`: `Pid` and `Feedforward`, with gains as config settings that can be tuned live, plus `Debouncer`,
  `Hysteresis` and `SlewRateLimiter`:

```kotlin
class Flywheel(parent: Component) : Component(parent) {
    val motor = Motor(this, "motor", ticksPerTurn = 28.0)
    val feedforward by config(Feedforward(kS = 0.05, kV = 0.00017))  // flywheel.feedforward = kS=0.05, kV=0.00017
    val gains by config(PidGains(p = 0.0005))                         // flywheel.gains = p=0.0005, i=0, d=0
    private val pid = Pid { gains }
    var targetRpm = 0.0

    override fun update() {
        motor.power = feedforward.power(targetRpm) + pid.update(targetRpm - motor.velocity.inRpm)
    }
}
```

- `telemetry`: components and opmodes `show("Angle", motor.position)` lines on the Driver Station, `graph("turn
  error", error)` values for the live graphs page, and `log("reached target")` messages. Once a second the Driver
  Station lines also go to the log (`telemetry.logInterval`), so match logs show what the drivers saw.

  Telemetry is cheap in fast loops. Lines are collected only every 100 ms (`telemetry.updateInterval`): in the loops
  between, `show()` does nothing and values are never turned into text. For a value that is costly to make, pass a
  block, which runs only when lines are collected: `show("Velocity") { "%.0f rpm".format(velocity.inRpm) }`. Graphs
  keep at most one sample per 10 ms of each value.

- `drive` and `localization`: the `Drivetrain` and `Localizer` components that teleop driving and path following use;
  the FTC implementations, `MecanumDrive` and `PinpointLocalizer`, are in `ftc` (below).

### ftc

```kotlin
@TeleOp
class Driving : CoroutineOpMode() {
    val robot = GammaRobot(this)

    override suspend fun CoroutineScope.onInit() { /* from INIT until START: menus, starting pose */ }

    override suspend fun CoroutineScope.onStart() {
        driver1.rightBumper.onPress { robot.goShoot() }        // pressing again starts it over
        driver1.a.onTap { robot.intake.toggle() }
        driver1.a.whileHeld(after = 300.milliseconds) { robot.intake.reverse() }  // cancelled on release
        driver2.rightTrigger.asButton(threshold = 0.1).onPress { robot.shootNow() }

        everyTick {
            val forward = driver1.leftStick.y.value          // positive when pushed forward
            ...
        }
    }
}
```

Every loop, `CoroutineOpMode` reads the gamepads and all hub sensors (in one bulk read), updates the robot's
components, runs coroutines and sends telemetry. A loop that takes less than `loop.minTime` (5 ms) sleeps for the rest:
faster than the sensors change only burns CPU and makes garbage. Handlers set up in `onInit` stop at START. The Driver Station's
first lines show loop time (*Loop: 2.4 ms average, 6.1 ms longest*) and the battery. Every loop's time is graphed as
`loop ms`: a loop longer than 10 ms always shows up there.

Motors and servos are components too, so their settings live in config under their parent's path:

```kotlin
class Turret(parent: Component) : Component(parent) {
    // goBILDA 435 RPM: 384.5 ticks per turn of its shaft, from goBILDA's product page. Settings like
    // turret.motor.direction, .brake, .ticksPerTurn and .gearRatio can be changed in config. keepZero: the turret
    // is centered by hand once, and autonomous and TeleOp share that zero.
    val motor = Motor(this, "motor", device = "turret", ticksPerTurn = 384.5, gearRatio = 121.0 / 16, keepZero = true)
    val pinpoint by hardware<GoBildaPinpointDriver>()  // any other device from the robot configuration

    override fun update() {
        val angle = motor.position                     // an Angle at the turret, after the gearing
        motor.power = 0.3                              // only sent to the hub when it changes
    }
}

class Shooter(parent: Component) : Component(parent) {
    val latch = PositionServo(this, "latch")
    val latchOpen by config(0.3)
    val latchClosed by config(0.65)

    fun open() { latch.position = latchOpen }          // only sent to the hub when it changes
}
```

`pwmMin` and `pwmMax` set a servo's pulse widths for devices that need other ones than the SDK's, like goBILDA's RGB
light (500 and 2500).

Digital sensors, like limit switches, touch sensors and beam breaks, work like gamepad buttons, and are read with
the gamepads at the start of every loop:

```kotlin
class Intake(parent: Component) : Component(parent) {
    val beam = DigitalInput(this, "beam", onWhenLow = true)   // most beam breaks pull their line low when broken
    var balls = 0

    suspend fun countBalls() = beam.onTurnOn { balls++ }      // also isOn, turnedOn, awaitOn(), whileOn { }
}
```

`intake.beam.onWhenLow` and `intake.beam.debounce` (a change counts only once it lasted that long) are config
settings. Digital inputs come with the hub's bulk read: the first one read in a loop costs one bulk read of its hub.

A `DistanceSensor` (REV's 2m sensor, or the distance of REV's color sensor) is read on the device reader's thread
every `readInterval` (50 ms), and the loop takes the newest reading at its start: `ball.distance` is a `Distance`,
or null when nothing is within `maxRange` (200 cm) or the sensor doesn't answer. REV's 2m sensor measures about every
33 ms, and a read waits for a new measurement, asking the sensor over I2C again and again: a shorter interval only keeps
the hubs' connection busy. Each read holds the connection for about 9 ms, so two sensors read every 50 ms take a
third of it: turn a sensor's `reading` off while nothing needs it (`shooterBall.reading = isShooting`). While off,
`distance` is null; turned back on, the sensor is read right away and `distance` stays null until that reading.
Turning distances into decisions is the component's job: a ball in once closer than 13 cm and out only once farther
than 17 is `Hysteresis(on = 13.0, off = 17.0)`.

With `velocityControl`, a motor whose encoder doesn't move for half a second under power is stopped, and the Driver
Station says why: without an encoder signal the hub would run it up to full power.

`position` and `velocity` need `ticksPerTurn`, and fail without it, telling what to set; motors whose encoders
aren't used need nothing. The motor type picked in the robot configuration isn't used for it, as it is wrong for most
current motors: the SDK's goBILDA type always has the 60 RPM model's numbers, and its REV UltraPlanetary type assumes
a 20:1 gearbox.

Unit tests use fake devices (`ftc/src/test/.../FakeHardware.kt`), since the SDK's own device lookup needs the
Control Hub.

#### Drivetrain

```kotlin
class GammaRobot(host: FtcHost) : Robot(host) {
    // Motors leftFront, rightFront, leftBack, rightBack in the robot configuration
    val drive = MecanumDrive(this)
}

// TeleOp
everyTick { robot.drive.driveWith(driver1.leftStick, driver1.rightStick.x) }
driver1.back.onPress { robot.drive.checkWheels() }   // each wheel in turn, to check directions and ports
```

`driveWith` drives robot-centric: the left stick moves, the right stick turns. When an action drives the robot, like
driving to a target, pushing a stick past halfway stops the action, and a thumb resting on a stick doesn't.
Code can also drive with powers relative to the robot, `drive.drive(forward = 0.5, right = 0.2, turn = 0.1)`, where
turn is counterclockwise like all angles. With `MecanumDrive(this, velocityControl = true)` the hub holds each wheel's
speed by its encoder: powers then ask for speeds, so even small ones move the robot, whatever the battery. It needs the
drive encoders plugged in: without one, the hub keeps raising that wheel's power, up to full. Settings: `drive.maxPower` scales everything down (0.3 for the first tries),
`drive.strafeScale` makes up for mecanum wheels slipping sideways, and each motor's `drive.leftFront.direction` etc.

`MecanumDrive` is a `Drivetrain`, which is what teleop driving and path following use. Other kinds, like tank or
swerve, would be other `Drivetrain`s: a tank drive can't strafe (`canStrafe` false) and ignores `right`.

#### Localization

```kotlin
class GammaRobot(host: FtcHost) : Robot(host) {
    val localizer = PinpointLocalizer(this)             // first, so it updates before anything drives by it
    val drive = MecanumDrive(this)
}

robot.localizer.pose = DecodeField.tile("A1BL").withHeading(0.degrees)   // the starting pose, in autonomous
val here = robot.localizer.pose                                           // every loop: x, y and heading

// Field-centric TeleOp: pushing forward drives away from the drivers, whichever way the robot faces
everyTick {
    robot.drive.driveWith(driver1.leftStick, driver1.rightStick.x,
        robot.localizer.pose.heading, DecodeField.driversFace(alliance))
}
```

`PinpointLocalizer` reads goBILDA's Pinpoint on the device reader's thread (below) every `localizer.readInterval`
(15 ms), so the loop never waits for it. Its settings, with goBILDA's signs:
`localizer.forwardPodLeft` and `localizer.strafePodForward` (required: where the pods are), `localizer.pods` (FOUR_BAR
or SWINGARM), the pod directions and `localizer.yawScalar`. They can be tuned live on the settings page. INIT
calibrates the Pinpoint's gyro with the robot standing still; the Driver Station shows the Pinpoint's problems, like a
pod not plugged in. The pose carries over from one opmode to the next (`Localizer.lastPose`), so TeleOp starts where
autonomous ended. Graphs: `x cm`, `y cm`, `heading deg`.

Field-centric driving needs the season's `Field` to say where the drivers stand: `redStation`, like
`FieldDirection.AUDIENCE_LEFT` in DECODE. Without a localizer, pass the heading the robot had when pointing away from
the drivers instead of `driversFace`.

#### Path following

```kotlin
class GammaRobot(host: FtcHost) : Robot(host) {
    val localizer = PinpointLocalizer(this)
    val drive = MecanumDrive(this)
    val follower = PathFollower(this, drive, localizer)
}

suspend fun GammaRobot.collect() {
    follower.driveTo(SPIKE_1)                                          // drives there and stops
    follower.drivePath(Waypoint(A), Waypoint(B, speed = 0.5), Waypoint(C))   // through A and B, stops at C
    follower.driveTo(localizer.pose + RobotOffset(forward = 30.cm))     // 30 cm ahead
}
```

`PathFollower` drives to a pose with three PIDs on how far the robot still has to go: forward, sideways (per cm) and
around (per degree). Their D terms use the velocity the localizer measures, so late readings don't make them jump.
Points on the way count as passed within `follower.passTolerance` (8 cm, 5°); where it stops, the robot has arrived
within `follower.tolerance` (2 cm, 1°) once it's slower than `follower.stoppedSpeed` and turning slower than
`follower.stoppedTurnSpeed`. A robot that gets no closer for
`follower.stallTime`, like one pushing against a wall, gives up on the point: `driveTo` returns false. Driving takes
over the drivetrain: a new drive action or the sticks stop it, and the motors stop with it. For the other alliance,
convert the poses with `field.forAlliance`.

Small powers don't move a robot against its wheels' friction, so until it is within tolerance the follower adds
friction power toward the target: `follower.frictionPower` forward (0.08), `frictionStrafePower` sideways (0.14, mecanum
wheels need more) and `frictionTurnPower` (0.1). Measure them by raising a power slowly until the robot moves.

The default gains were tuned on a 12 kg mecanum robot with `drive.nominalVoltage = 12`: 60 cm in 0.9 s, a quarter turn
in 0.7 s, within 1 cm and 1°. To tune another robot, offer drives as commands (below) and change
`follower.forwardGains`, `strafeGains` and `headingGains` live while watching the `forward error cm`, `strafe error cm`
and `heading error deg` graphs, next to the powers they turn into; the log says how each drive ended.

#### Hub time and the device reader

Every command to the hubs, a motor power, a servo position, a bulk read or a step of an I2C read, holds the hubs' one
connection until the hub answers, about 1.4 ms, and there is one connection for the Control Hub and an Expansion Hub
together. That, not the code, is what makes loops slow: a motor write costs about 1.6 ms, a Pinpoint read about 5.5 ms.

- Motors are only sent a power that differs from what the hub has by at least `<motor>.powerTolerance` (0.005), so a
  PID nudging the power every loop doesn't write every loop. Stopping and full power are always sent exactly.
- I2C sensors are read on the `DeviceReader`'s thread, one at a time, each at its own interval, so the loop doesn't
  wait for them. A component adds its read in `init()`: `(robot.host as FtcHost).deviceReader?.add(path,
  { readInterval }, { reading }) { latest = sensor.read() }`, keeping the result in a `@Volatile` field for
  `update()`; the read is skipped while the optional `{ reading }` is false. Every read takes connection time from
  everything else, so read as rarely as the robot can do with, and pause what nothing needs.
- When the reads take more than `reader.budget` percent of the time (50), the Driver Station says so while they do,
  naming the reads that take the most, and the log gets the first warning and any clearly worse one. Only what
  lasted three seconds counts: a slow read now and then, like a 2m sensor's first one, takes up to 300 ms. On Beta, the
  Pinpoint every 15 ms takes about 37%, and each distance sensor every 50 ms about 17%.
- `telemetry.profile = yes` graphs where each loop's time goes (`time <part> ms`: each top-level part's update,
  coroutines, motor writes and how many per loop, battery, telemetry), what each part allocates (`alloc <part> KB`),
  garbage collection, and how busy the device reader keeps the connection (`reader busy %`, and each read's time and
  rate).

#### Config on the robot

The app's config is `res/raw/config.properties` of the season app module. Every opmode loads it at INIT and checks
it against the settings its robot declares: a value that can't be read fails INIT with a list of all such values,
and other problems (like keys nothing uses) show on the Driver Station until START.

With the laptop on the robot's WiFi, `http://192.168.43.1:8080/bear/config` shows the config with every setting the
robot has, their defaults, and problems like typos in keys. Edits saved there take effect right away, even in a
running opmode, and stay on the robot until the next install; the page's *Download* link gets the edited file for
copying into the project. The page is served by the Control Hub's own web server, so it works with no opmode running.

`scripts/updateConfig.sh` sends a config file to the robot the same way, and `scripts/downloadMatchLogs.sh`
downloads the SDK's match logs. Over USB, run `adb forward tcp:8080 tcp:8080` and use `localhost:8080`
(`ROBOT=http://localhost:8080` for the scripts).

#### Graphs and battery

`http://192.168.43.1:8080/bear/graphs` draws everything code records with `graph(...)`, live, one chart per value,
and shows the Driver Station lines. It needs no internet, and shows the last opmode after it stops.

For testing and tuning, an opmode can offer commands, which show up as buttons on the graphs page:

```kotlin
override suspend fun CoroutineScope.onStart() {
    command("forward 60 cm") { robot.follower.driveTo(FORWARD) }   // offered until the opmode ends
}
```

`scripts/command.sh "forward 60 cm"` starts one from the laptop (`POST /bear/command?name=...`), and
`/bear/graphs/data` has the graphs, Driver Station lines and commands as JSON for tools: together they let a script
run a drive, read what it did, change gains with `scripts/updateConfig.sh`, and try again. Someone should be at the
Driver Station, ready to stop the robot.

Every opmode watches the battery: a `Battery: 12.6 V, lowest 12.1 V` line on the Driver Station, a `battery` graph,
a warning at INIT below `battery.low` (12 V by default), and the lowest reading in the log at the end.

## Conventions

- **Field coordinates** follow the FTC field coordinate system: origin at the field center, heading 0 faces +x,
  counterclockwise is positive. The season's `Field` says which way +x points and how the alliances mirror.
- **Robot coordinates** (`RobotOffset`) are `forward`, `right` and `turn` (counterclockwise).
- **Tiles** are named as the audience sees the field: columns A–F left to right, rows 1–6 from the audience side
  to the far side. An optional suffix picks a spot on the tile: `T`, `B`, `L`, `R`, `C` (e.g. `F6TR`, `C3L`).
- **Units** are read back explicitly: `distance.inCm`, `angle.inDegrees`.
- **`Angle` vs `Heading`**: an `Angle` is an amount of turning and never wraps (turrets, arms, counting rotations).
  A `Heading` is a direction on the field and always stays within (-180°, 180°]: `heading + angle` wraps around,
  and `heading - heading` is the shortest turn.

## Roadmap

1. ~~`core`: units and geometry~~
2. ~~`core`: coroutine loop runtime (`LoopRuntime`, `nextTick`, `waitUntil`, `race`, `ExclusiveResource`)~~
3. ~~`core`: config (typed delegates, scoped keys, units in values)~~
4. ~~`Component` tree (`core`) and `CoroutineOpMode` (`ftc`)~~
5. `ftc`
   1. ~~hardware: `Motor` and `PositionServo` with units and config settings, `hardware<T>()` for other devices~~
   2. ~~gamepad input: `driver1`, `driver2` with buttons, triggers and sticks~~
   3. ~~config files on the robot and the settings web page~~
6. ~~`core`: control (PID, feedforward, debouncer, hysteresis, slew rate)~~
7. ~~telemetry: Driver Station lines, live graphs page, logs, battery~~
8. ~~driving~~
   1. ~~drivetrain: `Drivetrain`, `MecanumDrive`, teleop driving~~
   2. ~~localization: Pinpoint odometry, field-centric driving~~
   3. ~~path following: `PathFollower`, PID to point~~
9. Port a real robot onto the platform

## Build

```
./gradlew test               # core unit tests
./gradlew :ftc:assembleDebug # builds the FTC module
```

The `ftc` module needs the Android SDK. Android Studio creates `local.properties` with its location when it opens
the project; from the command line, create it with `sdk.dir=/path/to/Android/sdk`.
