#pragma once
// Original QSS lab protocol. Little-endian, versioned, bounded file messages.
// This is deliberately NOT an OpenVR or OpenXR ABI implementation.
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace qss {
using Bytes = std::vector<uint8_t>;
constexpr uint32_t kMagic = 0x31535351; // QSS1
constexpr uint32_t kVersion = 1;
constexpr uint32_t kMaxDimension = 512;
constexpr size_t kMaxMessage = 2 * kMaxDimension * kMaxDimension * 4 + 128;
struct Vec3 { float x=0, y=0, z=0; };
struct Quat { float x=0, y=0, z=0, w=1; };
struct Pose { Quat orientation; Vec3 position; };
struct View { Pose pose; float left=-.8f, right=.8f, up=.8f, down=-.8f; };
struct Hand { Pose aim; float trigger=0; bool active=false; };
struct Tracking {
    uint64_t sequence=0;
    std::array<View,2> eyes;
    std::array<Hand,2> hands;
    bool focused=false;
};
struct Frame {
    uint64_t sequence=0;
    uint32_t width=0, height=0;
    Bytes rgba; // left then right, top row first, RGBA8
};
struct Writer {
    Bytes bytes;
    void u32(uint32_t x) { for(int i=0;i<4;++i) bytes.push_back(uint8_t(x>>(8*i))); }
    void u64(uint64_t x) { u32(uint32_t(x)); u32(uint32_t(x>>32)); }
    void f32(float x) { uint32_t v; static_assert(sizeof(x)==4); std::memcpy(&v,&x,4); u32(v); }
    void pose(const Pose& p) {
        f32(p.orientation.x); f32(p.orientation.y); f32(p.orientation.z); f32(p.orientation.w);
        f32(p.position.x); f32(p.position.y); f32(p.position.z);
    }
};
struct Reader {
    const Bytes& b; size_t at=0;
    uint32_t u32() {
        if(at+4>b.size()) throw std::runtime_error("Truncated QSS message");
        uint32_t v=0; for(int i=0;i<4;++i) v|=uint32_t(b[at++])<<(8*i); return v;
    }
    uint64_t u64() { auto lo=u32(); auto hi=u32(); return uint64_t(lo)|(uint64_t(hi)<<32); }
    float f32() { auto v=u32(); float f; std::memcpy(&f,&v,4); if(!std::isfinite(f)) throw std::runtime_error("Invalid float"); return f; }
    Pose pose() {
        Pose p; p.orientation={f32(),f32(),f32(),f32()}; p.position={f32(),f32(),f32()};
        const auto& q=p.orientation; float n=q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w;
        if(n<.9f || n>1.1f || std::fabs(p.position.x)>10000 || std::fabs(p.position.y)>10000 || std::fabs(p.position.z)>10000)
            throw std::runtime_error("Invalid pose");
        return p;
    }
    void end() { if(at!=b.size()) throw std::runtime_error("Trailing QSS data"); }
};
inline Bytes envelope(uint32_t type, const Bytes& payload) {
    if(payload.size()+16>kMaxMessage) throw std::runtime_error("QSS message too large");
    Writer w; w.u32(kMagic); w.u32(kVersion); w.u32(type); w.u32(uint32_t(payload.size()));
    w.bytes.insert(w.bytes.end(),payload.begin(),payload.end()); return w.bytes;
}
inline Reader open(const Bytes& bytes, uint32_t type) {
    if(bytes.size()>kMaxMessage) throw std::runtime_error("QSS message too large");
    Reader r{bytes};
    if(r.u32()!=kMagic || r.u32()!=kVersion || r.u32()!=type) throw std::runtime_error("Unsupported QSS message");
    auto length=r.u32(); if(length!=bytes.size()-16) throw std::runtime_error("Invalid QSS length"); return r;
}
inline Bytes encode(const Tracking& t) {
    Writer w; w.u64(t.sequence); w.u32(t.focused?1:0);
    for(const auto& v:t.eyes) { w.pose(v.pose); w.f32(v.left); w.f32(v.right); w.f32(v.up); w.f32(v.down); }
    for(const auto& h:t.hands) { w.pose(h.aim); w.f32(h.trigger); w.u32(h.active?1:0); }
    return envelope(1,w.bytes);
}
inline Tracking tracking(const Bytes& bytes) {
    auto r=open(bytes,1); Tracking t; t.sequence=r.u64(); auto focus=r.u32();
    if(focus>1) throw std::runtime_error("Invalid focus");
    t.focused=focus!=0;
    for(auto& v:t.eyes) {
        v.pose=r.pose(); v.left=r.f32(); v.right=r.f32(); v.up=r.f32(); v.down=r.f32();
        if(!(v.left<v.right && v.down<v.up) || std::fabs(v.left)>1.55f || std::fabs(v.right)>1.55f ||
           std::fabs(v.up)>1.55f || std::fabs(v.down)>1.55f) throw std::runtime_error("Invalid FOV");
    }
    for(auto& h:t.hands) { h.aim=r.pose(); h.trigger=r.f32(); auto a=r.u32();
        if(h.trigger<0 || h.trigger>1 || a>1) throw std::runtime_error("Invalid hand");
        h.active=a!=0;
    }
    r.end(); return t;
}
inline size_t pixelBytes(uint32_t w,uint32_t h) {
    if(w==0 || h==0 || w>kMaxDimension || h>kMaxDimension) throw std::runtime_error("Invalid frame size");
    return size_t(w)*h*2*4;
}
inline Bytes encode(const Frame& f) {
    if(f.rgba.size()!=pixelBytes(f.width,f.height)) throw std::runtime_error("Invalid pixel count");
    Writer w; w.u64(f.sequence); w.u32(f.width); w.u32(f.height);
    w.bytes.insert(w.bytes.end(),f.rgba.begin(),f.rgba.end()); return envelope(2,w.bytes);
}
inline Frame frame(const Bytes& bytes) {
    auto r=open(bytes,2); Frame f; f.sequence=r.u64(); f.width=r.u32(); f.height=r.u32();
    auto count=pixelBytes(f.width,f.height); if(bytes.size()-r.at!=count) throw std::runtime_error("Invalid pixel data");
    f.rgba.assign(bytes.begin()+r.at,bytes.end()); return f;
}
inline Vec3 operator+(Vec3 a,Vec3 b) { return {a.x+b.x,a.y+b.y,a.z+b.z}; }
inline Vec3 operator-(Vec3 a,Vec3 b) { return {a.x-b.x,a.y-b.y,a.z-b.z}; }
inline Vec3 operator*(Vec3 a,float s) { return {a.x*s,a.y*s,a.z*s}; }
inline float dot(Vec3 a,Vec3 b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
inline Vec3 cross(Vec3 a,Vec3 b) { return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x}; }
inline Vec3 rotate(Quat q,Vec3 v) { Vec3 u{q.x,q.y,q.z}; return v+cross(u,cross(u,v)+v*q.w)*2; }
inline Vec3 normalized(Vec3 a) { float n=std::sqrt(dot(a,a)); return n>0?a*(1/n):Vec3{}; }
inline Vec3 ray(const View& v,float x,float y) {
    float tx=std::tan(v.left)+(std::tan(v.right)-std::tan(v.left))*x;
    float ty=std::tan(v.up)+(std::tan(v.down)-std::tan(v.up))*y;
    return normalized(rotate(v.pose.orientation,{tx,ty,-1}));
}
} // namespace qss
