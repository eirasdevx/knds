/*
    KNDS — estado nativo de una instancia del emulador.
    Licencia: GPLv3 (igual que melonDS).
*/
#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "NDS.h"

namespace knds
{

struct Context
{
    std::unique_ptr<melonDS::NDS> nds;
    std::string savePath;
    std::string romName = "game.nds";
    bool directBoot = true;

    std::mutex saveLock;
    std::vector<melonDS::u8> saveData;
    bool saveDirty = false;

    std::atomic<bool> stopped {false};
    std::atomic<bool> micBlow {false};

    // Micrófono real: la app escribe muestras (44,1 kHz, mono) y melonDS las lee.
    std::mutex micLock;
    std::vector<melonDS::s16> micRing = std::vector<melonDS::s16>(44100);
    size_t micRead = 0, micWrite = 0, micLevel = 0;
    std::atomic<bool> micLive {false};
};

} // namespace knds
