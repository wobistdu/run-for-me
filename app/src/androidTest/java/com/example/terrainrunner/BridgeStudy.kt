package com.example.terrainrunner

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class BridgeStudy {
    @Test fun renderedShoesClearTheLogWithoutChangingInference(){
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val original=Course.load(assets)
        val edited=Course.Stroke(original,Tool.RAISE,Course.local(-.10f,.18f)).draft
        val network=Network(assets)
        val skin=RailClearance(assets)
        for((name,route) in listOf("forward" to Passage.BEAM.route,"reverse" to Passage.BEAM.route.reversed(),
                                  "reverse-edited" to Passage.BEAM.route.reversed())){
            val expected=mutableListOf<Int>()
            fun run(corrected:Boolean){
                network.reset()
                val t=Trial(original,route,network,if(corrected)network.bridgeContact else null)
                var rawMin=0f;var minGap=Float.POSITIVE_INFINITY;var peakLift=0f
                var tested=0;var contacts=0;var sculpted=false;var restored=false
                while(!t.done && t.ticks<2500){
                    if(name=="reverse-edited" && !sculpted && t.position.mx < -.086f){
                        t.useGround(edited);sculpted=true
                    }
                    if(sculpted && !restored && t.position.mx < -.12f){
                        t.useGround(original);restored=true
                    }
                    t.step()
                    val hash=requireNotNull(t.pose.output).contentHashCode()
                    if(!corrected){expected.add(hash);continue}
                    assertEquals("PFNN output at tick ${t.ticks}",expected[t.ticks-1],hash)
                    peakLift=max(peakLift,t.displayLift)
                    if(t.ticks%8==0 && t.position.mx in -.185f..-.015f){
                        val gap=skin.shoeDeckGap(t)
                        if(gap.isFinite()){
                            tested++
                            minGap=min(minGap,gap)
                            rawMin=min(rawMin,gap-t.displayLift)
                            if(abs(gap)<.00007f)contacts++
                        }
                    }
                }
                assertTrue(t.done)
                if(corrected){
                    assertEquals(expected.size,t.ticks)
                    assertEquals(0f,t.displayLift,0f)
                    Log.i("BridgeContact","$name frames=${t.ticks} sampled=$tested closeContacts=$contacts rawGap=$rawMin finalGap=$minGap maxLift=$peakLift edited=$sculpted")
                    assertTrue("$name failed to reproduce penetration: $rawMin",rawMin < -.0008f)
                    assertTrue("$name corrected gap: $minGap",minGap >= 0f)
                    assertTrue("$name shoe contact: $contacts / $tested",tested>=20 && contacts>tested/2)
                }
            }
            run(false);run(true)
        }
    }

    @Test fun balanceStrideIsEvenInBothDirections(){
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val course=Course.load(assets)
        for(route in listOf(Passage.BEAM.route,Passage.BEAM.route.reversed())){
            val trial=Trial(course,route,Network(assets))
            val steps=Array(2){mutableListOf<Float>()}
            val speed=mutableListOf<Float>()
            var previousBoundary:Float?=null
            var wristSpan=0f
            var wristHeightDifference=0f
            var sideDrop=0f
            while(!trial.done && trial.ticks<2500){
                val before=trial.position.mx
                val phase=trial.pose.phase
                trial.step()
                val after=trial.position.mx
                if(after !in -.143f..-.057f)continue
                val next=trial.pose.phase
                val wrapped=next<phase
                if(wrapped || (phase<.5f && next>=.5f)){
                    val boundary=if(wrapped)1f else .5f
                    val fraction=(boundary-phase)/(if(wrapped)1f+next-phase else next-phase)
                    val x=before+(after-before)*fraction
                    previousBoundary?.let{steps[if(wrapped)1 else 0].add(abs(x-it)/Network.MODEL_TO_METRES)}
                    previousBoundary=x
                }
                val y=requireNotNull(trial.pose.output)
                wristSpan+=abs(y[32+3*20]-y[32+3*27])
                wristHeightDifference+=abs(y[33+3*20]-y[33+3*27])
                val lateral=Point(trial.position.x,trial.position.z+25f/Network.UNIT)
                sideDrop+=(course.height(trial.position)-course.height(lateral))*Network.UNIT
                speed+=abs(after-before)/Network.MODEL_TO_METRES
            }
            assertTrue(trial.done && steps.all{it.size>=3})
            val left=steps[0].average();val right=steps[1].average()
            val asymmetry=abs(left-right)/((left+right)*.5)
            val mean=speed.average()
            val cv=sqrt(speed.map{(it-mean).pow(2)}.average())/mean
            Log.i("BridgeStride","startX=${route.first().mx} halfStrides=$left,$right asymmetry=$asymmetry speedCV=$cv wristSpan=${wristSpan/speed.size} meanWristHeightDifference=${wristHeightDifference/speed.size} sideDrop=${sideDrop/speed.size}")
            assertTrue("half-cycle distance imbalance $asymmetry",asymmetry<.065)
            assertTrue("wrist height imbalance",wristHeightDifference/speed.size<16f)
            assertTrue("balance wrist span",wristSpan/speed.size>65f)
        }
    }
}
