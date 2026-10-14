# BearPlatform

A Kotlin library for programming FTC robots, from team Beargineers (#27628). It sits on top of FIRST's FTC SDK and
gives robot code a shape students can read, change and reuse from one season to the next:

- a robot is a tree of small parts, each with its own settings;
- settings are declared where the code uses them, and tuned live on the robot from a laptop, without rebuilding;
- robot code reads top to bottom, as steps that wait for each other, with no threads;
- distances, angles and speeds carry their units;
- mecanum driving, Pinpoint odometry, field-centric TeleOp and driving to poses come with it;
- Driver Station lines, live graphs in the browser, logs, and commands that start actions from a laptop show what the
  robot does.

```kotlin
class Claw(parent: Component) : Component(parent) {
    val servo = PositionServo(this, "servo", device = "claw")
    val openPosition by config(0.6)                 // the setting claw.openPosition, tuned on the robot
    val closedPosition by config(0.3)

    fun open() { servo.position = openPosition }
    fun close() { servo.position = closedPosition }
}

class MyRobot(host: RobotHost) : Robot(host) {
    val localizer = PinpointLocalizer(this)
    val drive = MecanumDrive(this)
    val follower = PathFollower(this, drive, localizer)
    val claw = Claw(this)
}

@Autonomous(name = "Auto")
class Auto : CoroutineOpMode() {
    private val robot by robot(::MyRobot)

    override suspend fun CoroutineScope.onStart() {
        robot.claw.close()
        robot.follower.driveTo(Pose(60.cm, 0.cm, 90.degrees))   // returns once the robot is there
        robot.claw.open()
    }
}
```

## Start a robot project

The [Quickstart](https://github.com/Beargineers/Quickstart) is FIRST's FtcRobotController with a Kotlin `TeamCode` on
BearPlatform, which drives a mecanum robot out of the box. Fork it and follow its README.

A project you already have needs the Kotlin Android plugin and the library, from Maven Central:

```groovy
implementation 'nl.beargineers.platform:ftc:0.1.0'
```

`ftc` brings `core` with it. It compiles against FTC SDK 11.1 and works with whichever SDK version the app has, 12.0
included.

## Documentation

- **The manual:** [beargineers.nl/platform](https://beargineers.nl/platform/), from getting started to tuning path
  following.
- **API reference:** [ftc](https://javadoc.io/doc/nl.beargineers.platform/ftc) and
  [core](https://javadoc.io/doc/nl.beargineers.platform/core) on javadoc.io.

## Contributing

Contributions are welcome: fixes, new hardware, better docs. For anything big, open an issue first to talk it over.

### The repository

| Module | What                                                                                                    |
|--------|---------------------------------------------------------------------------------------------------------|
| `core` | Pure Kotlin/JVM, no FTC SDK: units, geometry, components, config, the coroutine runtime, control, driving. Everything here runs in unit tests on a laptop. |
| `ftc`  | Android library on top of the FTC SDK: opmodes, hardware, gamepads, the robot's web pages.             |

`scripts/` has shell scripts for the robot's web pages: sending settings, starting commands, downloading match logs.

### Build and test

```
./gradlew test                  # all unit tests
./gradlew :ftc:assembleDebug    # builds the FTC module
./gradlew publishToMavenLocal   # the -SNAPSHOT version into ~/.m2, for trying in a robot project
```

The `ftc` module needs the Android SDK. Android Studio creates `local.properties` with its location when it opens the
project; from the command line, create it with `sdk.dir=/path/to/Android/sdk`. CI runs the tests and a local publish on
every push and pull request.

### Trying a change on a robot

Clone BearPlatform next to a robot project, like one made from the Quickstart, and build the robot project against
your checkout instead of the released version:

```
./gradlew --include-build ../BearPlatform :TeamCode:assembleDebug
```

or add `includeBuild('../BearPlatform')` to the robot project's `settings.gradle`, which Android Studio picks up too.

### Guidelines

- **Nothing season-specific.** Game pieces, field positions and scoring belong in robot projects. The platform gives
  them the means: `Field`, settings, components.
- **Units, not bare numbers.** Public APIs take and return `Distance`, `Angle`, `Heading`, velocities and `Duration`.
- **Settings for whatever gets tuned,** with sensible defaults: `val tolerance by config(2.cm)`, documented where
  declared.
- **Parts are components.** Hardware is looked up from `init()` on, read in `update()`, and left safe in `stop()`.
- **Mind the hubs.** Every command to a hub costs time on a connection all devices share: send only changes, read slow
  devices on the device reader, and keep allocations out of every-loop code. See
  [Performance](https://beargineers.nl/platform/performance/).
- **Tests come with the change.** `core` code runs in unit tests with a test clock (`TestTimeSource`) and the loop
  driven by hand; `ftc` code runs against fake devices (`ftc/src/test/.../FakeHardware.kt`).
- **Plain words.** KDoc on every public declaration, saying what it does for the robot, with an example where it helps.
  Students read these.
- **Users' docs too.** A change users will notice updates the manual in the same go: it lives in the website's
  repository, [Beargineers/beargineers.nl](https://github.com/Beargineers/beargineers.nl), under `_platform/`.
- **Small commits** whose message says what changed and why.

### Roadmap

1. ~~`core`: units and geometry~~
2. ~~`core`: coroutine loop runtime (`LoopRuntime`, `nextTick`, `waitUntil`, `race`, `ExclusiveResource`)~~
3. ~~`core`: config (typed delegates, scoped keys, units in values)~~
4. ~~`Component` tree (`core`) and `CoroutineOpMode` (`ftc`)~~
5. `ftc`
   1. ~~hardware: `Motor`, `PositionServo` and `CRServo` with units and config settings; `DigitalInput`,
      `AnalogInput`, `DistanceSensor` and `ColorSensor`; indicator lights; `hardware<T>()` for other devices~~
   2. ~~gamepad input: `controller1`, `controller2` with buttons, triggers and sticks~~
   3. ~~config files on the robot and the settings web page~~
6. ~~`core`: control (PID, feedforward, debouncer, hysteresis, slew rate)~~
7. ~~telemetry: Driver Station lines, live graphs page, logs, battery~~
8. ~~driving~~
   1. ~~drivetrain: `Drivetrain`, `MecanumDrive`, teleop driving~~
   2. ~~localization: Pinpoint odometry, field-centric driving~~
   3. ~~path following: `PathFollower`, PID to point~~
9. Port the real robots onto the platform
   1. ~~one app for several robots: `RobotChoice` by robot configuration, robots created at INIT, config sections~~
   2. the DECODE season: its robot contract and shared opmodes, with Beta first, then Gamma
10. Ready for other teams
    1. ~~MIT license and releases to Maven Central, the first being 0.1.0~~
    2. generic check and tuning opmodes in the library, registered for the team's robot in a few lines
    3. ~~a quickstart project to start from: FtcRobotController without the Java samples, a Kotlin `TeamCode`~~
    4. Gradle tasks instead of the shell scripts, so they work on Windows too
    5. tank drives: an FTC `TankDrive` for `TankDrivetrain`, and field-centric driving for it, which has to turn the
       robot toward where the stick points before driving there
    6. ~~the manual at beargineers.nl/platform~~

### Releasing

See [RELEASING.md](RELEASING.md).

## License

[MIT](LICENSE): use it, change it, share it, just keep the copyright notice.
