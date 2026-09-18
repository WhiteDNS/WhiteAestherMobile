package com.whitedns.whiteaesther.service

import com.whitedns.whiteaesther.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetFacesTest {
    /**
     * The widget reports the engine, never the tap.
     *
     * One that turned green on click would call a connection that is still
     * negotiating done, and stay green for one that failed -- and the failures
     * are exactly what this app's users need to see.
     */
    @Test
    fun everyStageIsToldApartFromTheOthers() {
        // PREPARING and CONNECTING are one thing to a user -- "it is working on
        // it" -- so they share a face on purpose. Everything else is its own.
        val distinct = listOf(
            EngineStage.IDLE,
            EngineStage.CONNECTING,
            EngineStage.CONNECTED,
            EngineStage.STOPPING,
            EngineStage.ERROR,
        )
        val states = distinct.map { WidgetFaces.of(it).state }
        assertEquals("a stage is drawn as another one", states.size, states.toSet().size)
        assertEquals(
            WidgetFaces.of(EngineStage.PREPARING),
            WidgetFaces.of(EngineStage.CONNECTING),
        )
        // The three that must never be mistaken for each other at a glance.
        val dots = listOf(EngineStage.CONNECTED, EngineStage.CONNECTING, EngineStage.ERROR)
            .map { WidgetFaces.of(it).dot }
        assertEquals(dots.size, dots.toSet().size)
        assertNotEquals(WidgetFaces.of(EngineStage.CONNECTED).dot, WidgetFaces.of(EngineStage.IDLE).dot)
    }

    /**
     * A search that is going nowhere can be stopped from the home screen.
     *
     * This is when a user actually reaches for the widget. Offering nothing
     * while the engine hunts for a route is what sends them into the app to
     * force-stop it, on the version where hunting could take minutes.
     */
    @Test
    fun aConnectionInProgressCanBeStopped() {
        for (stage in listOf(EngineStage.PREPARING, EngineStage.CONNECTING)) {
            assertEquals(stage.name, WidgetAction.STOP, WidgetFaces.of(stage).tap)
        }
        assertEquals(WidgetAction.STOP, WidgetFaces.of(EngineStage.CONNECTED).tap)
    }

    /**
     * A tap never asks for what is already happening.
     *
     * The service takes a stop through a mutex and waits on the session job, so
     * a second one only queues behind the first and makes the wait longer.
     */
    @Test
    fun stoppingOffersNothingToTap() {
        assertEquals(WidgetAction.NONE, WidgetFaces.of(EngineStage.STOPPING).tap)
    }

    /** A failure offers the retry, rather than only reporting bad news. */
    @Test
    fun aFailureOffersToConnectAgain() {
        assertEquals(WidgetAction.CONNECT, WidgetFaces.of(EngineStage.ERROR).tap)
        assertEquals(WidgetAction.CONNECT, WidgetFaces.of(EngineStage.IDLE).tap)
        assertEquals(R.string.tile_failed, WidgetFaces.of(EngineStage.ERROR).state)
    }
}
