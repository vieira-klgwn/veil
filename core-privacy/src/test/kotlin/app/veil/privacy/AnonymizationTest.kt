package app.veil.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymizationTest {

    private fun face(cx: Float, cy: Float, rx: Float, ry: Float, rot: Float = 0f) =
        FaceRegion(cx, cy, rx, ry, rot)

    /** The pipeline adds a safety margin, so audits compare against the grown ellipse. */
    private fun protectedRegions(regions: List<FaceRegion>) =
        regions.map { it.scaled(PrivacyStrength.BALANCED.marginFactor) }

    @Test
    fun `dimensions and pixel count are preserved`() {
        val faces = listOf(face(300f, 400f, 40f, 52f))
        val (image, regions) = TestImages.scene(faces = faces)
        val original = image.copy()
        PrivacyPipeline.protect(image, regions, PrivacyEffect.BLUR)
        assertEquals(original.width, image.width)
        assertEquals(original.height, image.height)
        assertEquals(original.pixels.size, image.pixels.size)
    }

    @Test
    fun `pixels outside the face ellipse are byte identical`() {
        val faces = listOf(face(250f, 430f, 45f, 58f), face(640f, 450f, 30f, 38f))
        val (image, regions) = TestImages.scene(faces = faces)
        val original = image.copy()
        PrivacyPipeline.protect(image, regions, PrivacyEffect.BLUR)

        val protectedRegions = protectedRegions(regions)
        var leaked = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val inside = protectedRegions.any { PrivacyAudit.contains(it, x + 0.5f, y + 0.5f) }
                if (inside) continue
                val i = y * image.width + x
                if (original.pixels[i] != image.pixels[i]) leaked++
            }
        }
        assertEquals("no pixel outside a face may change", 0, leaked)
    }

    @Test
    fun `faces are strongly anonymized`() {
        val faces = listOf(face(300f, 420f, 48f, 60f))
        val (image, regions) = TestImages.scene(faces = faces)
        val original = image.copy()
        val outcome = PrivacyPipeline.protect(image, regions, PrivacyEffect.BLUR)

        val report = PrivacyAudit.audit(original, image, protectedRegions(regions))
        val audit = report.faces.single()
        assertTrue("face must change substantially, was ${audit.meanDifference}", audit.meanDifference > 12f)
        assertTrue("facial detail must be destroyed, retained ${audit.detailRetained}", audit.detailRetained < 0.2f)
        assertTrue("pipeline audit said unverified: ${outcome.audits}", outcome.allFacesVerified)
        assertTrue("background changed ${report.backgroundChangedFraction}", report.backgroundPreserved)
    }

    @Test
    fun `a tiny face only affects a tiny part of the photograph`() {
        val faces = listOf(face(450f, 420f, 9f, 11f))
        val (image, regions) = TestImages.scene(faces = faces)
        val outcome = PrivacyPipeline.protect(image, regions, PrivacyEffect.BLUR)
        assertTrue(
            "tiny face must not blur the scene, modified ${outcome.modifiedFraction}",
            outcome.modifiedFraction < 0.002f,
        )
        assertEquals(1, outcome.facesProtected)
    }

    @Test
    fun `the photograph is never globally blurred`() {
        val faces = listOf(face(300f, 420f, 50f, 62f), face(700f, 400f, 26f, 33f))
        val (image, regions) = TestImages.scene(faces = faces)
        val original = image.copy()
        PrivacyPipeline.protect(image, regions, PrivacyEffect.BLUR)

        // Sharpness of a building area far from any face must be unchanged.
        val buildings = IntRect(20, 380, 180, 600)
        assertEquals(
            PrivacyAudit.gradientEnergy(original, buildings),
            PrivacyAudit.gradientEnergy(image, buildings),
            1e-9,
        )
        val report = PrivacyAudit.audit(original, image, regions)
        assertTrue("whole-image change must stay local: ${report.imageChangedFraction}", report.imageChangedFraction < 0.05f)
    }

    @Test
    fun `every face in a crowd is protected`() {
        val faces = (0 until 12).map { i ->
            face(70f + i * 65f, 380f + (i % 3) * 40f, 16f + (i % 4) * 4f, 20f + (i % 4) * 5f)
        }
        val (image, regions) = TestImages.scene(faces = faces)
        val original = image.copy()
        val outcome = PrivacyPipeline.protect(image, regions, PrivacyEffect.BLUR)
        assertEquals(12, outcome.facesProtected)
        val report = PrivacyAudit.audit(original, image, regions)
        report.faces.forEachIndexed { i, a ->
            assertTrue("face $i left unprotected (detail ${a.detailRetained})", a.isProtected)
        }
    }

    @Test
    fun `rotated faces are covered by a rotated ellipse`() {
        val rotated = face(400f, 400f, 50f, 64f, rot = 32f)
        val (image, regions) = TestImages.scene(faces = listOf(rotated))
        val original = image.copy()
        PrivacyPipeline.protect(image, regions, PrivacyEffect.BLUR)
        val audit = PrivacyAudit.audit(original, image, regions).faces.single()
        assertTrue(audit.isProtected)
    }

    @Test
    fun `pixelation produces uniform blocks and keeps the background`() {
        val faces = listOf(face(320f, 430f, 46f, 58f))
        val (image, regions) = TestImages.scene(faces = faces)
        val original = image.copy()
        PrivacyPipeline.protect(image, regions, PrivacyEffect.PIXELATE)
        val report = PrivacyAudit.audit(original, image, protectedRegions(regions))
        val audit = report.faces.single()
        assertTrue("pixelation must remove detail, retained ${audit.detailRetained}", audit.detailRetained < 0.25f)
        assertTrue("background changed ${report.backgroundChangedFraction}", report.backgroundPreserved)
    }

    @Test
    fun `solid mask fully replaces the face`() {
        val faces = listOf(face(320f, 430f, 40f, 50f))
        val (image, regions) = TestImages.scene(faces = faces)
        val original = image.copy()
        PrivacyPipeline.protect(image, regions, PrivacyEffect.MASK)
        val audit = PrivacyAudit.audit(original, image, protectedRegions(regions)).faces.single()
        assertTrue(audit.meanDifference > 40f)
        assertTrue(audit.detailRetained < 0.05f)
    }

    @Test
    fun `weak protection is detected and escalated`() {
        // A flat face region: blurring a flat area changes almost nothing, so the
        // pipeline must notice and fall back to a solid mask.
        val region = face(300f, 300f, 40f, 50f)
        val image = PixelImage(600, 600, IntArray(600 * 600) { TestImages.rgb(200, 200, 200) })
        val outcome = PrivacyPipeline.protect(image, listOf(region), PrivacyEffect.BLUR)
        assertEquals(1, outcome.escalatedFaces)
        assertTrue(outcome.allFacesVerified)
    }

    @Test
    fun `processing a 12 megapixel photo with five faces is fast`() {
        val w = 4000
        val h = 3000
        val image = TestImages.skyline(w, h)
        val faces = (0 until 5).map { i -> face(600f + i * 650f, 1500f, 120f, 150f) }
        faces.forEachIndexed { i, f -> TestImages.drawFace(image, f, seed = i) }
        val outcome = PrivacyPipeline.protect(image, faces, PrivacyEffect.BLUR)
        assertEquals(5, outcome.facesProtected)
        println("12MP / 5 faces anonymization: ${outcome.elapsedMillis} ms, modified ${outcome.modifiedFraction}")
        assertTrue("anonymization took ${outcome.elapsedMillis} ms", outcome.elapsedMillis < 2000)
        assertTrue("modified ${outcome.modifiedFraction}", outcome.modifiedFraction < 0.04f)
    }
}
