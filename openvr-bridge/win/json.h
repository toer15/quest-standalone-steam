// Minimal JSON reader for SteamVR action manifests and binding files.
#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

namespace qss {

struct Json {
    enum Type { Null, Bool, Number, String, Array, Object } type = Null;
    bool b = false;
    double n = 0;
    std::string s;
    std::vector<Json> a;
    std::vector<std::pair<std::string, Json>> o;

    const Json& operator[](const char* key) const {
        static const Json none;
        if (type != Object) return none;
        for (auto& kv : o) if (kv.first == key) return kv.second;
        return none;
    }
    const std::string& str() const { return s; }
    bool isObject() const { return type == Object; }
    bool isArray() const { return type == Array; }

    static Json Parse(const std::string& text, bool* ok = nullptr) {
        size_t i = 0;
        Json j;
        bool good = ParseValue(text, i, j);
        if (ok) *ok = good;
        return j;
    }

private:
    static void Ws(const std::string& t, size_t& i) {
        while (i < t.size()) {
            char c = t[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { ++i; continue; }
            if (c == '/' && i + 1 < t.size() && t[i + 1] == '/') { while (i < t.size() && t[i] != '\n') ++i; continue; }
            break;
        }
    }
    static bool ParseString(const std::string& t, size_t& i, std::string& out) {
        if (i >= t.size() || t[i] != '"') return false;
        ++i;
        while (i < t.size() && t[i] != '"') {
            char c = t[i++];
            if (c == '\\' && i < t.size()) {
                char e = t[i++];
                switch (e) {
                    case 'n': out += '\n'; break;
                    case 't': out += '\t'; break;
                    case 'r': out += '\r'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'u': {
                        unsigned v = 0;
                        for (int k = 0; k < 4 && i < t.size(); ++k, ++i) {
                            char h = t[i];
                            v = v * 16 + (h >= '0' && h <= '9' ? h - '0' : (h | 32) - 'a' + 10);
                        }
                        if (v < 0x80) out += (char)v;
                        else if (v < 0x800) { out += (char)(0xc0 | (v >> 6)); out += (char)(0x80 | (v & 0x3f)); }
                        else { out += (char)(0xe0 | (v >> 12)); out += (char)(0x80 | ((v >> 6) & 0x3f)); out += (char)(0x80 | (v & 0x3f)); }
                        break;
                    }
                    default: out += e;
                }
            } else out += c;
        }
        if (i >= t.size()) return false;
        ++i;
        return true;
    }
    static bool ParseValue(const std::string& t, size_t& i, Json& j) {
        Ws(t, i);
        if (i >= t.size()) return false;
        char c = t[i];
        if (c == '{') {
            j.type = Object; ++i; Ws(t, i);
            if (i < t.size() && t[i] == '}') { ++i; return true; }
            while (i < t.size()) {
                Ws(t, i);
                std::string key;
                if (!ParseString(t, i, key)) return false;
                Ws(t, i);
                if (i >= t.size() || t[i] != ':') return false;
                ++i;
                Json v;
                if (!ParseValue(t, i, v)) return false;
                j.o.emplace_back(std::move(key), std::move(v));
                Ws(t, i);
                if (i < t.size() && t[i] == ',') { ++i; Ws(t, i); if (i < t.size() && t[i] == '}') { ++i; return true; } continue; }
                if (i < t.size() && t[i] == '}') { ++i; return true; }
                return false;
            }
            return false;
        }
        if (c == '[') {
            j.type = Array; ++i; Ws(t, i);
            if (i < t.size() && t[i] == ']') { ++i; return true; }
            while (i < t.size()) {
                Json v;
                if (!ParseValue(t, i, v)) return false;
                j.a.push_back(std::move(v));
                Ws(t, i);
                if (i < t.size() && t[i] == ',') { ++i; Ws(t, i); if (i < t.size() && t[i] == ']') { ++i; return true; } continue; }
                if (i < t.size() && t[i] == ']') { ++i; return true; }
                return false;
            }
            return false;
        }
        if (c == '"') { j.type = String; return ParseString(t, i, j.s); }
        if (t.compare(i, 4, "true") == 0) { j.type = Bool; j.b = true; i += 4; return true; }
        if (t.compare(i, 5, "false") == 0) { j.type = Bool; j.b = false; i += 5; return true; }
        if (t.compare(i, 4, "null") == 0) { j.type = Null; i += 4; return true; }
        size_t start = i;
        while (i < t.size() && (isdigit((unsigned char)t[i]) || t[i] == '-' || t[i] == '+' || t[i] == '.' || t[i] == 'e' || t[i] == 'E')) ++i;
        if (i == start) return false;
        j.type = Number;
        j.n = atof(t.substr(start, i - start).c_str());
        return true;
    }
};

}  // namespace qss
