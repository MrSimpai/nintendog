package com.poc.nintendog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The decay curve is the whole game, and it runs on plain JVM types — no Context,
 * no emulator. These tests drive [Pet.step] a minute at a time.
 */
class PetSimTest {

    /** Mirrors a fresh adoption, minus the persistence that needs a Context. */
    @Before
    fun freshPuppy() {
        Pet.created = true
        Pet.gone = false
        Pet.name = "Test"
        Pet.breedId = 0
        Pet.fullness = 80f
        Pet.hydration = 80f
        Pet.energy = 85f
        Pet.hygiene = 95f
        Pet.happiness = 75f
        Pet.bowel = 5f
        Pet.affection = 5f
        Pet.health = 100f
        Pet.asleep = false
        Pet.sick = false
        Pet.poops = 0
        Pet.weight = 5f
        Pet.coins = 150
        Pet.food = 4
        Pet.treats = 6
        Pet.shampoo = 2
        Pet.medicine = 1
        Pet.hasFrisbee = false
        Pet.walks = 0
        Pet.contestWins = 0
        Pet.bestDiscScore = 0
        for (k in Pet.tricks.keys.toList()) Pet.tricks[k] = 0f
    }

    private fun runMinutes(n: Int) = repeat(n) { Pet.step() }

    @Test
    fun `an ignored puppy is hungry and thirsty within a working day`() {
        runMinutes(8 * 60)
        assertTrue("should be thirsty after 8h, was ${Pet.hydration}", Pet.hydration < 30f)
        assertTrue("should be hungry after 8h, was ${Pet.fullness}", Pet.fullness < 45f)
        assertTrue("happiness should have slipped", Pet.happiness < 75f)
    }

    @Test
    fun `feeding and watering refill the meters and consume stock`() {
        runMinutes(8 * 60)
        val hungry = Pet.fullness
        Pet.food = 1
        Pet.feed()
        Pet.water()
        assertTrue("feeding should help, ${Pet.fullness} vs $hungry", Pet.fullness > hungry)
        assertTrue("watering should help, was ${Pet.hydration}", Pet.hydration > 40f)
        assertEquals("feeding consumes a bowl", 0, Pet.food)
        assertEquals("nothing left to serve", "No food left — visit the shop.", Pet.feed())
    }

    @Test
    fun `an exhausted puppy puts itself to bed and wakes up rested`() {
        Pet.energy = 5f
        Pet.step()
        assertTrue("should have fallen asleep", Pet.asleep)
        runMinutes(3 * 60)
        assertFalse("should have woken up", Pet.asleep)
        assertTrue("should be rested, was ${Pet.energy}", Pet.energy > 90f)
    }

    @Test
    fun `a full bowel becomes a mess on the floor`() {
        Pet.bowel = 99f
        runMinutes(10)
        assertTrue("should have pooped", Pet.poops >= 1)
        assertTrue("bowel resets after going", Pet.bowel < 50f)
    }

    @Test
    fun `mess on the floor drags hygiene down faster than a clean room`() {
        runMinutes(6 * 60)
        val clean = Pet.hygiene

        freshPuppy()
        Pet.poops = 3
        runMinutes(6 * 60)
        assertTrue("dirty room should be worse: ${Pet.hygiene} vs $clean", Pet.hygiene < clean)

        val before = Pet.hygiene
        Pet.cleanPoop()
        assertEquals("floor is clear", 0, Pet.poops)
        assertTrue("cleaning up helps", Pet.hygiene > before)
    }

    @Test
    fun `total neglect eventually sends the dog to the shelter`() {
        runMinutes(3 * 24 * 60)
        assertTrue("health should be gone, was ${Pet.health}", Pet.health <= 0f)
        assertTrue("dog should have been taken", Pet.gone)
    }

    @Test
    fun `a dog that is looked after never reaches the shelter`() {
        // Three days, with a check-in every four hours.
        repeat(3 * 24 * 60) { minute ->
            Pet.step()
            if (minute % 240 == 0) {
                Pet.food = 1; Pet.shampoo = 1; Pet.medicine = 1
                Pet.feed()
                Pet.water()
                Pet.stroke(10)
                if (Pet.poops > 0) Pet.cleanPoop()
                if (Pet.sick) Pet.giveMedicine()
                if (Pet.hygiene < 50f) Pet.finishWash()
            }
        }
        assertFalse("a cared-for dog should still be here", Pet.gone)
        assertTrue("health should hold up, was ${Pet.health}", Pet.health > 50f)
        assertTrue("the bond should have grown, was ${Pet.affection}", Pet.affection > 20f)
    }

    @Test
    fun `the care score collapses when the basics are missing`() {
        val happy = Pet.care
        Pet.fullness = 0f; Pet.hydration = 0f; Pet.happiness = 0f
        assertTrue("care should drop sharply", Pet.care < happy / 2f)
        assertEquals("Test is hungry", Pet.neediest())
    }

    @Test
    fun `a content dog reports nothing urgent`() {
        assertEquals(null, Pet.neediest())
        assertEquals("Test is happy and content.", Pet.statusLine())
    }

    @Test
    fun `illness is reported ahead of every other need`() {
        Pet.sick = true
        Pet.fullness = 0f
        assertEquals("Test is sick and needs medicine", Pet.neediest())
        Pet.medicine = 1
        Pet.giveMedicine()
        assertFalse("medicine cures", Pet.sick)
        assertEquals("the dose is used up", 0, Pet.medicine)
    }

    @Test
    fun `treats teach a trick faster than praise alone`() {
        Pet.treats = 5
        Pet.trained("Sit", praised = true, usedTreat = false)
        val withPraise = Pet.trickMastery("Sit")

        Pet.tricks["Sit"] = 0f
        Pet.trained("Sit", praised = true, usedTreat = true)
        val withTreat = Pet.trickMastery("Sit")

        assertTrue("$withTreat should beat $withPraise", withTreat > withPraise)
        assertEquals("a treat is consumed", 4, Pet.treats)
    }

    @Test
    fun `mastery is capped and ignoring the dog barely teaches anything`() {
        repeat(50) { Pet.trained("Spin", praised = true, usedTreat = false) }
        assertEquals("mastery tops out at 100", 100f, Pet.trickMastery("Spin"), 0.01f)

        Pet.trained("Shake", praised = false, usedTreat = false)
        assertTrue("ignoring barely helps, was ${Pet.trickMastery("Shake")}",
            Pet.trickMastery("Shake") < 2f)
    }

    @Test
    fun `the shop refuses purchases you cannot afford`() {
        Pet.coins = 10
        val before = Pet.food
        assertTrue(Pet.buy("food").startsWith("Not enough coins"))
        assertEquals("nothing was delivered", before, Pet.food)
        assertEquals("no coins taken", 10, Pet.coins)
    }

    @Test
    fun `walking costs energy and pays out coins`() {
        val coins = Pet.coins
        val energy = Pet.energy
        Pet.walked(30, 40)
        assertEquals("prize is banked", coins + 40, Pet.coins)
        assertTrue("a walk is tiring", Pet.energy < energy)
        assertTrue("and good for the mood", Pet.happiness > 75f)
    }

    @Test
    fun `only a strong disc round pays the top prize`() {
        val poor = Pet.coins
        Pet.contestFinished(1)
        val afterPoor = Pet.coins - poor

        freshPuppy()
        val good = Pet.coins
        Pet.contestFinished(12)
        val afterGood = Pet.coins - good

        assertTrue("$afterGood should beat $afterPoor", afterGood > afterPoor)
        assertEquals("a strong round counts as a win", 1, Pet.contestWins)
    }
}
