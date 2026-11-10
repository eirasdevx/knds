/*
    KNDS — puente JNI entre la app Kotlin y el núcleo melonDS.
    Licencia: GPLv3 (igual que melonDS).
*/

#include <jni.h>

#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <string>

#include "Args.h"
#include "NDS.h"
#include "NDSCart.h"
#include "GPU.h"
#include "SPU.h"
#include "Savestate.h"
#include "SPI_Firmware.h"
#include "FreeBIOS.h"
#include "AREngine.h"
#include <cctype>
#include <cstdlib>
#include "knds_context.h"

using namespace melonDS;

namespace melonDS::Platform { extern std::string g_baseDir; }

static std::string g_lastError;

static knds::Context* ctxOf(jlong h) { return reinterpret_cast<knds::Context*>(h); }

static std::vector<u8> bytesOf(JNIEnv* env, jbyteArray arr)
{
    std::vector<u8> v;
    if (!arr) return v;
    jsize n = env->GetArrayLength(arr);
    v.resize(n);
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(v.data()));
    return v;
}

static std::string stringOf(JNIEnv* env, jstring s)
{
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

static std::vector<u8> readFile(const std::string& path)
{
    std::vector<u8> v;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return v;
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (len > 0) {
        v.resize(len);
        if (fread(v.data(), 1, len, f) != (size_t)len) v.clear();
    }
    fclose(f);
    return v;
}

static void setHostTime(NDS& nds)
{
    time_t now = time(nullptr);
    tm lt {};
    localtime_r(&now, &lt);
    nds.RTC.SetDateTime(lt.tm_year + 1900, lt.tm_mon + 1, lt.tm_mday, lt.tm_hour, lt.tm_min, lt.tm_sec);
}

static void customizeFirmware(Firmware& fw, int language, const std::u16string& nick)
{
    auto& data = fw.GetEffectiveUserData();
    if (!nick.empty()) {
        size_t len = std::min<size_t>(nick.size(), 10);
        data.NameLength = (u16)len;
        memcpy(data.Nickname, nick.data(), len * sizeof(char16_t));
    }
    if (language >= 0 && language <= 5) {
        data.Settings &= ~Firmware::Language::Reserved;
        data.Settings |= (Firmware::Language)language;
        data.ExtendedSettings.ExtendedLanguage = (Firmware::Language)language;
    }
    fw.UpdateChecksums();
}

static void boot(knds::Context* ctx)
{
    NDS& nds = *ctx->nds;
    nds.Reset();
    if (ctx->directBoot || nds.NeedsDirectBoot()) nds.SetupDirectBoot(ctx->romName);
    setHostTime(nds);
    nds.Start();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_knds_app_NdsCore_nativeSetBaseDir(JNIEnv* env, jclass, jstring dir)
{
    Platform::g_baseDir = stringOf(env, dir);
}

JNIEXPORT jstring JNICALL
Java_com_knds_app_NdsCore_nativeLastError(JNIEnv* env, jclass)
{
    return env->NewStringUTF(g_lastError.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_knds_app_NdsCore_nativeCreate(JNIEnv* env, jclass, jstring romPath, jstring savePath,
                                       jbyteArray bios9, jbyteArray bios7, jbyteArray firmware,
                                       jint sampleRate, jboolean jit, jint language, jstring nickname,
                                       jboolean threaded3D)
{
    g_lastError.clear();
    auto ctx = std::make_unique<knds::Context>();
    ctx->savePath = stringOf(env, savePath);

    NDSArgs args;
    auto b9 = bytesOf(env, bios9);
    auto b7 = bytesOf(env, bios7);
    bool realBios = b9.size() == ARM9BIOSSize && b7.size() == ARM7BIOSSize;
    if (realBios) {
        args.ARM9BIOS = std::make_unique<ARM9BIOSImage>();
        args.ARM7BIOS = std::make_unique<ARM7BIOSImage>();
        memcpy(args.ARM9BIOS->data(), b9.data(), ARM9BIOSSize);
        memcpy(args.ARM7BIOS->data(), b7.data(), ARM7BIOSSize);
    }

    auto fwData = bytesOf(env, firmware);
    if (realBios && fwData.size() >= 0x20000) {
        args.Firmware = Firmware(fwData.data(), (u32)fwData.size());
        ctx->directBoot = true; // seguimos arrancando el juego directamente (más rápido)
    } else {
        args.Firmware = Firmware(0);
    }
    std::string nickUtf8 = stringOf(env, nickname);
    std::u16string nick(nickUtf8.begin(), nickUtf8.end());
    customizeFirmware(args.Firmware, language, nick);

#ifdef JIT_ENABLED
    if (!jit) args.JIT = std::nullopt;
#else
    args.JIT = std::nullopt;
#endif
    args.OutputSampleRate = (double)sampleRate;
    args.Interpolation = AudioInterpolation::Cubic;

    ctx->nds = std::make_unique<NDS>(std::move(args), ctx.get());

    RendererSettings rs {1, (bool)threaded3D, false, false};
    ctx->nds->GetRenderer().SetRenderSettings(rs);

    // La ROM se lee directamente del archivo (puede ocupar cientos de MB)
    std::unique_ptr<u8[]> romData;
    u32 romLen = 0;
    {
        FILE* f = fopen(stringOf(env, romPath).c_str(), "rb");
        if (!f) {
            g_lastError = "No se pudo abrir el archivo de la ROM.";
            return 0;
        }
        fseek(f, 0, SEEK_END);
        long len = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (len < 0x200 || len > 0x20000000) {
            fclose(f);
            g_lastError = len < 0x200 ? "El archivo es demasiado pequeño para ser una ROM de DS."
                                      : "La ROM supera los 512 MB.";
            return 0;
        }
        romData = std::make_unique<u8[]>(len);
        bool ok = fread(romData.get(), 1, len, f) == (size_t)len;
        fclose(f);
        if (!ok) {
            g_lastError = "Error leyendo la ROM.";
            return 0;
        }
        romLen = (u32)len;
    }
    auto save = readFile(ctx->savePath);
    NDSCart::NDSCartArgs cartArgs {};
    if (!save.empty()) {
        cartArgs.SRAM = std::make_unique<u8[]>(save.size());
        memcpy(cartArgs.SRAM.get(), save.data(), save.size());
        cartArgs.SRAMLength = (u32)save.size();
    }
    auto cart = NDSCart::ParseROM(std::move(romData), romLen, ctx.get(), std::move(cartArgs));
    if (!cart) {
        g_lastError = "melonDS no reconoce esta ROM de DS.";
        return 0;
    }
    ctx->nds->SetNDSCart(std::move(cart));
    boot(ctx.get());
    return reinterpret_cast<jlong>(ctx.release());
}

JNIEXPORT void JNICALL
Java_com_knds_app_NdsCore_nativeDestroy(JNIEnv*, jclass, jlong h)
{
    auto* ctx = ctxOf(h);
    if (!ctx) return;
    ctx->nds->Stop();
    delete ctx;
}

JNIEXPORT void JNICALL
Java_com_knds_app_NdsCore_nativeReset(JNIEnv*, jclass, jlong h)
{
    boot(ctxOf(h));
}

JNIEXPORT jint JNICALL
Java_com_knds_app_NdsCore_nativeRunFrame(JNIEnv*, jclass, jlong h)
{
    auto* ctx = ctxOf(h);
    if (ctx->stopped) return -1;
    return (jint)ctx->nds->RunFrame();
}

JNIEXPORT jboolean JNICALL
Java_com_knds_app_NdsCore_nativeGetScreens(JNIEnv* env, jclass, jlong h, jintArray out)
{
    auto* ctx = ctxOf(h);
    void* top = nullptr;
    void* bottom = nullptr;
    if (!ctx->nds->GPU.GetFramebuffers(&top, &bottom) || !top || !bottom) return JNI_FALSE;
    env->SetIntArrayRegion(out, 0, 256 * 192, reinterpret_cast<const jint*>(top));
    env->SetIntArrayRegion(out, 256 * 192, 256 * 192, reinterpret_cast<const jint*>(bottom));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_knds_app_NdsCore_nativeSetInput(JNIEnv*, jclass, jlong h, jint keys, jboolean touch, jint x, jint y)
{
    auto* ctx = ctxOf(h);
    ctx->nds->SetKeyMask(~(u32)keys & 0xFFF); // melonDS usa lógica inversa (0 = pulsado)
    if (touch) ctx->nds->TouchScreen((u16)std::clamp(x, 0, 255), (u16)std::clamp(y, 0, 191));
    else ctx->nds->ReleaseScreen();
}

JNIEXPORT void JNICALL
Java_com_knds_app_NdsCore_nativeSetMicBlow(JNIEnv*, jclass, jlong h, jboolean on)
{
    ctxOf(h)->micBlow = on;
}

JNIEXPORT void JNICALL
Java_com_knds_app_NdsCore_nativeSetMicLive(JNIEnv*, jclass, jlong h, jboolean on)
{
    auto* ctx = ctxOf(h);
    std::lock_guard<std::mutex> l(ctx->micLock);
    ctx->micLive = on;
    ctx->micRead = ctx->micWrite = ctx->micLevel = 0;
}

/** Muestras del micrófono real (mono, 44,1 kHz). Si el búfer se llena, se descartan las más viejas. */
JNIEXPORT void JNICALL
Java_com_knds_app_NdsCore_nativePushMic(JNIEnv* env, jclass, jlong h, jshortArray samples, jint count)
{
    auto* ctx = ctxOf(h);
    jshort* buf = env->GetShortArrayElements(samples, nullptr);
    {
        std::lock_guard<std::mutex> l(ctx->micLock);
        size_t cap = ctx->micRing.size();
        for (jint i = 0; i < count; i++) {
            ctx->micRing[ctx->micWrite] = buf[i];
            ctx->micWrite = (ctx->micWrite + 1) % cap;
            if (ctx->micLevel < cap) ctx->micLevel++;
            else ctx->micRead = (ctx->micRead + 1) % cap;
        }
    }
    env->ReleaseShortArrayElements(samples, buf, JNI_ABORT);
}

/**
 * Trucos Action Replay activos. Cada elemento es un código: pares de palabras hexadecimales
 * de 32 bits separadas por espacios o saltos de línea. Una lista vacía los desactiva.
 */
JNIEXPORT jboolean JNICALL
Java_com_knds_app_NdsCore_nativeSetCheats(JNIEnv* env, jclass, jlong h, jobjectArray codes)
{
    auto* ctx = ctxOf(h);
    std::vector<ARCode> list;
    bool ok = true;
    jsize n = codes ? env->GetArrayLength(codes) : 0;
    for (jsize i = 0; i < n; i++) {
        auto js = (jstring)env->GetObjectArrayElement(codes, i);
        std::string text = stringOf(env, js);
        env->DeleteLocalRef(js);
        ARCode code {};
        code.Parent = nullptr;
        code.Name = "knds";
        code.Enabled = true;
        std::string word;
        auto flush = [&]() {
            if (word.empty()) return;
            if (word.size() > 8) ok = false;
            else code.Code.push_back((u32)strtoul(word.c_str(), nullptr, 16));
            word.clear();
        };
        for (char c : text) {
            if (isxdigit((unsigned char)c)) word += c;
            else flush();
        }
        flush();
        if (code.Code.empty() || (code.Code.size() & 1)) { ok = false; continue; }
        list.push_back(std::move(code));
    }
    ctx->nds->AREngine.Cheats = std::move(list);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_knds_app_NdsCore_nativeSetLidClosed(JNIEnv*, jclass, jlong h, jboolean closed)
{
    ctxOf(h)->nds->SetLidClosed(closed);
}

JNIEXPORT jint JNICALL
Java_com_knds_app_NdsCore_nativeReadAudio(JNIEnv* env, jclass, jlong h, jshortArray out)
{
    auto* ctx = ctxOf(h);
    jsize cap = env->GetArrayLength(out) / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    int n = ctx->nds->SPU.ReadOutput(reinterpret_cast<s16*>(buf), cap);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_knds_app_NdsCore_nativeFlushSave(JNIEnv*, jclass, jlong h)
{
    auto* ctx = ctxOf(h);
    std::vector<u8> copy;
    {
        std::lock_guard<std::mutex> l(ctx->saveLock);
        if (!ctx->saveDirty) return JNI_FALSE;
        copy = ctx->saveData;
        ctx->saveDirty = false;
    }
    std::string tmp = ctx->savePath + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return JNI_FALSE;
    bool ok = fwrite(copy.data(), 1, copy.size(), f) == copy.size();
    ok = (fclose(f) == 0) && ok;
    if (ok) ok = rename(tmp.c_str(), ctx->savePath.c_str()) == 0;
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_knds_app_NdsCore_nativeSaveState(JNIEnv* env, jclass, jlong h)
{
    auto* ctx = ctxOf(h);
    Savestate st;
    if (st.Error || !ctx->nds->DoSavestate(&st) || st.Error) return nullptr;
    st.Finish();
    jbyteArray arr = env->NewByteArray((jsize)st.Length());
    env->SetByteArrayRegion(arr, 0, (jsize)st.Length(), reinterpret_cast<const jbyte*>(st.Buffer()));
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_knds_app_NdsCore_nativeLoadState(JNIEnv* env, jclass, jlong h, jbyteArray data)
{
    auto* ctx = ctxOf(h);
    auto bytes = bytesOf(env, data);
    Savestate st(bytes.data(), (u32)bytes.size(), false);
    if (st.Error) return JNI_FALSE;
    bool ok = ctx->nds->DoSavestate(&st) && !st.Error;
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
