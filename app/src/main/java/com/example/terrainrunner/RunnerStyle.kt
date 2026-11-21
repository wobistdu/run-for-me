package com.example.terrainrunner

enum class RunnerStyle(val title:String,val folder:String){
    ORIGINAL("林溪","ridge"),
    STRIDE("陆野","stride");

    internal fun bindOffset(j:Int)=j==19 || j==20 || j==26 || j==27 || (this!=ORIGINAL && (j==18 || j==25))
    companion object{
        fun saved(name:String?)=entries.firstOrNull{it.name==name}?:ORIGINAL
    }
    val modelUri get()="asset://$folder/runner.glb"
}
