#include <jni.h>
#include <arm_neon.h>
#include <cmath>

// Each phase bank is row-major. Interpolating the two affine outputs before
// ELU is algebraically identical to interpolating the weights and biases.
namespace {
constexpr int inputSize=342, hidden=512, outputSize=311;
constexpr int bankSize=(inputSize+1)*hidden+(hidden+1)*hidden+(hidden+1)*outputSize;
void layer(const float *a,const float *b,const float *x,float *y,int rows,int cols,float t,bool elu){
    for(int r=0;r<rows;r++){
        auto av=vdupq_n_f32(0), bv=vdupq_n_f32(0);
        const float *wa=a+r*cols, *wb=b+r*cols;
        int c=0;
        for(;c+3<cols;c+=4){
            auto xv=vld1q_f32(x+c);
            av=vfmaq_f32(av,xv,vld1q_f32(wa+c)); bv=vfmaq_f32(bv,xv,vld1q_f32(wb+c));
        }
        float va=vaddvq_f32(av)+a[rows*cols+r], vb=vaddvq_f32(bv)+b[rows*cols+r];
        for(;c<cols;c++){va+=x[c]*wa[c];vb+=x[c]*wb[c];}
        float v=va+t*(vb-va);
        y[r]=(elu && v<0)?std::expm1(v):v;
    }
}
}
extern "C" JNIEXPORT void JNICALL Java_com_example_terrainrunner_Network_predict(
    JNIEnv *env,jobject,jobject weights,jfloat phase,jfloatArray input,jfloatArray output){
    const float *base=static_cast<const float*>(env->GetDirectBufferAddress(weights));
    float x[inputSize],h0[hidden],h1[hidden],y[outputSize];
    env->GetFloatArrayRegion(input,0,inputSize,x);
    for(int i=0;i<inputSize;i++)x[i]=(x[i]-base[i])/base[inputSize+i];
    const float *means=base+2*inputSize, *banks=means+2*outputSize;
    float index=phase*10;
    int k=static_cast<int>(index)%10;
    auto a=banks+bankSize*k,b=banks+bankSize*((k+1)%10);
    const float t=index-std::floor(index);
    layer(a,b,x,h0,hidden,inputSize,t,true);
    a+=(inputSize+1)*hidden;b+=(inputSize+1)*hidden;
    layer(a,b,h0,h1,hidden,hidden,t,true);
    a+=(hidden+1)*hidden;b+=(hidden+1)*hidden;
    layer(a,b,h1,y,outputSize,hidden,t,false);
    for(int i=0;i<outputSize;i++)y[i]=y[i]*means[outputSize+i]+means[i];
    env->SetFloatArrayRegion(output,0,outputSize,y);
}
