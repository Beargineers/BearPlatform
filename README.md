# BearPlatform

A reusable Kotlin platform for FTC robots from team Beargineers (#27628). It grew out of our DECODE season code,
and aims to make robot programming simpler for students: small composable pieces, real units instead of bare
numbers, and nothing tied to a particular season.

## Modules

| Module | What                                                                                     |
|--------|------------------------------------------------------------------------------------------|
| `core` | Pure Kotlin/JVM, no FTC SDK. Everything here runs in unit tests on a laptop.              |
| `ftc`  | Android library on top of the FTC SDK: `CoroutineOpMode` and hardware; gamepad to follow. |

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
  parts, siblings in creation order.

- `runtime`: `LoopRuntime` runs coroutines on the robot loop, one step per `tick()`. Robot code is written as
  plain sequential `suspend` functions:

```kotlin
suspend fun GammaRobot.collectAndShoot() = withName("Collect and shoot") {
    race({ driveTo(SPIKE_1) }, { waitUntil { intake.isFull } })  // whichever happens first
    waitUntil(1.seconds) { shooter.isReady }                      // gives up after a second
    shooter.shoot()
}

suspend fun Drive.driveTo(target: Pose) = takeOver("driveTo $target") {  // stops any other drive action
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

### ftc

```kotlin
@TeleOp
class Driving : CoroutineOpMode() {
    val robot = GammaRobot(this)

    override suspend fun CoroutineScope.onInit() { /* from INIT until START: menus, starting pose */ }

    override suspend fun CoroutineScope.onStart() {
        everyTick { /* driving */ }
    }
}
```

Every loop, `CoroutineOpMode` reads all hub sensors in one bulk read, updates the robot's components, runs
coroutines and sends telemetry.

Motors and servos are components too, so their settings live in config under their parent's path:

```kotlin
class Turret(parent: Component) : Component(parent) {
    val motor = Motor(this, "turret", gearRatio = 121.0 / 16)  // turret.turret.direction, .brake, .gearRatio
    val pinpoint by hardware<GoBildaPinpointDriver>()          // any other device from the robot configuration

    override fun update() {
        val angle = motor.position                             // an Angle at the turret, after the gearing
        motor.power = 0.3                                      // only sent to the hub when it changes
    }
}

class Shooter(parent: Component) : Component(parent) {
    val latch = PositionServo(this, "latch")
    val latchOpen by config(0.3)
    val latchClosed by config(0.65)
}
```

Unit tests use fake devices (`ftc/src/test/.../FakeHardware.kt`), since the SDK's own device lookup needs the
Control Hub.

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
   2. gamepad input
   3. config files on the robot and the settings web page
6. `ftc`: drivetrain, localization, path following
7. Port a real robot onto the platform

## Build

```
./gradlew test               # core unit tests
./gradlew :ftc:assembleDebug # builds the FTC module
```

The `ftc` module needs the Android SDK. Android Studio creates `local.properties` with its location when it opens
the project; from the command line, create it with `sdk.dir=/path/to/Android/sdk`.
