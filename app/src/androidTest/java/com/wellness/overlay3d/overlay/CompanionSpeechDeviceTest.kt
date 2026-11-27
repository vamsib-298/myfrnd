package com.wellness.overlay3d.overlay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CompanionSpeechDeviceTest {
    @Test fun bundledVoiceSynthesizesAndPlaysACustomReminder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val speaker = CompanionSpeech()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                speaker.speak("Remember to take your blue notebook.", { playing ->
                    if (playing) started.countDown() else finished.countDown()
                }, { throw AssertionError("The bundled voice fell back to device TTS") })
            }
            assertTrue("Voice playback should start", started.await(45, TimeUnit.SECONDS))
            assertTrue("Voice playback should finish", finished.await(30, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { speaker.close() }
        }
    }
}
