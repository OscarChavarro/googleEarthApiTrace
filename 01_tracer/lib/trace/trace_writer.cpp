/**************************************************************************
 *
 * Copyright 2007-2009 VMware, Inc.
 * All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 **************************************************************************/


#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <wchar.h>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <string>
#include <thread>
#include <mutex>
#include <deque>
#include <condition_variable>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <png.h>
#include <bzlib.h>

#include "os.hpp"
#include "trace_ostream.hpp"
#include "trace_writer.hpp"
#include "trace_format.hpp"

int THE_FrameNumber = 0;
int THE_TextureId = 0;
int THE_TextureWidth = 0;
int THE_TextureHeight = 0;
int THE_TextureFormat = 0;
int THE_TextureType = 0;
int THE_DrawElementMode = 0;
int THE_DrawElementType = 0;
int THE_DrawElementShouldExport = 0;
unsigned long long THE_DrawElementIndicesBlobId = 0;
int THE_VertexAttribPointerShouldExport = 0;
unsigned long long THE_VertexAttribPointerBlobId = 0;
int THE_VertexAttribPointerAttribIndex = -1;
int THE_BoundArrayBufferId = 0;
int THE_BoundElementArrayBufferId = 0;
int THE_BufferDataShouldExport = 0;
int THE_BufferDataTarget = 0;
int THE_BufferDataBufferId = 0;
int THE_BufferDataIsSubData = 0;
unsigned long long THE_BufferDataOffset = 0;
unsigned long long THE_BufferDataSize = 0;
unsigned long long THE_CurrentGlCallNumber = 0;
int THE_PositionAttribBufferId = 0;
unsigned long long THE_PositionAttribOffset = 0;
int THE_PositionAttribSize = 0;
int THE_PositionAttribStride = 0;
int THE_PositionAttribType = 0;

namespace trace {

static const int THE_GL_COMPRESSED_RGB_S3TC_DXT1_EXT = 0x83F0;
static const int THE_GL_UNSIGNED_BYTE = 0x1401;
static const int THE_GL_ALPHA = 0x1906;
static const int THE_GL_RGB = 0x1907;
static const int THE_GL_RGBA = 0x1908;
static const int THE_GL_LUMINANCE = 0x1909;
static const int THE_GL_LUMINANCE_ALPHA = 0x190A;
static const int THE_GL_BGR = 0x80E0;
static const int THE_GL_BGRA = 0x80E1;
static const int THE_GL_TRIANGLE_STRIP = 0x0005;
static const int THE_GL_TRIANGLES = 0x0004;
static const int THE_GL_LINES = 0x0001;
static const int THE_GL_LINE_LOOP = 0x0002;
static const int THE_GL_LINE_STRIP = 0x0003;
static const int THE_GL_UNSIGNED_SHORT = 0x1403;

static bool isExportableDrawMode(int mode) {
    return mode == THE_GL_TRIANGLE_STRIP
        || mode == THE_GL_TRIANGLES
        || mode == THE_GL_LINES
        || mode == THE_GL_LINE_LOOP
        || mode == THE_GL_LINE_STRIP;
}
static const int THE_GL_FLOAT = 0x1406;
static const int THE_GL_ARRAY_BUFFER = 0x8892;
static const int THE_GL_ELEMENT_ARRAY_BUFFER = 0x8893;
static std::unordered_map<unsigned long long, bool> g_exportedTextureKeys;
static std::unordered_set<unsigned long long> g_pendingTextureKeys;
static std::mutex g_textureExportMutex;
static std::unordered_map<int, unsigned long long> g_drawElementCountByFrame;
static std::unordered_map<int, unsigned long long> g_vertexAttribPointerCountByFrame;
static std::unordered_map<int, unsigned long long> g_bufferDataUpdateCountByFrame;
static std::unordered_map<int, std::vector<uint8_t>> g_arrayBufferSnapshots;
static std::unordered_map<int, std::vector<uint8_t>> g_elementArrayBufferSnapshots;

static void compressedBlobPath(const char *binPath, char *out, size_t outSize) {
    snprintf(out, outSize, "%s.bz2", binPath);
}

static void appendManifestLine(int frameNumber, const char *line) {
    if (!line) {
        return;
    }
    struct stat st = {0};
    if (stat(OUTPUT_DIRECTORY, &st) == -1) {
        mkdir(OUTPUT_DIRECTORY, 0755);
    }
    char frameDir[256];
    snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, frameNumber);
    if (stat(frameDir, &st) == -1) {
        mkdir(frameDir, 0755);
    }
    char manifestPath[512];
    snprintf(manifestPath, sizeof(manifestPath), "%s/manifest.txt", frameDir);
    FILE *m = fopen(manifestPath, "ab");
    if (!m) {
        return;
    }
    fprintf(m, "%s\n", line);
    fclose(m);
}

static void writeDssHeader(FILE *f, int width, int height, size_t dataSize) {
    if (!f) {
        return;
    }

    const uint32_t DDSD_CAPS = 0x00000001u;
    const uint32_t DDSD_HEIGHT = 0x00000002u;
    const uint32_t DDSD_WIDTH = 0x00000004u;
    const uint32_t DDSD_PIXELFORMAT = 0x00001000u;
    const uint32_t DDSD_LINEARSIZE = 0x00080000u;
    const uint32_t DDSCAPS_TEXTURE = 0x00001000u;
    const uint32_t DDPF_FOURCC = 0x00000004u;

    uint32_t header[31] = {0};
    header[0] = 124u; // DDS_HEADER.dwSize
    header[1] = DDSD_CAPS | DDSD_HEIGHT | DDSD_WIDTH | DDSD_PIXELFORMAT | DDSD_LINEARSIZE;
    header[2] = (uint32_t)height;
    header[3] = (uint32_t)width;
    header[4] = (uint32_t)dataSize;
    header[7] = 1u; // dwMipMapCount
    header[18] = 32u; // DDS_PIXELFORMAT.dwSize
    header[19] = DDPF_FOURCC;
    header[20] = 0x31545844u; // "DXT1"
    header[26] = DDSCAPS_TEXTURE;

    static const char magic[4] = {'D', 'D', 'S', ' '};
    fwrite(magic, 1, sizeof(magic), f);
    fwrite(header, sizeof(uint32_t), 31, f);
}

static bool writePngFile(const char *filePath, int width, int height, const uint8_t *rgba) {
    FILE *fp = fopen(filePath, "wb");
    if (!fp) {
        return false;
    }
    png_structp png = png_create_write_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
    if (!png) {
        fclose(fp);
        return false;
    }
    png_infop info = png_create_info_struct(png);
    if (!info) {
        png_destroy_write_struct(&png, nullptr);
        fclose(fp);
        return false;
    }
    if (setjmp(png_jmpbuf(png))) {
        png_destroy_write_struct(&png, &info);
        fclose(fp);
        return false;
    }

    png_init_io(png, fp);
    png_set_IHDR(
        png, info,
        width, height,
        8,
        PNG_COLOR_TYPE_RGBA,
        PNG_INTERLACE_NONE,
        PNG_COMPRESSION_TYPE_DEFAULT,
        PNG_FILTER_TYPE_DEFAULT
    );
    png_write_info(png, info);

    std::vector<png_bytep> rows((size_t)height);
    for (int y = 0; y < height; ++y) {
        const int srcY = (height - 1) - y;
        rows[(size_t)y] = const_cast<png_bytep>(rgba + (size_t)srcY * (size_t)width * 4u);
    }
    png_write_image(png, rows.data());
    png_write_end(png, nullptr);
    png_destroy_write_struct(&png, &info);
    fclose(fp);
    return true;
}

static bool decodeToRgba(const void *ptr, size_t size, int width, int height, int format, int type, std::vector<uint8_t> &rgbaOut) {
    if (!ptr || width <= 0 || height <= 0) {
        return false;
    }
    if (type != THE_GL_UNSIGNED_BYTE) {
        return false;
    }

    int channels = 0;
    if (format == THE_GL_ALPHA || format == THE_GL_LUMINANCE) {
        channels = 1;
    }
    else if (format == THE_GL_LUMINANCE_ALPHA) {
        channels = 2;
    }
    else if (format == THE_GL_RGB || format == THE_GL_BGR) {
        channels = 3;
    }
    else if (format == THE_GL_RGBA || format == THE_GL_BGRA) {
        channels = 4;
    }
    else {
        return false;
    }

    const size_t expected = (size_t)width * (size_t)height * (size_t)channels;
    if (size < expected) {
        return false;
    }

    const uint8_t *src = reinterpret_cast<const uint8_t *>(ptr);
    rgbaOut.resize((size_t)width * (size_t)height * 4u);
    size_t si = 0;
    size_t di = 0;
    for (int i = 0; i < width * height; ++i) {
        uint8_t r = 0, g = 0, b = 0, a = 255;
        if (format == THE_GL_ALPHA) {
            a = src[si + 0];
            r = 255;
            g = 255;
            b = 255;
        }
        else if (format == THE_GL_LUMINANCE) {
            r = src[si + 0];
            g = src[si + 0];
            b = src[si + 0];
        }
        else if (format == THE_GL_LUMINANCE_ALPHA) {
            r = src[si + 0];
            g = src[si + 0];
            b = src[si + 0];
            a = src[si + 1];
        }
        else if (format == THE_GL_RGB) {
            r = src[si + 0];
            g = src[si + 1];
            b = src[si + 2];
        }
        else if (format == THE_GL_BGR) {
            b = src[si + 0];
            g = src[si + 1];
            r = src[si + 2];
        }
        else if (format == THE_GL_RGBA) {
            r = src[si + 0];
            g = src[si + 1];
            b = src[si + 2];
            a = src[si + 3];
        }
        else if (format == THE_GL_BGRA) {
            b = src[si + 0];
            g = src[si + 1];
            r = src[si + 2];
            a = src[si + 3];
        }
        si += (size_t)channels;
        rgbaOut[di + 0] = r;
        rgbaOut[di + 1] = g;
        rgbaOut[di + 2] = b;
        rgbaOut[di + 3] = a;
        di += 4u;
    }
    return true;
}

struct PngExportJob {
    unsigned long long exportKey;
    int frameNumber;
    int textureWidth;
    int textureHeight;
    int textureFormat;
    int textureType;
    int textureId;
    std::vector<uint8_t> blob;
};

class AsyncPngExporter {
public:
    AsyncPngExporter() : m_stopping(false) {
        const int workerCount = chooseWorkerCount();
        m_maxQueueSize = chooseMaxQueueSize();
        for (int i = 0; i < workerCount; ++i) {
            m_workers.emplace_back(&AsyncPngExporter::workerLoop, this);
        }
    }

    ~AsyncPngExporter() {
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_stopping = true;
        }
        m_cv.notify_all();
        for (std::thread &t : m_workers) {
            if (t.joinable()) {
                t.join();
            }
        }
    }

    bool enqueue(PngExportJob &&job) {
        std::unique_lock<std::mutex> lock(m_mutex);
        if (m_stopping) {
            return false;
        }
        while (m_jobs.size() >= m_maxQueueSize && !m_stopping) {
            m_cvNotFull.wait(lock);
        }
        if (m_stopping) {
            return false;
        }
        m_jobs.emplace_back(std::move(job));
        lock.unlock();
        m_cv.notify_one();
        return true;
    }

private:
    static int chooseWorkerCount() {
        const char *env = getenv("TRACE_PNG_THREADS");
        if (env && *env) {
            char *end = nullptr;
            unsigned long parsed = strtoul(env, &end, 10);
            if (end != env && parsed > 0ul) {
                if (parsed > 256ul) {
                    parsed = 256ul;
                }
                if (parsed < 1ul) {
                    parsed = 1ul;
                }
                return (int)parsed;
            }
        }

        long cpuCount = sysconf(_SC_NPROCESSORS_ONLN);
        if (cpuCount <= 0) {
            cpuCount = 4;
        }
        int capped = (int)cpuCount;
        if (capped > 12) {
            capped = 12;
        }
        if (capped < 2) {
            capped = 2;
        }
        return capped;
    }

    static size_t chooseMaxQueueSize() {
        const char *env = getenv("TRACE_PNG_QUEUE");
        if (!env || !*env) {
            return 128u;
        }
        char *end = nullptr;
        unsigned long parsed = strtoul(env, &end, 10);
        if (end == env || parsed == 0ul) {
            return 128u;
        }
        if (parsed > 4096ul) {
            parsed = 4096ul;
        }
        return (size_t)parsed;
    }

    static bool writeJobPng(const PngExportJob &job) {
        std::vector<uint8_t> rgba;
        if (!decodeToRgba(
                job.blob.data(),
                job.blob.size(),
                job.textureWidth,
                job.textureHeight,
                job.textureFormat,
                job.textureType,
                rgba)) {
            return false;
        }

        struct stat st = {0};
        if (stat(OUTPUT_DIRECTORY, &st) == -1) {
            mkdir(OUTPUT_DIRECTORY, 0755);
        }

        char frameDir[256];
        snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, job.frameNumber);
        if (stat(frameDir, &st) == -1) {
            mkdir(frameDir, 0755);
        }

        char pngPath[512];
        snprintf(pngPath, sizeof(pngPath), "%s/%dx%d_%d.png", frameDir, job.textureWidth, job.textureHeight, job.textureId);
        return writePngFile(pngPath, job.textureWidth, job.textureHeight, rgba.data());
    }

    void workerLoop() {
        for (;;) {
            PngExportJob job;
            {
                std::unique_lock<std::mutex> lock(m_mutex);
                m_cv.wait(lock, [&]() { return m_stopping || !m_jobs.empty(); });
                if (m_stopping && m_jobs.empty()) {
                    return;
                }
                job = std::move(m_jobs.front());
                m_jobs.pop_front();
                m_cvNotFull.notify_one();
            }

            const bool ok = writeJobPng(job);
            std::lock_guard<std::mutex> exportLock(g_textureExportMutex);
            g_pendingTextureKeys.erase(job.exportKey);
            if (ok) {
                g_exportedTextureKeys[job.exportKey] = true;
            }
        }
    }

    bool m_stopping;
    size_t m_maxQueueSize;
    std::mutex m_mutex;
    std::condition_variable m_cv;
    std::condition_variable m_cvNotFull;
    std::deque<PngExportJob> m_jobs;
    std::vector<std::thread> m_workers;
};

static AsyncPngExporter &getAsyncPngExporter() {
    static AsyncPngExporter exporter;
    return exporter;
}

class AsyncBzip2BlobCompressor {
public:
    AsyncBzip2BlobCompressor() : m_stopping(false) {
        const int workerCount = chooseWorkerCount();
        m_maxQueueSize = chooseMaxQueueSize();
        for (int i = 0; i < workerCount; ++i) {
            m_workers.emplace_back(&AsyncBzip2BlobCompressor::workerLoop, this);
        }
    }

    ~AsyncBzip2BlobCompressor() {
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            m_stopping = true;
        }
        m_cv.notify_all();
        for (std::thread &t : m_workers) {
            if (t.joinable()) {
                t.join();
            }
        }
    }

    bool enqueue(const char *binPath) {
        if (!binPath || !*binPath) {
            return false;
        }
        std::unique_lock<std::mutex> lock(m_mutex);
        if (m_stopping) {
            return false;
        }
        while (m_jobs.size() >= m_maxQueueSize && !m_stopping) {
            m_cvNotFull.wait(lock);
        }
        if (m_stopping) {
            return false;
        }
        m_jobs.emplace_back(binPath);
        lock.unlock();
        m_cv.notify_one();
        return true;
    }

private:
    static int chooseWorkerCount() {
        const char *env = getenv("TRACE_BZ2_THREADS");
        if (env && *env) {
            char *end = nullptr;
            unsigned long parsed = strtoul(env, &end, 10);
            if (end != env && parsed > 0ul) {
                if (parsed > 256ul) {
                    parsed = 256ul;
                }
                return (int)parsed;
            }
        }
        return 8;
    }

    static size_t chooseMaxQueueSize() {
        const char *env = getenv("TRACE_BZ2_QUEUE");
        if (!env || !*env) {
            return 1024u;
        }
        char *end = nullptr;
        unsigned long parsed = strtoul(env, &end, 10);
        if (end == env || parsed == 0ul) {
            return 1024u;
        }
        if (parsed > 65536ul) {
            parsed = 65536ul;
        }
        return (size_t)parsed;
    }

    static bool compressOne(const std::string &binPath) {
        char bzPath[1024];
        compressedBlobPath(binPath.c_str(), bzPath, sizeof(bzPath));

        char tmpPath[1060];
        snprintf(tmpPath, sizeof(tmpPath), "%s.tmp", bzPath);

        FILE *in = fopen(binPath.c_str(), "rb");
        if (!in) {
            return false;
        }
        FILE *out = fopen(tmpPath, "wb");
        if (!out) {
            fclose(in);
            return false;
        }

        int bzError = BZ_OK;
        BZFILE *bz = BZ2_bzWriteOpen(&bzError, out, 9, 0, 30);
        if (bzError != BZ_OK || !bz) {
            fclose(in);
            fclose(out);
            remove(tmpPath);
            return false;
        }

        uint8_t buffer[1024 * 1024];
        bool ok = true;
        for (;;) {
            size_t n = fread(buffer, 1, sizeof(buffer), in);
            if (n > 0) {
                BZ2_bzWrite(&bzError, bz, buffer, (int)n);
                if (bzError != BZ_OK) {
                    ok = false;
                    break;
                }
            }
            if (n < sizeof(buffer)) {
                if (ferror(in)) {
                    ok = false;
                }
                break;
            }
        }

        int abandon = ok ? 0 : 1;
        BZ2_bzWriteClose(&bzError, bz, abandon, nullptr, nullptr);
        if (ok && bzError != BZ_OK) {
            ok = false;
        }
        if (fclose(in) != 0) {
            ok = false;
        }
        if (fclose(out) != 0) {
            ok = false;
        }

        if (!ok) {
            remove(tmpPath);
            return false;
        }
        if (rename(tmpPath, bzPath) != 0) {
            remove(tmpPath);
            return false;
        }
        remove(binPath.c_str());
        return true;
    }

    void workerLoop() {
        for (;;) {
            std::string path;
            {
                std::unique_lock<std::mutex> lock(m_mutex);
                m_cv.wait(lock, [&]() { return m_stopping || !m_jobs.empty(); });
                if (m_stopping && m_jobs.empty()) {
                    return;
                }
                path = std::move(m_jobs.front());
                m_jobs.pop_front();
                m_cvNotFull.notify_one();
            }
            compressOne(path);
        }
    }

    bool m_stopping;
    size_t m_maxQueueSize;
    std::mutex m_mutex;
    std::condition_variable m_cv;
    std::condition_variable m_cvNotFull;
    std::deque<std::string> m_jobs;
    std::vector<std::thread> m_workers;
};

static AsyncBzip2BlobCompressor &getAsyncBzip2BlobCompressor() {
    static AsyncBzip2BlobCompressor compressor;
    return compressor;
}

static void enqueueBlobCompression(const char *binPath) {
    getAsyncBzip2BlobCompressor().enqueue(binPath);
}

static void exportPlain(const void *ptr, size_t size, int id) {
    if (THE_TextureWidth <= 0 || THE_TextureHeight <= 0 || id <= 0) {
        return;
    }

    unsigned long long exportKey = ((unsigned long long)(unsigned int)THE_FrameNumber << 32) | (unsigned int)id;
    {
        std::lock_guard<std::mutex> lock(g_textureExportMutex);
        if (g_exportedTextureKeys.find(exportKey) != g_exportedTextureKeys.end()) {
            return;
        }
        if (g_pendingTextureKeys.find(exportKey) != g_pendingTextureKeys.end()) {
            return;
        }
    }

    struct stat st = {0};
    if (stat(OUTPUT_DIRECTORY, &st) == -1) {
        mkdir(OUTPUT_DIRECTORY, 0755);
    }

    char frameDir[256];
    snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, THE_FrameNumber);

    if (stat(frameDir, &st) == -1) {
        mkdir(frameDir, 0755);
    }

    if (THE_TextureFormat == THE_GL_COMPRESSED_RGB_S3TC_DXT1_EXT) {
        char filePath[512];
        snprintf(filePath, sizeof(filePath), "%s/%dx%d_%d.dds", frameDir, THE_TextureWidth, THE_TextureHeight, id);
        FILE *f = fopen(filePath, "wb");
        if (f) {
            writeDssHeader(f, THE_TextureWidth, THE_TextureHeight, size);
            fwrite(ptr, 1, size, f);
            fclose(f);
            std::lock_guard<std::mutex> lock(g_textureExportMutex);
            g_exportedTextureKeys[exportKey] = true;
        }
        return;
    }
    if (THE_TextureType != THE_GL_UNSIGNED_BYTE) {
        return;
    }

    PngExportJob job;
    job.exportKey = exportKey;
    job.frameNumber = THE_FrameNumber;
    job.textureWidth = THE_TextureWidth;
    job.textureHeight = THE_TextureHeight;
    job.textureFormat = THE_TextureFormat;
    job.textureType = THE_TextureType;
    job.textureId = id;
    job.blob.assign((const uint8_t *)ptr, (const uint8_t *)ptr + size);

    {
        std::lock_guard<std::mutex> lock(g_textureExportMutex);
        g_pendingTextureKeys.insert(exportKey);
    }
    if (!getAsyncPngExporter().enqueue(std::move(job))) {
        std::lock_guard<std::mutex> lock(g_textureExportMutex);
        g_pendingTextureKeys.erase(exportKey);
    }
}

static void exportDrawElementsBlob(const void *ptr, size_t size) {
    if (!THE_DrawElementShouldExport) {
        return;
    }

    if (!isExportableDrawMode(THE_DrawElementMode) || THE_DrawElementType != THE_GL_UNSIGNED_SHORT) {
        return;
    }

    if (THE_DrawElementIndicesBlobId == 0) {
        return;
    }
    unsigned long long currentBlobId = (unsigned long long)(uintptr_t)ptr;
    if (currentBlobId != THE_DrawElementIndicesBlobId) {
        return;
    }

    struct stat st = {0};
    if (stat(OUTPUT_DIRECTORY, &st) == -1) {
        mkdir(OUTPUT_DIRECTORY, 0755);
    }

    char frameDir[256];
    snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, THE_FrameNumber);
    if (stat(frameDir, &st) == -1) {
        mkdir(frameDir, 0755);
    }

    unsigned long long drawCount = ++g_drawElementCountByFrame[THE_FrameNumber];

    char filePath[512];
    snprintf(filePath, sizeof(filePath), "%s/drawElements_indices_call_%llu.bin", frameDir, THE_CurrentGlCallNumber);

    FILE *f = fopen(filePath, "wb");
    if (f) {
        fwrite(ptr, 1, size, f);
        fclose(f);

        char compressedPath[1024];
        compressedBlobPath(filePath, compressedPath, sizeof(compressedPath));
        char manifestLine[2048];
        snprintf(manifestLine, sizeof(manifestLine), "kind=draw_elements frame=%d call=%llu parserCall=%llu file=%s mode=%d type=%d blobPtr=%llu bytes=%zu compression=bzip2", THE_FrameNumber, THE_CurrentGlCallNumber, drawCount, compressedPath, THE_DrawElementMode, THE_DrawElementType, THE_DrawElementIndicesBlobId, size);
        appendManifestLine(THE_FrameNumber, manifestLine);
        enqueueBlobCompression(filePath);
    }
}

static bool writeBlobToPath(const char *path, const void *ptr, size_t size) {
    FILE *f = fopen(path, "wb");
    if (!f) {
        return false;
    }
    fwrite(ptr, 1, size, f);
    fclose(f);
    enqueueBlobCompression(path);
    return true;
}

static void exportVertexPositionsFromVbo(int frameNumber, unsigned long long drawCall, const std::vector<uint8_t> &indexBytes) {
    if (THE_PositionAttribBufferId <= 0 || THE_PositionAttribType != THE_GL_FLOAT || THE_PositionAttribSize < 3) {
        return;
    }
    auto it = g_arrayBufferSnapshots.find(THE_PositionAttribBufferId);
    if (it == g_arrayBufferSnapshots.end()) {
        return;
    }
    const std::vector<uint8_t> &arraySnapshot = it->second;
    if (arraySnapshot.empty()) {
        return;
    }

    size_t maxIndex = 0;
    bool hasAnyIndex = false;
    for (size_t i = 0; i + 1 < indexBytes.size(); i += 2) {
        const unsigned value = (unsigned)indexBytes[i] | ((unsigned)indexBytes[i + 1] << 8);
        if (!hasAnyIndex || value > maxIndex) {
            maxIndex = value;
            hasAnyIndex = true;
        }
    }
    if (!hasAnyIndex) {
        return;
    }

    const size_t minStride = (size_t)THE_PositionAttribSize * 4u;
    size_t stride = THE_PositionAttribStride > 0 ? (size_t)THE_PositionAttribStride : minStride;
    if (stride < minStride) {
        return;
    }

    const size_t offset = (size_t)THE_PositionAttribOffset;
    const size_t neededBytes = offset + (maxIndex + 1u) * stride;
    if (neededBytes > arraySnapshot.size()) {
        return;
    }

    struct stat st = {0};
    if (stat(OUTPUT_DIRECTORY, &st) == -1) {
        mkdir(OUTPUT_DIRECTORY, 0755);
    }
    char frameDir[256];
    snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, frameNumber);
    if (stat(frameDir, &st) == -1) {
        mkdir(frameDir, 0755);
    }

    const size_t outBytes = (maxIndex + 1u) * stride;
    char filePath[512];
    snprintf(filePath, sizeof(filePath), "%s/glVertexAttribPointer_vertexAttrib_call_%llu.bin", frameDir, drawCall);
    if (!writeBlobToPath(filePath, arraySnapshot.data() + offset, outBytes)) {
        return;
    }

    char compressedPath[1024];
    compressedBlobPath(filePath, compressedPath, sizeof(compressedPath));
    char manifestLine[2048];
    snprintf(
        manifestLine,
        sizeof(manifestLine),
        "kind=vertex_attrib frame=%d call=%llu parserCall=0 file=%s attribIndex=0 bufferId=%d offset=%llu stride=%d size=%d type=%d bytes=%zu source=vbo_snapshot compression=bzip2",
        frameNumber,
        drawCall,
        compressedPath,
        THE_PositionAttribBufferId,
        THE_PositionAttribOffset,
        THE_PositionAttribStride,
        THE_PositionAttribSize,
        THE_PositionAttribType,
        outBytes
    );
    appendManifestLine(frameNumber, manifestLine);
}

void exportDrawElementsFromBoundBuffers(unsigned long long indicesOffsetBytes, unsigned long long indexBytes) {
    if (!THE_DrawElementShouldExport) {
        return;
    }
    if (!isExportableDrawMode(THE_DrawElementMode) || THE_DrawElementType != THE_GL_UNSIGNED_SHORT) {
        return;
    }
    if (THE_BoundElementArrayBufferId <= 0 || indexBytes == 0) {
        return;
    }

    auto it = g_elementArrayBufferSnapshots.find(THE_BoundElementArrayBufferId);
    if (it == g_elementArrayBufferSnapshots.end()) {
        return;
    }
    const std::vector<uint8_t> &snapshot = it->second;
    const size_t offset = (size_t)indicesOffsetBytes;
    const size_t size = (size_t)indexBytes;
    if (offset > snapshot.size() || size > snapshot.size() - offset) {
        return;
    }

    struct stat st = {0};
    if (stat(OUTPUT_DIRECTORY, &st) == -1) {
        mkdir(OUTPUT_DIRECTORY, 0755);
    }
    char frameDir[256];
    snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, THE_FrameNumber);
    if (stat(frameDir, &st) == -1) {
        mkdir(frameDir, 0755);
    }

    char filePath[512];
    snprintf(filePath, sizeof(filePath), "%s/drawElements_indices_call_%llu.bin", frameDir, THE_CurrentGlCallNumber);
    if (!writeBlobToPath(filePath, snapshot.data() + offset, size)) {
        return;
    }

    char compressedPath[1024];
    compressedBlobPath(filePath, compressedPath, sizeof(compressedPath));
    char manifestLine[2048];
    snprintf(
        manifestLine,
        sizeof(manifestLine),
        "kind=draw_elements frame=%d call=%llu parserCall=0 file=%s mode=%d type=%d indicesOffset=%llu bytes=%zu source=ebo_snapshot compression=bzip2",
        THE_FrameNumber,
        THE_CurrentGlCallNumber,
        compressedPath,
        THE_DrawElementMode,
        THE_DrawElementType,
        indicesOffsetBytes,
        size
    );
    appendManifestLine(THE_FrameNumber, manifestLine);

    std::vector<uint8_t> indexSlice(size);
    memcpy(indexSlice.data(), snapshot.data() + offset, size);
    exportVertexPositionsFromVbo(THE_FrameNumber, THE_CurrentGlCallNumber, indexSlice);
}

static void exportVertexAttribPointerBlob(const void *ptr, size_t size) {
    if (!THE_VertexAttribPointerShouldExport) {
        return;
    }

    if (THE_VertexAttribPointerBlobId == 0) {
        return;
    }
    if (THE_VertexAttribPointerBlobId <= 4096ull) {
        return;
    }
    unsigned long long currentBlobId = (unsigned long long)(uintptr_t)ptr;
    if (currentBlobId != THE_VertexAttribPointerBlobId) {
        return;
    }

    struct stat st = {0};
    if (stat(OUTPUT_DIRECTORY, &st) == -1) {
        mkdir(OUTPUT_DIRECTORY, 0755);
    }

    char frameDir[256];
    snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, THE_FrameNumber);
    if (stat(frameDir, &st) == -1) {
        mkdir(frameDir, 0755);
    }

    unsigned long long vertexAttribCount = ++g_vertexAttribPointerCountByFrame[THE_FrameNumber];

    char filePath[512];
    snprintf(filePath, sizeof(filePath), "%s/glVertexAttribPointer_vertexAttrib_call_%llu.bin", frameDir, THE_CurrentGlCallNumber);

    FILE *f = fopen(filePath, "wb");
    if (f) {
        fwrite(ptr, 1, size, f);
        fclose(f);

        char compressedPath[1024];
        compressedBlobPath(filePath, compressedPath, sizeof(compressedPath));
        char manifestLine[2048];
        snprintf(
            manifestLine,
            sizeof(manifestLine),
            "kind=vertex_attrib frame=%d call=%llu parserCall=%llu file=%s attribIndex=%d blobPtr=%llu bytes=%zu source=wrapped_call compression=bzip2",
            THE_FrameNumber,
            THE_CurrentGlCallNumber,
            vertexAttribCount,
            compressedPath,
            THE_VertexAttribPointerAttribIndex,
            THE_VertexAttribPointerBlobId,
            size
        );
        appendManifestLine(THE_FrameNumber, manifestLine);
        enqueueBlobCompression(filePath);
    }
}

void exportVertexAttribPointerBlobForCall(unsigned long long callNo, int attribIndex, const void *ptr, size_t size) {
    if (!ptr || size == 0) {
        return;
    }
    unsigned long long blobPtr = (unsigned long long)(uintptr_t)ptr;
    if (blobPtr <= 4096ull) {
        return;
    }

    struct stat st = {0};
    if (stat(OUTPUT_DIRECTORY, &st) == -1) {
        mkdir(OUTPUT_DIRECTORY, 0755);
    }
    char frameDir[256];
    snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, THE_FrameNumber);
    if (stat(frameDir, &st) == -1) {
        mkdir(frameDir, 0755);
    }

    char filePath[512];
    snprintf(filePath, sizeof(filePath), "%s/glVertexAttribPointer_vertexAttrib_call_%llu.bin", frameDir, callNo);
    if (!writeBlobToPath(filePath, ptr, size)) {
        return;
    }

    char compressedPath[1024];
    compressedBlobPath(filePath, compressedPath, sizeof(compressedPath));
    char manifestLine[2048];
    snprintf(
        manifestLine,
        sizeof(manifestLine),
        "kind=vertex_attrib frame=%d call=%llu parserCall=0 file=%s attribIndex=%d blobPtr=%llu bytes=%zu source=fake_call compression=bzip2",
        THE_FrameNumber,
        callNo,
        compressedPath,
        attribIndex,
        blobPtr,
        size
    );
    appendManifestLine(THE_FrameNumber, manifestLine);
}

static void exportBufferDataBlob(const void *ptr, size_t size) {
    if (!THE_BufferDataShouldExport) {
        return;
    }
    if (THE_BufferDataBufferId <= 0) {
        return;
    }
    if (THE_BufferDataTarget != THE_GL_ARRAY_BUFFER && THE_BufferDataTarget != THE_GL_ELEMENT_ARRAY_BUFFER) {
        return;
    }

    struct stat st = {0};
    if (stat(OUTPUT_DIRECTORY, &st) == -1) {
        mkdir(OUTPUT_DIRECTORY, 0755);
    }

    char frameDir[256];
    snprintf(frameDir, sizeof(frameDir), "%s/%05d", OUTPUT_DIRECTORY, THE_FrameNumber);
    if (stat(frameDir, &st) == -1) {
        mkdir(frameDir, 0755);
    }

    const char *targetLabel = THE_BufferDataTarget == THE_GL_ARRAY_BUFFER ? "arrayBuffer" : "elementArrayBuffer";
    unsigned long long updateId = ++g_bufferDataUpdateCountByFrame[THE_FrameNumber];

    char filePath[512];
    snprintf(
        filePath,
        sizeof(filePath),
        "%s/glBufferData_%s_buffer_%d_update_%llu.bin",
        frameDir,
        targetLabel,
        THE_BufferDataBufferId,
        updateId
    );

    FILE *f = fopen(filePath, "wb");
    if (!f) {
        return;
    }
    fwrite(ptr, 1, size, f);
    fclose(f);
    enqueueBlobCompression(filePath);

    char metaPath[512];
    snprintf(
        metaPath,
        sizeof(metaPath),
        "%s/glBufferData_%s_buffer_%d_update_%llu.meta.txt",
        frameDir,
        targetLabel,
        THE_BufferDataBufferId,
        updateId
    );
    FILE *m = fopen(metaPath, "wb");
    if (!m) {
        return;
    }
    fprintf(m, "frame=%d\n", THE_FrameNumber);
    fprintf(m, "target=%s\n", targetLabel);
    fprintf(m, "bufferId=%d\n", THE_BufferDataBufferId);
    fprintf(m, "updateId=%llu\n", updateId);
    fprintf(m, "operation=%s\n", THE_BufferDataIsSubData ? "glBufferSubData" : "glBufferData");
    fprintf(m, "offset=%llu\n", THE_BufferDataOffset);
    fprintf(m, "declaredSize=%llu\n", THE_BufferDataSize);
    fprintf(m, "exportedBlobSize=%zu\n", size);
    fclose(m);

    std::unordered_map<int, std::vector<uint8_t>> *targetSnapshots = nullptr;
    if (THE_BufferDataTarget == THE_GL_ARRAY_BUFFER) {
        targetSnapshots = &g_arrayBufferSnapshots;
    } else if (THE_BufferDataTarget == THE_GL_ELEMENT_ARRAY_BUFFER) {
        targetSnapshots = &g_elementArrayBufferSnapshots;
    }
    if (!targetSnapshots) {
        return;
    }

    std::vector<uint8_t> &snapshot = (*targetSnapshots)[THE_BufferDataBufferId];
    const size_t writeOffset = (size_t)THE_BufferDataOffset;
    const size_t writeSize = size;
    if (!THE_BufferDataIsSubData) {
        snapshot.assign((const uint8_t *)ptr, (const uint8_t *)ptr + writeSize);
        return;
    }
    if (snapshot.size() < writeOffset + writeSize) {
        snapshot.resize(writeOffset + writeSize, 0);
    }
    memcpy(snapshot.data() + writeOffset, ptr, writeSize);
}

Writer::Writer() :
    call_no(0)
{
    m_file = nullptr;
}

Writer::~Writer()
{
    close();
}

void
Writer::close(void) {
    delete m_file;
    m_file = nullptr;
}

bool
Writer::open(const char *filename,
             unsigned semanticVersion,
             const Properties &properties)
{
    close();

    m_file = createSnappyStream(filename);
    if (!m_file) {
        return false;
    }

    call_no = 0;
    functions.clear();
    structs.clear();
    enums.clear();
    bitmasks.clear();
    frames.clear();

    _writeUInt(TRACE_VERSION);

    assert(semanticVersion <= TRACE_VERSION);
    _writeUInt(semanticVersion);

    beginProperties();
    for (auto & kv : properties) {
        writeProperty(kv.first.c_str(), kv.second.c_str());
    }
    endProperties();

    return true;
}

void inline
Writer::_write(const void *sBuffer, size_t dwBytesToWrite) {
    m_file->write(sBuffer, dwBytesToWrite);
}

void inline
Writer::_writeByte(char c) {
    _write(&c, 1);
}

void inline
Writer::_writeUInt(unsigned long long value) {
    char buf[2 * sizeof value];
    unsigned len;

    len = 0;
    do {
        assert(len < sizeof buf);
        buf[len] = 0x80 | (value & 0x7f);
        value >>= 7;
        ++len;
    } while (value);

    assert(len);
    buf[len - 1] &= 0x7f;

    _write(buf, len);
}

void inline
Writer::_writeFloat(float value) {
    static_assert(sizeof value == 4, "float is not 4 bytes");
    _write((const char *)&value, sizeof value);
}

void inline
Writer::_writeDouble(double value) {
    static_assert(sizeof value == 8, "double is not 8 bytes");
    _write((const char *)&value, sizeof value);
}

void inline
Writer::_writeString(const char *str) {
    size_t len = strlen(str);
    _writeUInt(len);
    _write(str, len);
}

inline bool lookup(std::vector<bool> &map, size_t index) {
    if (index >= map.size()) {
        map.resize(index + 1);
        return false;
    } else {
        return map[index];
    }
}

void Writer::beginBacktrace(unsigned num_frames) {
    if (num_frames) {
        _writeByte(trace::CALL_BACKTRACE);
        _writeUInt(num_frames);
    }
}

void Writer::writeStackFrame(const RawStackFrame *frame) {
    _writeUInt(frame->id);
    if (!lookup(frames, frame->id)) {
        if (frame->module != NULL) {
            _writeByte(trace::BACKTRACE_MODULE);
            _writeString(frame->module);
        }
        if (frame->function != NULL) {
            _writeByte(trace::BACKTRACE_FUNCTION);
            _writeString(frame->function);
        }
        if (frame->filename != NULL) {
            _writeByte(trace::BACKTRACE_FILENAME);
            _writeString(frame->filename);
        }
        if (frame->linenumber >= 0) {
            _writeByte(trace::BACKTRACE_LINENUMBER);
            _writeUInt(frame->linenumber);
        }
        if (frame->offset >= 0) {
            _writeByte(trace::BACKTRACE_OFFSET);
            _writeUInt(frame->offset);
        }
        _writeByte(trace::BACKTRACE_END);
        frames[frame->id] = true;
    }
}

void
Writer::writeFlags(unsigned flags) {
    if (flags) {
        _writeByte(trace::CALL_FLAGS);
        _writeUInt(flags);
    }
}

void
Writer::writeProperty(const char *name, const char *value)
{
    assert(name);
    assert(strlen(name));
    assert(value);
    _writeString(name);
    _writeString(value);
}

void
Writer::endProperties(void)
{
    _writeUInt(0);  // zero-length string
}

unsigned Writer::beginEnter(const FunctionSig *sig, unsigned thread_id) {
    THE_CurrentGlCallNumber = (unsigned long long)call_no;
    _writeByte(trace::EVENT_ENTER);
    _writeUInt(thread_id);
    _writeUInt(sig->id);
    if (!lookup(functions, sig->id)) {
        _writeString(sig->name);
        _writeUInt(sig->num_args);
        for (unsigned i = 0; i < sig->num_args; ++i) {
            _writeString(sig->arg_names[i]);
        }
        functions[sig->id] = true;
    }

    return call_no++;
}

void Writer::endEnter(void) {
    _writeByte(trace::CALL_END);
}

void Writer::beginLeave(unsigned call) {
    _writeByte(trace::EVENT_LEAVE);
    _writeUInt(call);
}

void Writer::endLeave(void) {
    _writeByte(trace::CALL_END);
}

void Writer::beginArg(unsigned index) {
    _writeByte(trace::CALL_ARG);
    _writeUInt(index);
}

void Writer::beginReturn(void) {
    _writeByte(trace::CALL_RET);
}

void Writer::beginArray(size_t length) {
    _writeByte(trace::TYPE_ARRAY);
    _writeUInt(length);
}

void Writer::beginStruct(const StructSig *sig) {
    _writeByte(trace::TYPE_STRUCT);
    _writeUInt(sig->id);
    if (!lookup(structs, sig->id)) {
        _writeString(sig->name);
        _writeUInt(sig->num_members);
        for (unsigned i = 0; i < sig->num_members; ++i) {
            _writeString(sig->member_names[i]);
        }
        structs[sig->id] = true;
    }
}

void Writer::beginRepr(void) {
    _writeByte(trace::TYPE_REPR);
}

void Writer::writeBool(bool value) {
    _writeByte(value ? trace::TYPE_TRUE : trace::TYPE_FALSE);
}

void Writer::writeSInt(signed long long value) {
    if (value < 0) {
        _writeByte(trace::TYPE_SINT);
        _writeUInt(-value);
    } else {
        _writeByte(trace::TYPE_UINT);
        _writeUInt(value);
    }
}

void Writer::writeUInt(unsigned long long value) {
    _writeByte(trace::TYPE_UINT);
    _writeUInt(value);
}

void Writer::writeFloat(float value) {
    _writeByte(trace::TYPE_FLOAT);
    _writeFloat(value);
}

void Writer::writeDouble(double value) {
    _writeByte(trace::TYPE_DOUBLE);
    _writeDouble(value);
}

void Writer::writeString(const char *str) {
    if (!str) {
        Writer::writeNull();
        return;
    }
    _writeByte(trace::TYPE_STRING);
    _writeString(str);
}

void Writer::writeString(const char *str, size_t len) {
    if (!str) {
        Writer::writeNull();
        return;
    }
    _writeByte(trace::TYPE_STRING);
    _writeUInt(len);
    _write(str, len);
}

void Writer::writeWString(const wchar_t *str, size_t len) {
    if (!str) {
        Writer::writeNull();
        return;
    }
    _writeByte(trace::TYPE_WSTRING);
    _writeUInt(len);
    for (size_t i = 0; i < len; ++i) {
        _writeUInt(str[i]);
    }
}

void Writer::writeWString(const wchar_t *str) {
    if (!str) {
        Writer::writeNull();
        return;
    }
    size_t len = wcslen(str);
    writeWString(str, len);
}

void Writer::writeBlob(const void *data, size_t size) {
    if (!data) {
        Writer::writeNull();
        return;
    }

    if (THE_TextureFormat != 0 && THE_TextureWidth > 0 && THE_TextureHeight > 0) {
        exportPlain(data, size, THE_TextureId);
    }

    exportBufferDataBlob(data, size);
    exportVertexAttribPointerBlob(data, size);
    exportDrawElementsBlob(data, size);

    _writeByte(trace::TYPE_BLOB);
    _writeUInt(size);
    if (size) {
        _write(data, size);
    }
}

void Writer::writeEnum(const EnumSig *sig, signed long long value) {
    _writeByte(trace::TYPE_ENUM);
    _writeUInt(sig->id);
    if (!lookup(enums, sig->id)) {
        _writeUInt(sig->num_values);
        for (unsigned i = 0; i < sig->num_values; ++i) {
            _writeString(sig->values[i].name);
            writeSInt(sig->values[i].value);
        }
        enums[sig->id] = true;
    }
    writeSInt(value);
}

void Writer::writeBitmask(const BitmaskSig *sig, unsigned long long value) {
    _writeByte(trace::TYPE_BITMASK);
    _writeUInt(sig->id);
    if (!lookup(bitmasks, sig->id)) {
        _writeUInt(sig->num_flags);
        for (unsigned i = 0; i < sig->num_flags; ++i) {
            if (i != 0 && sig->flags[i].value == 0) {
                os::log("apitrace: warning: sig %s is zero but is not first flag\n", sig->flags[i].name);
            }
            _writeString(sig->flags[i].name);
            _writeUInt(sig->flags[i].value);
        }
        bitmasks[sig->id] = true;
    }
    _writeUInt(value);
}

void Writer::writeNull(void) {
    _writeByte(trace::TYPE_NULL);
}

void Writer::writePointer(unsigned long long addr) {
    if (!addr) {
        Writer::writeNull();
        return;
    }
    _writeByte(trace::TYPE_OPAQUE);
    _writeUInt(addr);
}

} /* namespace trace */
