/*
    KNDS — implementación de melonDS::Platform para Android (y Linux, para pruebas).
    Licencia: GPLv3 (igual que melonDS).
*/

#include "Platform.h"
#include "SPI_Firmware.h"
#include "knds_context.h"

#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <dlfcn.h>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <sys/stat.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace melonDS::Platform
{

std::string g_baseDir = ".";

void SignalStop(StopReason reason, void* userdata)
{
    if (auto* ctx = static_cast<knds::Context*>(userdata)) ctx->stopped = true;
}

// ------------------------------------------------------------------ archivos

static std::string ModeString(FileMode mode, bool exists)
{
    std::string m;
    if (mode & FileMode::Append) m += 'a';
    else if (!(mode & FileMode::Write)) m += 'r';
    else if (mode & FileMode::NoCreate) m += 'r';
    else if ((mode & FileMode::Preserve) && exists) m += 'r';
    else m += 'w';
    if ((mode & FileMode::ReadWrite) == FileMode::ReadWrite) m += '+';
    if (!(mode & FileMode::Text)) m += 'b';
    return m;
}

std::string GetLocalFilePath(const std::string& filename)
{
    if (!filename.empty() && filename[0] == '/') return filename;
    return g_baseDir + "/" + filename;
}

FileHandle* OpenFile(const std::string& path, FileMode mode)
{
    if ((mode & FileMode::ReadWrite) == FileMode::None) return nullptr;
    bool exists = FileExists(path);
    if ((mode & FileMode::NoCreate) && !exists) return nullptr;
    FILE* f = fopen(path.c_str(), ModeString(mode, exists).c_str());
    return reinterpret_cast<FileHandle*>(f);
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode)
{
    return OpenFile(GetLocalFilePath(path), mode);
}

bool FileExists(const std::string& name)
{
    struct stat st {};
    return stat(name.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool LocalFileExists(const std::string& name) { return FileExists(GetLocalFilePath(name)); }

bool CheckFileWritable(const std::string& filepath)
{
    FILE* f = fopen(filepath.c_str(), "ab");
    if (!f) return false;
    fclose(f);
    return true;
}

bool CheckLocalFileWritable(const std::string& filepath) { return CheckFileWritable(GetLocalFilePath(filepath)); }

bool CloseFile(FileHandle* file) { return fclose(reinterpret_cast<FILE*>(file)) == 0; }
bool IsEndOfFile(FileHandle* file) { return feof(reinterpret_cast<FILE*>(file)) != 0; }

bool FileReadLine(char* str, int count, FileHandle* file)
{
    return fgets(str, count, reinterpret_cast<FILE*>(file)) != nullptr;
}

u64 FilePosition(FileHandle* file) { return (u64)ftell(reinterpret_cast<FILE*>(file)); }

bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin)
{
    int o = origin == FileSeekOrigin::Start ? SEEK_SET : origin == FileSeekOrigin::Current ? SEEK_CUR : SEEK_END;
    return fseek(reinterpret_cast<FILE*>(file), (long)offset, o) == 0;
}

void FileRewind(FileHandle* file) { rewind(reinterpret_cast<FILE*>(file)); }

u64 FileRead(void* data, u64 size, u64 count, FileHandle* file)
{
    return fread(data, size, count, reinterpret_cast<FILE*>(file));
}

bool FileFlush(FileHandle* file) { return fflush(reinterpret_cast<FILE*>(file)) == 0; }

u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file)
{
    return fwrite(data, size, count, reinterpret_cast<FILE*>(file));
}

u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    int r = vfprintf(reinterpret_cast<FILE*>(file), fmt, args);
    va_end(args);
    return r < 0 ? 0 : (u64)r;
}

u64 FileLength(FileHandle* file)
{
    FILE* f = reinterpret_cast<FILE*>(file);
    long pos = ftell(f);
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, pos, SEEK_SET);
    return (u64)len;
}

// ------------------------------------------------------------------ registro

void Log(LogLevel level, const char* fmt, ...)
{
    if (level == LogLevel::Debug) return;
    va_list args;
    va_start(args, fmt);
#ifdef __ANDROID__
    int prio = level == LogLevel::Error ? ANDROID_LOG_ERROR : level == LogLevel::Warn ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_vprint(prio, "melonDS", fmt, args);
#else
    vfprintf(stderr, fmt, args);
#endif
    va_end(args);
}

// ------------------------------------------------------------------ hilos y sincronización

struct Thread { std::thread t; };

Thread* Thread_Create(std::function<void()> func) { return new Thread{std::thread(std::move(func))}; }

void Thread_Free(Thread* thread)
{
    if (thread->t.joinable()) thread->t.detach();
    delete thread;
}

void Thread_Wait(Thread* thread)
{
    if (thread->t.joinable()) thread->t.join();
}

struct Semaphore
{
    std::mutex m;
    std::condition_variable cv;
    int count = 0;
};

Semaphore* Semaphore_Create() { return new Semaphore(); }
void Semaphore_Free(Semaphore* sema) { delete sema; }

void Semaphore_Reset(Semaphore* sema)
{
    std::lock_guard<std::mutex> l(sema->m);
    sema->count = 0;
}

void Semaphore_Wait(Semaphore* sema)
{
    std::unique_lock<std::mutex> l(sema->m);
    sema->cv.wait(l, [sema] { return sema->count > 0; });
    sema->count--;
}

bool Semaphore_TryWait(Semaphore* sema, int timeout_ms)
{
    std::unique_lock<std::mutex> l(sema->m);
    if (timeout_ms == 0) {
        if (sema->count == 0) return false;
    } else if (!sema->cv.wait_for(l, std::chrono::milliseconds(timeout_ms), [sema] { return sema->count > 0; })) {
        return false;
    }
    sema->count--;
    return true;
}

void Semaphore_Post(Semaphore* sema, int count)
{
    {
        std::lock_guard<std::mutex> l(sema->m);
        sema->count += count;
    }
    sema->cv.notify_all();
}

struct Mutex { std::mutex m; };

Mutex* Mutex_Create() { return new Mutex(); }
void Mutex_Free(Mutex* mutex) { delete mutex; }
void Mutex_Lock(Mutex* mutex) { mutex->m.lock(); }
void Mutex_Unlock(Mutex* mutex) { mutex->m.unlock(); }
bool Mutex_TryLock(Mutex* mutex) { return mutex->m.try_lock(); }

void Sleep(u64 usecs) { std::this_thread::sleep_for(std::chrono::microseconds(usecs)); }

static const auto g_start = std::chrono::steady_clock::now();

u64 GetMSCount()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - g_start).count();
}

u64 GetUSCount()
{
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - g_start).count();
}

// ------------------------------------------------------------------ guardado

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    auto* ctx = static_cast<knds::Context*>(userdata);
    if (!ctx) return;
    std::lock_guard<std::mutex> l(ctx->saveLock);
    ctx->saveData.assign(savedata, savedata + savelen);
    ctx->saveDirty = true;
}

void WriteGBASave(const u8*, u32, u32, u32, void*) {}
void WriteFirmware(const Firmware&, u32, u32, void*) {}
void WriteDateTime(int, int, int, int, int, int, void*) {}

// ------------------------------------------------------------------ wifi local / red (no soportado)

void MP_Begin(void*) {}
void MP_End(void*) {}
int MP_SendPacket(u8*, int, u64, void*) { return 0; }
int MP_RecvPacket(u8*, u64*, void*) { return 0; }
int MP_SendCmd(u8*, int, u64, void*) { return 0; }
int MP_SendReply(u8*, int, u64, u16, void*) { return 0; }
int MP_SendAck(u8*, int, u64, void*) { return 0; }
int MP_RecvHostPacket(u8*, u64*, void*) { return 0; }
u16 MP_RecvReplies(u8*, u64, u16, void*) { return 0; }
int Net_SendPacket(u8*, int, void*) { return 0; }
int Net_RecvPacket(u8*, void*) { return 0; }

// ------------------------------------------------------------------ cámara (DSi), micrófono, accesorios

void Camera_Start(int, void*) {}
void Camera_Stop(int, void*) {}

void Camera_CaptureFrame(int, u32* frame, int width, int height, bool, void*)
{
    for (int i = 0; i < width * height; i++) frame[i] = 0x80108010;
}

void Mic_Start(void*) {}
void Mic_Stop(void*) {}

int Mic_ReadInput(s16* data, int maxlength, void* userdata)
{
    auto* ctx = static_cast<knds::Context*>(userdata);
    // "Soplar" al micrófono: ruido sencillo mientras el usuario mantiene el botón
    if (ctx && ctx->micBlow) {
        for (int i = 0; i < maxlength; i++) data[i] = (s16)((rand() & 0x7FFF) - 0x4000);
        return maxlength;
    }
    // Micrófono real del móvil (si está activado): lo que haya en el búfer; el resto, silencio.
    int n = 0;
    if (ctx && ctx->micLive) {
        std::lock_guard<std::mutex> l(ctx->micLock);
        size_t cap = ctx->micRing.size();
        while (n < maxlength && ctx->micLevel > 0) {
            data[n++] = ctx->micRing[ctx->micRead];
            ctx->micRead = (ctx->micRead + 1) % cap;
            ctx->micLevel--;
        }
    }
    for (int i = n; i < maxlength; i++) data[i] = 0;
    return maxlength;
}

struct AACDecoder { int dummy; };
AACDecoder* AAC_Init() { return nullptr; }
void AAC_DeInit(AACDecoder* dec) { delete dec; }
bool AAC_Configure(AACDecoder*, int, int) { return false; }
bool AAC_DecodeFrame(AACDecoder*, const void*, int, void*, int) { return false; }

bool Addon_KeyDown(KeyType, void*) { return false; }
void Addon_RumbleStart(u32, void*) {}
void Addon_RumbleStop(void*) {}
float Addon_MotionQuery(MotionQueryType, void*) { return 0.f; }

// ------------------------------------------------------------------ bibliotecas dinámicas (lo usa el JIT en Android)

struct DynamicLibrary;

DynamicLibrary* DynamicLibrary_Load(const char* lib)
{
    return reinterpret_cast<DynamicLibrary*>(dlopen(lib, RTLD_NOW | RTLD_LOCAL));
}

void DynamicLibrary_Unload(DynamicLibrary* lib)
{
    if (lib) dlclose(lib);
}

void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name)
{
    return lib ? dlsym(lib, name) : nullptr;
}

} // namespace melonDS::Platform
