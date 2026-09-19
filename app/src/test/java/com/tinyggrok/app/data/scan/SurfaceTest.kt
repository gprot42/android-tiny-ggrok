package com.tinyggrok.app.data.scan

import com.tinyggrok.app.data.scan.SurfaceScenes.Surface
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An A4 sheet on the surfaces people actually use, each with the feature most likely to
 * fool an edge finder, under even light and under light from one side.
 *
 * These exist because "it works on a plain background" proved nothing. Three designs
 * were tried against these scenes before one passed all of them:
 *  - sharpest local step: drifted onto carpet speckle;
 *  - difference of region means: on white marble, cropped the scan to the block of text,
 *    because print drags a mean down and became the strongest boundary;
 *  - difference of region medians with a fine second stage: passes everywhere below.
 */
class SurfaceTest {

    /** Under a fifth of a percent of the frame: far below anything that reads as tilt. */
    private val tight = 0.003f

    private val allLighting = listOf(false, true)

    @Test
    fun darkAndMidSurfacesAreFoundOnTheDeviceWithNoHelp() {
        for (surface in listOf(Surface.DARK_WOOD, Surface.DARK_GRANITE, Surface.GREY_CONCRETE)) {
            for (sideLit in allLighting) {
                val scene = SurfaceScenes.render(surface, lightingGradient = sideLit)
                val corners = locatePage(scene)
                assertNotNull("$surface sideLit=$sideLit should be found on-device", corners)
                val error = SurfaceScenes.worstError(corners!!)
                assertTrue("$surface sideLit=$sideLit error $error", error < tight)
            }
        }
    }

    @Test
    fun paleWoodIsFoundOnTheDeviceInEvenLight() {
        val corners = locatePage(SurfaceScenes.render(Surface.LIGHT_WOOD))
        assertNotNull(corners)
        assertTrue(SurfaceScenes.worstError(corners!!) < tight)
    }

    @Test
    fun aProposalThatFollowsTheLightingInsteadOfThePageIsRejected() {
        // Pale wood lit from one side: the brightness split tracks the light, and the
        // finder proposes a tidy, confident and completely wrong outline. No side of it
        // lies on a paper edge, so verification throws it out and Grok gets asked.
        val scene = SurfaceScenes.render(Surface.LIGHT_WOOD, lightingGradient = true)
        assertNull(locatePage(scene))
    }

    @Test
    fun whitePaperOnWhiteMarbleIsLeftToGrok() {
        for (sideLit in allLighting) {
            assertNull(locatePage(SurfaceScenes.render(Surface.WHITE_MARBLE, lightingGradient = sideLit)))
        }
    }

    @Test
    fun roughCornersAreSquaredUpOnEverySurface() {
        // What happens after Grok (or the user's hand) puts the corners ~2% out.
        for (surface in Surface.values()) {
            for (sideLit in allLighting) {
                val scene = SurfaceScenes.render(surface, lightingGradient = sideLit)
                val refined = refineCornersDetailed(scene, SurfaceScenes.GROK_LIKE)
                val error = SurfaceScenes.worstError(refined.corners)
                assertTrue("$surface sideLit=$sideLit confirmed ${refined.confirmedSides}", refined.confirmedSides == 4)
                assertTrue("$surface sideLit=$sideLit error $error", error < tight)
            }
        }
    }

    @Test
    fun refinementNeverCropsToTheTextBlock() {
        // The white-marble failure: paper and surface barely differ, so the printed
        // block was the strongest boundary in reach and the page was cropped to it.
        val scene = SurfaceScenes.render(Surface.WHITE_MARBLE)
        val refined = refineCorners(scene, SurfaceScenes.GROK_LIKE)
        val shrink = 1f - quadArea(refined) / quadArea(SurfaceScenes.PAGE)
        assertTrue("page area changed by $shrink", kotlin.math.abs(shrink) < 0.02f)
    }

    @Test
    fun refinementDoesNoHarmWhenItCannotHelp() {
        // Corners nowhere near any page: the honest answer is the input, untouched.
        val scene = SurfaceScenes.render(Surface.DARK_WOOD)
        val far = DocumentCorners(
            NormPoint(0.02f, 0.02f), NormPoint(0.12f, 0.02f),
            NormPoint(0.12f, 0.09f), NormPoint(0.02f, 0.09f)
        )
        val result = refineCornersDetailed(scene, far)
        assertTrue(result.corners == far && result.confirmedSides == 0)
    }
}
