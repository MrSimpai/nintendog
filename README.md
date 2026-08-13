# Nintendog — a Nintendogs-style virtual pet (Android POC)

A real-time care game: you adopt a puppy, and it keeps living whether or not the
app is open. Needs decay in real minutes, so neglect it for a day and you will
come back to a hungry, filthy, miserable dog. Look after it and it bonds to you.

Art is deliberately programmer-art — the dog is drawn procedurally on a `Canvas`
and every sound is synthesised PCM. **Zero image or audio assets, zero
third-party dependencies.** All of the effort went into mechanics and feel.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone and tap it (allow "install from unknown
sources"). Min Android 7.0.

## The Nintendogs half — direct interaction

| Feel | How it works |
| --- | --- |
| **Petting** | Drag your finger over the dog. Each stroke raises happiness and bond, hearts pop, the tail wags harder, and it sits and leans into your hand. |
| **Fetch** | Fling the ball across the yard. It arcs, bounces and rolls with real physics; the pup chases it down, picks it up and trots it back to your feet. |
| **Trick training** | Lure with gestures, not buttons: swipe **down** for Sit, a long drag down for Lie Down, sideways for Shake, **up** for Speak, a circle for Spin/Roll Over. Success is a dice roll against current mastery — then a 3-second window opens to **Praise** or **give a treat**. Treats teach fastest. Mastery is per trick, 0–100%. |
| **Bathing** | Scrub the dog with your finger. Suds build to 100%, bubbles drift off, lift your finger to rinse. |
| **Walks** | Draw a route on the neighbourhood map exactly like the DS game. Route length sets the duration; along the way you find coins, meet other dogs, sniff trees, stop at the pond. |
| **Disc contest** | Six throws. Only catches **in the air** score — if the disc hits the ground first it's a miss. Score converts to prize money. |
| **Breeds** | Six, each with its own coat, ear shape, size, tail plume, speed and how fast it learns tricks. |
| **The dog is alive** | Idle AI wanders, sits, lies down, scratches, begs and barks on its own. It breathes, blinks, pants, tracks your finger with its head, and moves in a 2.5D room so it can walk towards you. |

## The Tamagotchi half — the care loop

Six meters decay in real time, at rates tuned so a day of neglect hurts but a
few check-ins a day keeps a dog thriving:

| Need | Empties in |
| --- | --- |
| Food | ~16 h (one bowl covers ~8 h) |
| Water | ~13 h (one drink covers ~7 h) |
| Energy | ~15 h awake (refills in ~2.5 h asleep) |
| Clean | ~40 h, much faster with mess on the floor |
| Happy | driven by every other need, plus your attention |
| Bond | fades slowly if you never visit |

The rates are tuned so **two or three visits a day keeps a pup thriving** — a
meal has to outlast the gap between check-ins, or a conscientious player still
watches their dog slowly starve. `PetSimTest` pins that down: one test simulates
three days of check-ins every four hours and fails if health drops below 50.

Plus: bowel timer → the dog messes the floor → hygiene tanks until you clean it.
Low hygiene and poor health can make it **sick**, which needs medicine. The dog
puts itself to bed when exhausted and wakes rested. Weight goes up with food and
down with exercise. Age advances through Puppy → Young → Adult.

**Stakes:** health only drains when a need is at rock bottom. If it reaches zero,
the dog is taken to the shelter and you must adopt again. That is the tamagotchi
tension — but it takes sustained, total neglect, not one missed meal.

Economy: coins from walks and contests → kibble, treats, shampoo, medicine, and
the flying disc that unlocks the contest.

Offline catch-up is capped at 3 days, so a long absence can't instantly kill a
pet that was healthy when you left.

## The widget

A home-screen widget is the piece that makes people actually care — the dog asks
for things where you can't ignore it.

- Mood face that tracks a weighted care score, name, life stage, and the single
  most urgent thing the dog needs right now
- Live food and happiness meters
- **One-tap Feed, Water and Pet** straight from the home screen, no app launch
- Tap the face to open the game

A ~20-minute alarm advances the simulation, refreshes the widget and posts a
notification when something is genuinely wrong — rate-limited to one nag every
two hours.

## Put it on GitHub

Every file in this folder is meant to be uploaded — there is nothing here to
strip out first. Select all of it and drag it into a new empty repository (the
"uploading an existing file" link on GitHub's empty-repo page).

The moment it lands, `.github/workflows/main.yml` runs and:

1. runs the simulation unit tests — a broken decay curve fails the build here
   rather than shipping
2. builds the APK (signed if you've set the secrets below, debug otherwise)
3. attaches it as a build artifact
4. publishes a **Release** tagged `v1.0.<run number>` with a direct
   `Nintendog.apk` link — that's the link to open on the phone

The APK's `versionCode` comes from the run number, so each build installs over
the last one as a proper update.

### Optional: signed release builds

Without these secrets you still get a working debug APK. To sign properly, add
under *Settings → Secrets and variables → Actions*:

| Secret | Value |
| --- | --- |
| `KEYSTORE_B64` | your `keystore.jks`, base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

## Building locally

`build-local.cmd` uses the toolchain kept in `../nintendog-toolchain` (JDK 17,
Android SDK 34) so nothing large sits inside the repo folder:

```bash
build-local.cmd
```

Or open the folder in Android Studio, which supplies its own SDK. There is
deliberately no `local.properties` in the repo: a Windows `sdk.dir` would point
the Linux CI runner at a directory that doesn't exist and fail the build.

## Code map

| File | Role |
| --- | --- |
| `Pet.kt` | The whole simulation: needs, decay, actions, economy, persistence |
| `DogView.kt` | Procedural dog rendering, idle AI, petting, fetch, wash, training, disc |
| `MainActivity.kt` | Home screen, meters, mode switching, care actions |
| `OnboardActivity.kt` | Breed selection and naming |
| `WalkActivity.kt` | Route drawing and walk encounters |
| `ShopActivity.kt` | Pet shop |
| `PetWidget.kt` | Home-screen widget and its one-tap actions |
| `TickReceiver.kt` | Background heartbeat, widget refresh, notifications |
| `Sfx.kt` | Synthesised barks, whines, chimes |
| `Ui.kt` | Code-built views and the need meter |
| `app/src/test/.../PetSimTest.kt` | 15 JVM tests over the simulation — no emulator |
| `.github/workflows/main.yml` | Test → build APK → publish Release, on every push |

## Known POC limits

- Programmer art. The dog is geometry, not a model.
- No voice recognition. In the DS game you call the dog by name into the mic;
  here training is gesture-based instead.
- Background ticking uses `AlarmManager` and will be delayed by Doze on a
  sleeping phone. The simulation catches up on next open, so nothing is lost.
- Single dog, no kennel, no multiplayer/Bark Mode.
