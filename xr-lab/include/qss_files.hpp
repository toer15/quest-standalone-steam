#pragma once
#include "qss_protocol.hpp"
#include <cstdio>
#include <fstream>
#include <string>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif
namespace qss {
inline bool readFile(const std::string& path, Bytes& b) {
    std::ifstream in(path,std::ios::binary|std::ios::ate);
    if(!in) return false;
    auto size=in.tellg(); if(size<0 || size>std::streamoff(kMaxMessage)) return false;
    b.resize(size_t(size)); in.seekg(0); return bool(in.read(reinterpret_cast<char*>(b.data()),size));
}
// One writer per file. Rename keeps partial messages invisible to the reader.
inline bool writeFile(const std::string& path,const Bytes& b) {
    if(b.size()>kMaxMessage) return false;
    std::string tmp=path+".tmp";
    { std::ofstream out(tmp,std::ios::binary|std::ios::trunc);
      if(!out || !out.write(reinterpret_cast<const char*>(b.data()),std::streamsize(b.size()))) return false;
      out.close(); if(out.fail()) return false; }
#ifdef _WIN32
    return MoveFileExA(tmp.c_str(),path.c_str(),MOVEFILE_REPLACE_EXISTING)!=0;
#else
    return std::rename(tmp.c_str(),path.c_str())==0;
#endif
}
} // namespace qss
