#pragma once
#include "qss_protocol.hpp"
#include <algorithm>
namespace qss {
inline float sphere(Vec3 origin,Vec3 direction,Vec3 center,float radius) {
    Vec3 oc=origin-center; float b=dot(oc,direction),c=dot(oc,oc)-radius*radius,d=b*b-c;
    if(d<0) return -1;
    float t=-b-std::sqrt(d); return t>.01f?t:-1;
}
struct Scene {
    unsigned score=0; bool wasPressed=false;
    Vec3 target() const { return {float(int(score%3)-1)*.65f,0,-2.8f}; }
    void update(const Tracking& t) {
        bool pressed=false,hit=false;
        for(auto& h:t.hands) if(h.active && t.focused) {
            pressed|=h.trigger>.7f;
            if(h.trigger>.7f) hit|=sphere(h.aim.position,rotate(h.aim.orientation,{0,0,-1}),target(),.25f)>0;
        }
        if(pressed && !wasPressed && hit) ++score;
        wasPressed=pressed;
    }
    Frame render(const Tracking& t,uint32_t width=192,uint32_t height=192) const {
        Frame f{t.sequence,width,height,Bytes(pixelBytes(width,height))};
        for(unsigned eye=0;eye<2;++eye) for(uint32_t y=0;y<height;++y) for(uint32_t x=0;x<width;++x) {
            const auto& v=t.eyes[eye]; Vec3 dir=ray(v,(float(x)+.5f)/width,(float(y)+.5f)/height);
            Vec3 color{.025f,.045f,.085f}; float depth=1000;
            if(dir.y<-.001f) { float d=(-1.5f-v.pose.position.y)/dir.y; if(d>0) {
                Vec3 p=v.pose.position+dir*d; bool grid=(int(std::floor(p.x))+int(std::floor(p.z)))%2==0;
                color=grid?Vec3{.10f,.14f,.19f}:Vec3{.04f,.07f,.11f}; depth=d;
            } }
            float d=sphere(v.pose.position,dir,target(),.25f);
            if(d>0 && d<depth) { depth=d; color={.2f,.85f,.45f}; }
            for(unsigned i=0;i<2;++i) if(t.hands[i].active) {
                d=sphere(v.pose.position,dir,t.hands[i].aim.position,.055f);
                if(d>0 && d<depth) { depth=d; color=i==0?Vec3{.25f,.55f,1}:Vec3{1,.4f,.2f}; }
                // A marker 0.5 m along each controller's aim ray makes orientation visible.
                auto mark=t.hands[i].aim.position+rotate(t.hands[i].aim.orientation,{0,0,-.5f});
                d=sphere(v.pose.position,dir,mark,.018f);
                if(d>0 && d<depth) { depth=d; color={1,1,1}; }
            }
            size_t n=((eye*height+y)*width+x)*4;
            f.rgba[n]=uint8_t(std::clamp(color.x,0.f,1.f)*255);
            f.rgba[n+1]=uint8_t(std::clamp(color.y,0.f,1.f)*255);
            f.rgba[n+2]=uint8_t(std::clamp(color.z,0.f,1.f)*255); f.rgba[n+3]=255;
        }
        return f;
    }
};
} // namespace qss
