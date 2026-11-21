package com.example.terrainrunner

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bulk buffers avoid per-vertex MeshModel/JNI conversion on every brush preview. */
internal class EditableSurface(source:MeshResource,name:String):AutoCloseable {
    val positions=source.getPositions()
    val mesh:MeshResource
    private val descriptor:RawMeshDescriptor
    private val indices=source.getTriangleIndices()
    private val writer=object:RawMeshBufferWriter{
        override fun write(buffer:ByteBuffer):Boolean{
            buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().put(positions)
            return true
        }
    }
    private val streams=mutableListOf<RawMeshBufferWriter>(writer)
    init{
        val bounds=source.getBoundingBox()
        // Cover the entire allowed edit range, including future edits, without rebuilding bounds.
        val low=bounds.min-Vector3(0f,.65f*Course.CELL,0f)
        val high=bounds.max+Vector3(0f,1.8f*Course.CELL,0f)
        RawMeshDescriptorBuilder().use{builder->
            builder.vertexCount(positions.size/3).indexCount(indices.size)
                .indexType(MeshIndexSizeType.UNSIGNED_INT)
                .boundingBox(BoundingBox((low+high)*.5f,(high-low)*.5f))
                .streamLayout(RawMeshStreamLayout(0,0,12))
                .streamAttribute(RawMeshStreamAttribute(MeshVertexAttribute.POSITION,MeshVertexAttributeType.FLOAT3,false,0,0))
                .addSubMesh(0,indices.size,0,name)
            fun attribute(kind:MeshVertexAttribute,type:MeshVertexAttributeType,width:Int,data:FloatArray?){
                if(data==null)return
                val slot=streams.size
                builder.streamLayout(RawMeshStreamLayout(slot,0,width*4))
                    .streamAttribute(RawMeshStreamAttribute(kind,type,false,0,slot))
                streams+=object:RawMeshBufferWriter{
                    override fun write(buffer:ByteBuffer):Boolean{
                        buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
                        return true
                    }
                }
            }
            attribute(MeshVertexAttribute.NORMAL,MeshVertexAttributeType.FLOAT3,3,source.getNormals())
            attribute(MeshVertexAttribute.UV0,MeshVertexAttributeType.FLOAT2,2,source.getUv0())
            source.getColors()?.let{data->
                val slot=streams.size
                builder.streamLayout(RawMeshStreamLayout(slot,0,4))
                    .streamAttribute(RawMeshStreamAttribute(MeshVertexAttribute.COLOR,MeshVertexAttributeType.UBYTE4,true,0,slot))
                streams+=object:RawMeshBufferWriter{
                    override fun write(buffer:ByteBuffer):Boolean{buffer.put(data);return true}
                }
            }
            descriptor=builder.build()
        }
        mesh=raw().use{MeshResource.createWithRawMesh(it,name).apply{toGlobal()}}
    }
    private fun raw():RawMesh{
        val raw=RawMesh.create(descriptor)
        try{
            streams.forEachIndexed{i,stream->check(raw.populateVertexBuffer(i,stream))}
            check(raw.populateIndicesBuffer(object:RawMeshBufferWriter{
                override fun write(buffer:ByteBuffer):Boolean{
                    buffer.order(ByteOrder.nativeOrder()).asIntBuffer().put(indices)
                    return true
                }
            }))
            return raw
        }catch(e:Throwable){raw.close();throw e}
    }
    fun upload(){
        // Static buffer replacement also works during offstage/cancel/undo. Dynamic slot updates
        // can wait 500 ms for a render frame if the scene stops presenting during a gesture.
        raw().use{check(mesh.replaceWithRawMesh(it)){"Could not update $mesh positions"}}
    }
    override fun close(){mesh.close();descriptor.close()}
}
