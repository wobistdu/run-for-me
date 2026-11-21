#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace {
struct Point { float x, y, z; };
struct Influence { float bone, weight, x, y, z; };
using Vertex = std::array<Influence,4>;
using Polygon = std::array<Point,8>;

float side(Point a, Point b, Point p) {
    return (b.x-a.x)*(p.z-a.z)-(b.z-a.z)*(p.x-a.x);
}

// Clip the skinned shoe face in XZ; interpolating Y retains its actual tilted surface.
int clip(const Polygon& in, int n, Point a, Point b, Polygon& out) {
    int count=0;
    Point prev=in[n-1];
    float last=side(a,b,prev);
    for (int i=0; i<n; ++i) {
        Point cur=in[i];
        float next=side(a,b,cur);
        if ((last<0)!=(next<0)) {
            float t=last/(last-next);
            out[count++]={prev.x+t*(cur.x-prev.x),prev.y+t*(cur.y-prev.y),prev.z+t*(cur.z-prev.z)};
        }
        if (next>=0) out[count++]=cur;
        prev=cur; last=next;
    }
    return count;
}

float overlapHeight(const Polygon& shoe, Point a, Point b, Point c, float lift) {
    Polygon p,q;
    int n=clip(shoe,3,a,b,p);
    if (!n) return lift;
    n=clip(p,n,b,c,q);
    if (!n) return lift;
    n=clip(q,n,c,a,p);
    float area=side(a,b,c);
    for (int i=0; i<n; ++i) {
        Point v=p[i];
        float y=a.y+((b.y-a.y)*side(c,a,v)+(c.y-a.y)*side(a,b,v))/area;
        lift=std::max(lift,y-v.y);
    }
    return lift;
}
}

// Both buffers are immutable, little-endian assets prepared by the Kotlin wrapper.
extern "C" JNIEXPORT jfloat JNICALL Java_com_example_terrainrunner_BridgeContact_measure(
    JNIEnv* env, jobject, jobject shoes, jobject deck, jint spans, jint sides,
    jfloatArray boneArray, jfloat unit, jfloat x, jfloat baseY, jfloat z, jfloat dx, jfloat dz, jint supportFoot,
    jobject flexMesh, jfloat flexWeight) {
    auto header=static_cast<const uint32_t*>(env->GetDirectBufferAddress(shoes));
    auto vertices=reinterpret_cast<const Vertex*>(header+2);
    auto indices=reinterpret_cast<const uint32_t*>(vertices+header[0]);
    auto flex=flexMesh && flexWeight>0 ? reinterpret_cast<const Vertex*>(
        static_cast<const uint32_t*>(env->GetDirectBufferAddress(flexMesh))+2) : nullptr;
    auto log=static_cast<const Point*>(env->GetDirectBufferAddress(deck));
    // Each row is an affine transform from joint space into the PFNN facing frame.
    float bones[31*12];
    env->GetFloatArrayRegion(boneArray,0,31*12,bones);
    thread_local std::vector<Point> posed;
    posed.resize(header[0]);
    const bool terrain=supportFoot>=0;
    const float infinity=std::numeric_limits<float>::infinity();
    Point lowest={0,infinity,0};
    float minX=infinity,maxX=-infinity,minZ=infinity,maxZ=-infinity;
    for (size_t i=0; i<posed.size(); ++i) {
        if (terrain && static_cast<int>(vertices[i][0].bone>=6)!=supportFoot) continue;
        float lx=0,ly=0,lz=0;
        for (size_t slot=0; slot<4; ++slot) {
            auto v=vertices[i][slot];
            if (v.weight==0) continue;
            if (flex) {
                const auto& b=flex[i][slot];
                v.x+=flexWeight*(b.x-v.x);
                v.y+=flexWeight*(b.y-v.y);
                v.z+=flexWeight*(b.z-v.z);
            }
            auto m=bones+12*static_cast<int>(v.bone);
            lx+=v.weight*(m[0]*v.x+m[1]*v.y+m[2]*v.z+m[3]);
            ly+=v.weight*(m[4]*v.x+m[5]*v.y+m[6]*v.z+m[7]);
            lz+=v.weight*(m[8]*v.x+m[9]*v.y+m[10]*v.z+m[11]);
        }
        posed[i]={x+unit*(dz*lx+dx*lz),baseY+unit*ly,z+unit*(dz*lz-dx*lx)};
        if (terrain) {
            const auto& p=posed[i];
            if (p.y<lowest.y) lowest=p;
            minX=std::min(minX,p.x);maxX=std::max(maxX,p.x);
            minZ=std::min(minZ,p.z);maxZ=std::max(maxZ,p.z);
        }
    }
    int width=sides+1;
    float step=(log[spans*width].x-log[0].x)/spans;
    float lift=terrain ? -std::numeric_limits<float>::infinity() : 0.f;
    float zStep=(log[sides].z-log[0].z)/sides;
    float patchTop=infinity;
    if (terrain) {
        auto cellX=[&](float v){return std::clamp(static_cast<int>((v-log[0].x)/step),0,spans-1);};
        auto cellZ=[&](float v){return std::clamp(static_cast<int>((v-log[0].z)/zStep),0,sides-1);};
        // A linear heightfield triangle cannot exceed its highest vertex.
        patchTop=-infinity;
        for (int k=cellX(minX); k<=cellX(maxX)+1; ++k)
            for (int j=cellZ(minZ); j<=cellZ(maxZ)+1; ++j)
                patchTop=std::max(patchTop,log[k*width+j].y);
        if (lowest.x>=log[0].x && lowest.x<=log[spans*width].x &&
            lowest.z>=log[0].z && lowest.z<=log[sides].z) {
            const auto p=log+cellX(lowest.x)*width+cellZ(lowest.z);
            // This sole vertex is a feasible contact. It seeds a lower bound
            // before clipping any faces. Keep a rounding margin below the bound.
            float u=(lowest.x-p[0].x)/(p[width].x-p[0].x);
            float v=(lowest.z-p[0].z)/(p[1].z-p[0].z);
            float y=v>u ? (1-v)*p[0].y+(v-u)*p[1].y+u*p[width+1].y
                        : (1-u)*p[0].y+(u-v)*p[width].y+v*p[width+1].y;
            lift=y-lowest.y-1e-6f;
        }
    }
    for (uint32_t i=0; i<header[1]; ++i) {
        if (terrain) {
            // The left and right shoe meshes are disconnected. The primary
            // influence belongs to that foot's shin, ankle or toe chain.
            int joint=static_cast<int>(vertices[indices[3*i]][0].bone);
            if ((joint>=6 ? 1 : 0)!=supportFoot) continue;
        }
        Point a=posed[indices[3*i]], b=posed[indices[3*i+1]], c=posed[indices[3*i+2]];
        float bottom=std::min({a.y,b.y,c.y});
        if (bottom+lift>=patchTop) continue;
        float x0=std::min({a.x,b.x,c.x}), x1=std::max({a.x,b.x,c.x});
        float z0=std::min({a.z,b.z,c.z}), z1=std::max({a.z,b.z,c.z});
        if (x1<log[0].x || x0>log[spans*width].x || z1<log[0].z || z0>log[sides].z) continue;
        int first=std::clamp(static_cast<int>((x0-log[0].x)/step),0,spans-1);
        int last=std::clamp(static_cast<int>((x1-log[0].x)/step),0,spans-1);
        Polygon face={a,b,c};
        int firstZ=terrain ? std::clamp(static_cast<int>((z0-log[0].z)/zStep),0,sides-1) : 0;
        int lastZ=terrain ? std::clamp(static_cast<int>((z1-log[0].z)/zStep),0,sides-1) : sides-1;
        for (int j=firstZ;j<=lastZ;++j) {
            if (log[j+1].z<z0 || log[j].z>z1) continue;
            for (int k=first;k<=last;++k) {
                auto p=log+k*width+j;
                if (std::max({p[0].y,p[1].y,p[width].y,p[width+1].y})<=bottom+lift) continue;
                // Same diagonal as the render mesh, wound counterclockwise in XZ.
                lift=overlapHeight(face,p[0],p[width+1],p[1],lift);
                lift=overlapHeight(face,p[0],p[width],p[width+1],lift);
            }
        }
    }
    return lift;
}
