#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/fanotify.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>

namespace {
volatile sig_atomic_t g_running = 1;

void onSignal(int) {
    g_running = 0;
}

void formatNow(char* out, size_t outSize) {
    time_t now = time(nullptr);
    struct tm localTime;
    localtime_r(&now, &localTime);

    if (out == nullptr || outSize == 0) {
        return;
    }

    out[0] = '\0';
    strftime(out, outSize, "%Y_%m%b%d_%H:%M.%S", &localTime);

    for (size_t i = 0; out[i] != '\0'; ++i) {
        char& c = out[i];
        if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }
}

void printUsage(const char* prog) {
    std::fprintf(stderr, "Usage: %s <folder_path>\n", prog);
}
}  // namespace

int main(int argc, char* argv[]) {
    if (argc != 2) {
        printUsage(argv[0]);
        return 1;
    }

    const char* folderPath = argv[1];

    signal(SIGINT, onSignal);
    signal(SIGTERM, onSignal);

    int fanFd = fanotify_init(FAN_CLASS_NOTIF | FAN_CLOEXEC | FAN_REPORT_FID, O_RDONLY | O_CLOEXEC);
    if (fanFd < 0) {
        std::fprintf(
            stderr,
            "fanotify_init failed: %s (run with root/suid permissions)\n",
            std::strerror(errno));
        return 1;
    }

    const uint64_t mask = FAN_CREATE | FAN_MOVED_TO;
    if (fanotify_mark(
            fanFd,
            FAN_MARK_ADD | FAN_MARK_ONLYDIR,
            mask,
            AT_FDCWD,
            folderPath) < 0) {
        std::fprintf(
            stderr,
            "fanotify_mark failed for '%s': %s\n",
            folderPath,
            std::strerror(errno));
        close(fanFd);
        return 1;
    }

    constexpr size_t kBufferSize = 4096;
    alignas(struct fanotify_event_metadata) char buffer[kBufferSize];

    char stdinLine[1024] = {0};
    size_t stdinLen = 0;
    bool watchStdin = true;

    while (g_running) {
        struct pollfd fds[2];
        fds[0].fd = fanFd;
        fds[0].events = POLLIN;
        fds[0].revents = 0;

        fds[1].fd = watchStdin ? STDIN_FILENO : -1;
        fds[1].events = watchStdin ? POLLIN : 0;
        fds[1].revents = 0;

        int pollResult = poll(fds, 2, -1);
        if (pollResult < 0) {
            if (errno == EINTR) {
                continue;
            }
            std::fprintf(stderr, "poll failed: %s\n", std::strerror(errno));
            break;
        }

        if (watchStdin && (fds[1].revents & POLLIN) != 0) {
            char ch = '\0';
            ssize_t readCount = read(STDIN_FILENO, &ch, 1);
            if (readCount > 0) {
                if (ch == '\n' || ch == '\r') {
                    stdinLine[stdinLen] = '\0';
                    if (stdinLen >= 4 &&
                        stdinLine[0] == 'e' &&
                        stdinLine[1] == 'x' &&
                        stdinLine[2] == 'i' &&
                        stdinLine[3] == 't') {
                        g_running = 0;
                        break;
                    }
                    stdinLen = 0;
                } else if (stdinLen + 1 < sizeof(stdinLine)) {
                    stdinLine[stdinLen++] = ch;
                }
            } else if (readCount == 0) {
                // stdin closed; keep running silently with fanotify only.
                watchStdin = false;
            }
        }

        if (!g_running) {
            break;
        }

        if ((fds[0].revents & POLLIN) != 0) {
            ssize_t bytesRead = read(fanFd, buffer, sizeof(buffer));
            if (bytesRead < 0) {
                if (errno == EINTR) {
                    continue;
                }
                std::fprintf(stderr, "read failed: %s\n", std::strerror(errno));
                break;
            }

            if (bytesRead == 0) {
                continue;
            }

            for (struct fanotify_event_metadata* meta =
                     reinterpret_cast<struct fanotify_event_metadata*>(buffer);
                 FAN_EVENT_OK(meta, static_cast<ssize_t>(bytesRead));
                 meta = FAN_EVENT_NEXT(meta, bytesRead)) {
                if (meta->vers != FANOTIFY_METADATA_VERSION) {
                    std::fprintf(stderr, "Incompatible fanotify metadata version\n");
                    g_running = 0;
                    break;
                }

                if (meta->fd >= 0) {
                    close(meta->fd);
                }

                if (meta->mask & (FAN_CREATE | FAN_MOVED_TO)) {
                    char timestamp[64] = {0};
                    formatNow(timestamp, sizeof(timestamp));
                    std::printf("Updated at %s\n", timestamp);
                }
            }
        }
    }

    close(fanFd);
    return 0;
}
