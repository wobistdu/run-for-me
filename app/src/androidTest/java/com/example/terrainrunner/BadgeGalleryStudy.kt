package com.example.terrainrunner

import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.core.ecs.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BadgeGalleryStudy {
    @Test fun eachMedalCanTurnOverAndReturnsToItsSlot()=runBlocking {
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val state=RidgeSession(Network(assets),Course.load(assets))
        val meshes=TrailBadge.entries.associateWith{Entity.loadSuspend(it.modelUri)}
        val prefs=InstrumentationRegistry.getInstrumentation().context.getSharedPreferences("gallery-unlock-test",0)
        prefs.edit().clear().commit()
        val book=FieldBook(prefs)
        withContext(Dispatchers.Main){
            val board=Entity()
            val gallery=TrailBadges(board,.001f,meshes)
            try{
                gallery.place(.60f,.20f)
                fun update()=gallery.update(state.earnedDays,state.selectedBadge,state.badgeYaw)
                update()
                for((badge,mesh) in meshes){
                    val slot=mesh.tr.position
                    for(day in state.earnedDays+1 until badge.days){
                        book.record(ExpeditionResult(day.toLong(),3,70.0,3,10))
                    }
                    state.loadBook(book)
                    assertEquals(badge.days-1,state.earnedDays)
                    state.selectedBadge=badge;update()
                    assertNull(state.selectedBadge)
                    assertFalse(gallery.previewHit.enabled)
                    assertTrue(meshes.values.all{it.enabled})
                    assertEquals(slot,mesh.tr.position)
                    book.record(ExpeditionResult(badge.days.toLong(),3,70.0,3,10))
                    state.loadBook(book)
                    state.selectedBadge=badge;update()
                    assertEquals(badge,state.selectedBadge)
                    assertTrue(gallery.previewHit.enabled)
                    assertEquals(1,meshes.values.count{it.enabled})
                    val front=mesh.tr.quaternion
                    state.rotateBadge(180f);update()
                    val back=mesh.tr.quaternion
                    assertTrue(abs(front.x*back.x+front.y*back.y+front.z*back.z+front.w*back.w)<.01f)
                    state.rotateBadge(-180f);update()
                    assertEquals(0f,state.badgeYaw,0f)
                    state.selectedBadge=null;update()
                    assertEquals(slot,mesh.tr.position)
                    assertFalse(gallery.previewHit.enabled)
                    assertTrue(meshes.values.all{it.enabled})
                }
            }finally{board.destroy(true);gallery.close();prefs.edit().clear().commit()}
        }
    }
}
