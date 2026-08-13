package com.poc.nintendog

import android.content.Context
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Breed definition. Only the numbers that change how the dog looks, moves and
 * behaves live here — the art itself is drawn procedurally in [DogView].
 */
class Breed(
    val label: String,
    val coat: Int,
    val patch: Int,
    val floppyEars: Boolean,
    val size: Float,       // body scale multiplier
    val fluffTail: Float,  // 0 = whippy, 1 = big curled plume
    val zoom: Float,       // running speed / general excitability
    val smart: Float       // how fast tricks are learned
) {
    companion object {
        val ALL = listOf(
            Breed("Shiba Inu", 0xFFD9944B.toInt(), 0xFFF3E3CE.toInt(), false, 0.95f, 1.0f, 1.05f, 0.95f),
            Breed("Labrador", 0xFFE8C98A.toInt(), 0xFFF6E9CC.toInt(), true, 1.12f, 0.35f, 1.0f, 1.15f),
            Breed("Beagle", 0xFFB9793F.toInt(), 0xFFF4EDE2.toInt(), true, 0.92f, 0.3f, 1.1f, 0.9f),
            Breed("Corgi", 0xFFE0A15A.toInt(), 0xFFF7EFE2.toInt(), false, 0.85f, 0.2f, 0.9f, 1.05f),
            Breed("Husky", 0xFF6E7481.toInt(), 0xFFF2F4F7.toInt(), false, 1.15f, 0.9f, 1.25f, 0.85f),
            Breed("Toy Poodle", 0xFF3A3A3E.toInt(), 0xFF5A5A60.toInt(), true, 0.78f, 0.6f, 0.95f, 1.2f)
        )
    }
}

/**
 * The whole pet simulation. A singleton because the widget, the alarm receiver
 * and the activities all mutate the same dog, and they share one process.
 *
 * Everything is time-based: needs decay in real minutes, so the dog keeps
 * living (and getting hungry) while the app is closed.
 */
object Pet {

    private const val PREFS = "nintendog"
    private const val KEY = "state"

    // --- how fast each need moves, per real-world minute ---
    // Tuned against PetSimTest: one bowl has to outlast the gap between check-ins,
    // or a conscientious player still watches their dog slowly starve. A meal
    // covers ~8h of hunger, so two or three visits a day keeps a pup thriving.
    private const val D_FULLNESS = 0.100f     // empty in ~16h
    private const val D_HYDRATION = 0.130f    // empty in ~13h
    private const val D_ENERGY_AWAKE = 0.111f // tired in ~15h
    private const val D_ENERGY_SLEEP = 0.667f // fully rested in ~2.5h
    private const val D_HYGIENE = 0.042f      // filthy in ~40h
    private const val D_BOWEL = 0.180f        // needs to go every ~9h
    private const val D_AFFECTION = 0.008f    // the bond fades if you never visit

    private const val MAX_CATCHUP_MIN = 3 * 24 * 60

    // --- identity ---
    var loaded = false; private set
    var created = false
    var gone = false                 // taken to the shelter after total neglect
    var name = "Buddy"
    var breedId = 0
    var birth = 0L
    var lastTick = 0L

    // --- needs, all 0..100 ---
    var fullness = 80f
    var hydration = 80f
    var energy = 85f
    var hygiene = 95f
    var happiness = 70f
    var bowel = 10f
    var affection = 5f
    var health = 100f

    // --- condition ---
    var asleep = false
    var sick = false
    var poops = 0
    var weight = 5.0f

    // --- progression ---
    var coins = 150
    var food = 4
    var treats = 6
    var shampoo = 2
    var medicine = 1
    var hasFrisbee = false
    var walks = 0
    var contestWins = 0
    var bestDiscScore = 0
    var tricks = linkedMapOf(
        "Sit" to 0f, "Lie Down" to 0f, "Shake" to 0f,
        "Roll Over" to 0f, "Speak" to 0f, "Spin" to 0f
    )

    // --- transient (not persisted) ---
    var lastMessage = ""

    val breed: Breed get() = Breed.ALL[breedId.coerceIn(0, Breed.ALL.size - 1)]

    // ---------------------------------------------------------------- storage

    fun load(ctx: Context) {
        if (loaded) return
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw != null) {
            try {
                val j = JSONObject(raw)
                created = j.optBoolean("created", false)
                gone = j.optBoolean("gone", false)
                name = j.optString("name", "Buddy")
                breedId = j.optInt("breed", 0)
                birth = j.optLong("birth", System.currentTimeMillis())
                lastTick = j.optLong("lastTick", System.currentTimeMillis())
                fullness = j.optDouble("fullness", 80.0).toFloat()
                hydration = j.optDouble("hydration", 80.0).toFloat()
                energy = j.optDouble("energy", 85.0).toFloat()
                hygiene = j.optDouble("hygiene", 95.0).toFloat()
                happiness = j.optDouble("happiness", 70.0).toFloat()
                bowel = j.optDouble("bowel", 10.0).toFloat()
                affection = j.optDouble("affection", 5.0).toFloat()
                health = j.optDouble("health", 100.0).toFloat()
                asleep = j.optBoolean("asleep", false)
                sick = j.optBoolean("sick", false)
                poops = j.optInt("poops", 0)
                weight = j.optDouble("weight", 5.0).toFloat()
                coins = j.optInt("coins", 150)
                food = j.optInt("food", 4)
                treats = j.optInt("treats", 6)
                shampoo = j.optInt("shampoo", 2)
                medicine = j.optInt("medicine", 1)
                hasFrisbee = j.optBoolean("frisbee", false)
                walks = j.optInt("walks", 0)
                contestWins = j.optInt("wins", 0)
                bestDiscScore = j.optInt("bestDisc", 0)
                val t = j.optJSONObject("tricks")
                if (t != null) for (k in tricks.keys.toList()) tricks[k] = t.optDouble(k, 0.0).toFloat()
            } catch (e: Exception) {
                // Corrupt save: start over rather than crash on launch.
                created = false
            }
        }
        loaded = true
    }

    fun save(ctx: Context) {
        val j = JSONObject()
        j.put("created", created); j.put("gone", gone)
        j.put("name", name); j.put("breed", breedId)
        j.put("birth", birth); j.put("lastTick", lastTick)
        j.put("fullness", fullness.toDouble()); j.put("hydration", hydration.toDouble())
        j.put("energy", energy.toDouble()); j.put("hygiene", hygiene.toDouble())
        j.put("happiness", happiness.toDouble()); j.put("bowel", bowel.toDouble())
        j.put("affection", affection.toDouble()); j.put("health", health.toDouble())
        j.put("asleep", asleep); j.put("sick", sick); j.put("poops", poops)
        j.put("weight", weight.toDouble())
        j.put("coins", coins); j.put("food", food); j.put("treats", treats)
        j.put("shampoo", shampoo); j.put("medicine", medicine); j.put("frisbee", hasFrisbee)
        j.put("walks", walks); j.put("wins", contestWins); j.put("bestDisc", bestDiscScore)
        val t = JSONObject()
        for ((k, v) in tricks) t.put(k, v.toDouble())
        j.put("tricks", t)
        // apply() rather than commit(): petting saves several times a second and
        // every reader (widget, receiver, activities) shares this process, so the
        // in-memory value is already consistent the moment we write it.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, j.toString()).apply()
    }

    fun adopt(ctx: Context, dogName: String, breed: Int) {
        val now = System.currentTimeMillis()
        created = true; gone = false
        name = dogName.ifBlank { "Buddy" }
        breedId = breed
        birth = now; lastTick = now
        fullness = 80f; hydration = 80f; energy = 85f; hygiene = 95f
        happiness = 75f; bowel = 5f; affection = 5f; health = 100f
        asleep = false; sick = false; poops = 0; weight = 5f
        coins = 150; food = 4; treats = 6; shampoo = 2; medicine = 1; hasFrisbee = false
        walks = 0; contestWins = 0; bestDiscScore = 0
        for (k in tricks.keys.toList()) tricks[k] = 0f
        save(ctx)
    }

    // ------------------------------------------------------------ simulation

    /** Advances the simulation to now, minute by minute. Safe to call often. */
    fun tick(ctx: Context) {
        load(ctx)
        if (!created || gone) return
        val now = System.currentTimeMillis()
        var minutes = ((now - lastTick) / 60_000L).toInt()
        if (minutes <= 0) return
        if (minutes > MAX_CATCHUP_MIN) minutes = MAX_CATCHUP_MIN
        repeat(minutes) { step() }
        lastTick = now
        save(ctx)
    }

    /** One simulated minute. Internal so the unit tests can drive it without a Context. */
    internal fun step() {
        // Dogs put themselves to bed when they are exhausted, and wake up rested.
        if (!asleep && energy < 8f) asleep = true
        if (asleep && energy > 97f) asleep = false

        fullness = max(0f, fullness - D_FULLNESS * (if (asleep) 0.5f else 1f))
        hydration = max(0f, hydration - D_HYDRATION * (if (asleep) 0.5f else 1f))
        energy = if (asleep) min(100f, energy + D_ENERGY_SLEEP)
        else max(0f, energy - D_ENERGY_AWAKE)

        hygiene = max(0f, hygiene - D_HYGIENE - 0.10f * poops)

        if (!asleep && fullness > 10f) {
            bowel = min(100f, bowel + D_BOWEL)
            if (bowel >= 100f) { bowel = 0f; poops = min(6, poops + 1) }
        }

        var dh = -0.035f
        if (fullness < 25f) dh -= 0.06f
        if (hydration < 25f) dh -= 0.06f
        if (hygiene < 35f) dh -= 0.04f
        if (energy < 15f && !asleep) dh -= 0.05f
        if (poops > 0) dh -= 0.03f * poops
        if (sick) dh -= 0.12f
        if (affection > 60f) dh += 0.02f
        if (asleep) dh *= 0.4f
        happiness = (happiness + dh).coerceIn(0f, 100f)

        affection = max(0f, affection - D_AFFECTION)

        val starving = fullness < 5f || hydration < 5f
        health = when {
            sick || starving -> max(0f, health - 0.08f)
            fullness > 40f && hydration > 40f && hygiene > 40f && happiness > 40f ->
                min(100f, health + 0.06f)
            else -> health
        }

        if (!sick && Random.nextFloat() < 0.0006f * (
                    (if (hygiene < 25f) 3f else 0f) + (if (poops >= 3) 2f else 0f) +
                            (if (health < 40f) 3f else 0f) + 0.4f)
        ) sick = true

        // Total neglect has a consequence — this is the tamagotchi stake.
        if (health <= 0f) gone = true

        weight = (weight - 0.0006f).coerceIn(2.5f, 14f)
    }

    // --------------------------------------------------------------- actions

    fun feed(): String {
        if (asleep) return "$name is fast asleep."
        if (food <= 0) return "No food left — visit the shop."
        if (fullness > 92f) return "$name sniffs the bowl and walks away. Not hungry."
        food--
        fullness = min(100f, fullness + 50f)
        happiness = min(100f, happiness + 4f)
        affection = min(100f, affection + 1.5f)
        bowel = min(100f, bowel + 12f)
        weight = min(14f, weight + 0.08f)
        return "$name wolfs down a bowl of kibble."
    }

    fun water(): String {
        if (asleep) return "$name is fast asleep."
        if (hydration > 92f) return "The water bowl is still full."
        hydration = min(100f, hydration + 55f)
        happiness = min(100f, happiness + 2f)
        return "$name laps up fresh water."
    }

    fun treat(): String {
        if (asleep) return "$name is fast asleep."
        if (treats <= 0) return "Out of treats — visit the shop."
        treats--
        fullness = min(100f, fullness + 8f)
        happiness = min(100f, happiness + 9f)
        affection = min(100f, affection + 4f)
        bowel = min(100f, bowel + 4f)
        weight = min(14f, weight + 0.03f)
        return "$name takes the treat gently and thumps their tail."
    }

    /** Called by [DogView] as the finger moves over the dog. */
    fun stroke(count: Int) {
        if (asleep) return
        happiness = min(100f, happiness + 0.35f * count)
        affection = min(100f, affection + 0.5f * count)
    }

    /** Scrubbing progress from the wash mini-game. */
    fun scrub(amount: Float): Boolean {
        if (shampoo <= 0) return false
        hygiene = min(100f, hygiene + amount)
        happiness = min(100f, happiness + amount * 0.1f)
        return true
    }

    fun finishWash(): String {
        if (shampoo <= 0) return "You need shampoo from the shop."
        shampoo--
        hygiene = 100f
        affection = min(100f, affection + 3f)
        return "$name shakes off a spray of water. Squeaky clean!"
    }

    fun cleanPoop(): String {
        if (poops <= 0) return "The floor is already spotless."
        poops = 0
        hygiene = min(100f, hygiene + 8f)
        happiness = min(100f, happiness + 3f)
        return "You clean up after $name. Much better."
    }

    fun giveMedicine(): String {
        if (!sick) return "$name isn't sick."
        if (medicine <= 0) return "No medicine left — visit the shop."
        medicine--
        sick = false
        health = min(100f, health + 25f)
        happiness = max(0f, happiness - 4f)
        affection = min(100f, affection + 2f)
        return "$name swallows the pill with a grimace, then perks up."
    }

    fun toggleSleep(): String {
        asleep = !asleep
        return if (asleep) "Lights out. $name curls up." else "$name stretches and yawns."
    }

    /** Fetch / frisbee: one completed retrieve. */
    fun retrieved(): String {
        happiness = min(100f, happiness + 6f)
        affection = min(100f, affection + 2f)
        energy = max(0f, energy - 2.5f)
        fullness = max(0f, fullness - 1.2f)
        hydration = max(0f, hydration - 1.5f)
        weight = max(2.5f, weight - 0.01f)
        return "$name brings it right back to you!"
    }

    /** Trick training outcome. [reward] is Praise / Treat / Ignore. */
    fun trained(trick: String, praised: Boolean, usedTreat: Boolean): String {
        val gain = (if (usedTreat) 9f else if (praised) 5.5f else 1f) * breed.smart
        tricks[trick] = min(100f, (tricks[trick] ?: 0f) + gain)
        happiness = min(100f, happiness + if (praised || usedTreat) 4f else 0f)
        affection = min(100f, affection + if (usedTreat) 3f else 1.5f)
        energy = max(0f, energy - 1.5f)
        if (usedTreat && treats > 0) treats--
        val m = tricks[trick] ?: 0f
        return when {
            m >= 100f -> "$name has mastered $trick!"
            praised || usedTreat -> "\"Good $name!\" — $trick is now ${m.toInt()}%."
            else -> "$name looks up, waiting for praise..."
        }
    }

    fun trickMastery(trick: String): Float = tricks[trick] ?: 0f

    /** Result of a completed walk. */
    fun walked(minutes: Int, found: Int): String {
        walks++
        happiness = min(100f, happiness + minutes * 0.9f)
        affection = min(100f, affection + minutes * 0.5f)
        energy = max(0f, energy - minutes * 1.6f)
        fullness = max(0f, fullness - minutes * 0.9f)
        hydration = max(0f, hydration - minutes * 1.3f)
        hygiene = max(0f, hygiene - minutes * 1.1f)
        weight = max(2.5f, weight - minutes * 0.012f)
        bowel = min(100f, bowel + minutes * 0.8f)
        coins += found
        return "Back from a $minutes-minute walk. $name found $found coins."
    }

    fun contestFinished(score: Int): String {
        val prize = when {
            score >= 12 -> 120
            score >= 8 -> 70
            score >= 4 -> 35
            else -> 10
        }
        if (score >= 8) contestWins++
        if (score > bestDiscScore) bestDiscScore = score
        coins += prize
        happiness = min(100f, happiness + 8f)
        affection = min(100f, affection + 3f)
        energy = max(0f, energy - 12f)
        hydration = max(0f, hydration - 8f)
        return "Disc contest: $score catches. Prize: $prize coins."
    }

    fun buy(item: String): String {
        data class P(val cost: Int, val apply: () -> Unit, val msg: String)
        val p = when (item) {
            "food" -> P(20, { food += 3 }, "Bought 3 bowls of kibble.")
            "treats" -> P(15, { treats += 5 }, "Bought 5 treats.")
            "shampoo" -> P(25, { shampoo += 2 }, "Bought 2 bottles of shampoo.")
            "medicine" -> P(60, { medicine += 1 }, "Bought 1 dose of medicine.")
            "frisbee" -> P(90, { hasFrisbee = true }, "Bought a flying disc!")
            else -> return "Unknown item."
        }
        if (item == "frisbee" && hasFrisbee) return "You already own a disc."
        if (coins < p.cost) return "Not enough coins (need ${p.cost})."
        coins -= p.cost
        p.apply()
        return p.msg
    }

    // ------------------------------------------------------------- readouts

    val ageDays: Int get() = ((System.currentTimeMillis() - birth) / 86_400_000L).toInt()

    val stage: String get() = when {
        ageDays < 3 -> "Puppy"
        ageDays < 10 -> "Young"
        else -> "Adult"
    }

    /** Single number the widget and the mood face are driven from. */
    val care: Float
        get() = (fullness * 0.22f + hydration * 0.18f + hygiene * 0.15f +
                happiness * 0.30f + health * 0.15f).coerceIn(0f, 100f)

    fun moodEmoji(): String = when {
        gone -> "🏠"        // house
        sick -> "🤒"        // sick face
        asleep -> "😴"      // sleeping
        care > 80 -> "😍"   // heart eyes
        care > 60 -> "😄"   // grin
        care > 40 -> "🙂"   // slight smile
        care > 20 -> "😟"   // worried
        else -> "😢"        // crying
    }

    /** The one thing the dog most wants right now — drives nagging + widget. */
    fun neediest(): String? {
        if (gone) return null
        if (sick) return "$name is sick and needs medicine"
        if (fullness < 22f) return "$name is hungry"
        if (hydration < 22f) return "$name is thirsty"
        if (poops >= 2) return "$name's area needs cleaning"
        if (hygiene < 25f) return "$name needs a bath"
        if (happiness < 30f) return "$name is lonely and wants to play"
        if (energy < 12f && !asleep) return "$name is exhausted"
        return null
    }

    fun statusLine(): String = when {
        gone -> "$name was taken to the shelter."
        sick -> "$name isn't feeling well."
        asleep -> "$name is sleeping."
        else -> neediest() ?: "$name is happy and content."
    }
}
