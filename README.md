# BearPlatform

A reusable Kotlin platform for FTC robots from team Beargineers (#27628). It grew out of our DECODE season code,
and aims to make robot programming simpler for students: small composable pieces, real units instead of bare
numbers, and nothing tied to a particular season.

## Modules

| Module | What                                                                                              |
|--------|---------------------------------------------------------------------------------------------------|
| `core` | Pure Kotlin/JVM, no FTC SDK. Everything here runs in unit tests on a laptop.                       |
| `ftc`  | *(planned)* Android library on top of the FTC SDK: opmodes, components, hardware, gamepad, config. |

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

- `runtime`: `LoopRuntime` runs coroutines on the robot loop, one step per `tick()`. Robot code is written as
  plain sequential `suspend` functions:

```kotlin
suspend fun Robot.collectAndShoot() = withName("Collect and shoot") {
    race({ driveTo(SPIKE_1) }, { waitUntil { intake.isFull } })  // whichever happens first
    waitUntil(1.seconds) { shooter.isReady }                      // gives up after a second
    shoot()
}

suspend fun Drive.driveTo(target: Pose) = resource.takeOver("driveTo $target") {  // stops any other drive action
    while (!isAt(target)) {
        followTowards(target)
        nextTick()
    }
}
```

  `delay` and `withTimeout` work too; they measure time with the runtime's `TimeSource`, so tests control the clock.

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
3. `core`: config (typed delegates, scoped keys, pluggable sources)
4. `ftc`: `Component` tree and `CoroutineOpMode`
5. `ftc`: hardware wrappers with units, gamepad input
6. `ftc`: drivetrain, localization, path following
7. Port a real robot onto the platform

## Build

```
./gradlew test
```
