#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <sys/inotify.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <dirent.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>

volatile sig_atomic_t g_running = 1;

void
onSignal(int) {
    g_running = 0;
}

void
formatNow(char* out, size_t outSize) {
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

void
printUsage(const char* prog) {
    std::fprintf(stderr, "Usage: %s <folder_path>\n", prog);
}

void
installSignalHandlers() {
    signal(SIGINT, onSignal);
    signal(SIGTERM, onSignal);
}

int
addWatchRecursive(
    int inotifyFd,
    const std::string& folderPath,
    std::unordered_map<int, std::string>& wdToPath) {
    const uint32_t watchMask = IN_CREATE | IN_MOVED_TO;
    int wd = inotify_add_watch(inotifyFd, folderPath.c_str(), watchMask);
    if (wd < 0) {
        std::fprintf(
            stderr,
            "inotify_add_watch failed for '%s': %s\n",
            folderPath.c_str(),
            std::strerror(errno));
        return false;
    }
    wdToPath[wd] = folderPath;

    DIR* dir = opendir(folderPath.c_str());
    if (dir == nullptr) {
        std::fprintf(stderr, "opendir failed for '%s': %s\n", folderPath.c_str(), std::strerror(errno));
        return false;
    }

    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type != DT_DIR) {
            continue;
        }

        if (std::strcmp(entry->d_name, ".") == 0 || std::strcmp(entry->d_name, "..") == 0) {
            continue;
        }

        std::string childPath = folderPath;
        childPath += "/";
        childPath += entry->d_name;

        if (!addWatchRecursive(inotifyFd, childPath, wdToPath)) {
            closedir(dir);
            return false;
        }
    }

    closedir(dir);
    return true;
}

int
initializeInotify(const char* folderPath, std::unordered_map<int, std::string>& wdToPath) {
    int inotifyFd = inotify_init1(IN_CLOEXEC);
    if (inotifyFd < 0) {
        std::fprintf(stderr, "inotify_init1 failed: %s\n", std::strerror(errno));
        return -1;
    }

    if (!addWatchRecursive(inotifyFd, folderPath, wdToPath)) {
        close(inotifyFd);
        return -1;
    }

    return inotifyFd;
}

int
waitForEvents(int inotifyFd, bool watchStdin, struct pollfd* fds) {
    fds[0].fd = inotifyFd;
    fds[0].events = POLLIN;
    fds[0].revents = 0;

    fds[1].fd = watchStdin ? STDIN_FILENO : -1;
    fds[1].events = watchStdin ? POLLIN : 0;
    fds[1].revents = 0;

    int pollResult = poll(fds, 2, -1);
    if (pollResult < 0 && errno != EINTR) {
        std::fprintf(stderr, "poll failed: %s\n", std::strerror(errno));
    }
    return pollResult;
}

void
processStdin(bool* watchStdin, char* stdinLine, size_t* stdinLen) {
    char ch = '\0';
    ssize_t readCount = read(STDIN_FILENO, &ch, 1);

    if (readCount > 0) {
        if (ch == '\n' || ch == '\r') {
            stdinLine[*stdinLen] = '\0';
            if (*stdinLen >= 4 &&
                stdinLine[0] == 'e' &&
                stdinLine[1] == 'x' &&
                stdinLine[2] == 'i' &&
                stdinLine[3] == 't') {
                g_running = 0;
                return;
            }
            *stdinLen = 0;
        } else if (*stdinLen + 1 < 1024) {
            stdinLine[(*stdinLen)++] = ch;
        }
        return;
    }

    if (readCount == 0) {
        *watchStdin = false;
    }
}

bool
handleSingleInotifyEvent(
    const struct inotify_event* event,
    std::unordered_map<int, std::string>& wdToPath,
    int inotifyFd) {
    if ((event->mask & (IN_CREATE | IN_MOVED_TO)) != 0) {
        char timestamp[64] = {0};
        formatNow(timestamp, sizeof(timestamp));
        std::printf("Updated at %s\n", timestamp);

        if ((event->mask & IN_ISDIR) != 0 && event->len > 0) {
            auto it = wdToPath.find(event->wd);
            if (it != wdToPath.end()) {
                std::string createdDirPath = it->second;
                createdDirPath += "/";
                createdDirPath += event->name;
                if (!addWatchRecursive(inotifyFd, createdDirPath, wdToPath)) {
                    return false;
                }
            }
        }
    }

    return true;
}

bool
processInotifyEvents(
    int inotifyFd,
    char* buffer,
    size_t bufferSize,
    std::unordered_map<int, std::string>& wdToPath) {
    ssize_t bytesRead = read(inotifyFd, buffer, bufferSize);
    if (bytesRead < 0) {
        if (errno == EINTR) {
            return true;
        }
        std::fprintf(stderr, "read failed: %s\n", std::strerror(errno));
        return false;
    }

    if (bytesRead == 0) {
        return true;
    }

    size_t offset = 0;
    while (offset + sizeof(struct inotify_event) <= static_cast<size_t>(bytesRead)) {
        const struct inotify_event* event =
            reinterpret_cast<const struct inotify_event*>(buffer + offset);
        if (!handleSingleInotifyEvent(event, wdToPath, inotifyFd)) {
            return false;
        }
        offset += sizeof(struct inotify_event) + event->len;
    }

    return true;
}

int
runEventLoop(int inotifyFd, std::unordered_map<int, std::string>& wdToPath) {
    constexpr size_t kBufferSize = 16 * 1024;
    alignas(struct inotify_event) char buffer[kBufferSize];

    char stdinLine[1024] = {0};
    size_t stdinLen = 0;
    bool watchStdin = true;

    while (g_running) {
        struct pollfd fds[2];
        int pollResult = waitForEvents(inotifyFd, watchStdin, fds);

        if (pollResult < 0) {
            if (errno == EINTR) {
                continue;
            }
            return 1;
        }

        if (watchStdin && (fds[1].revents & POLLIN) != 0) {
            processStdin(&watchStdin, stdinLine, &stdinLen);
        }

        if (!g_running) {
            break;
        }

        if ((fds[0].revents & POLLIN) != 0) {
            if (!processInotifyEvents(inotifyFd, buffer, sizeof(buffer), wdToPath)) {
                return 1;
            }
        }
    }

    return 0;
}

int
main(int argc, char* argv[]) {
    if (argc != 2) {
        printUsage(argv[0]);
        return 1;
    }

    installSignalHandlers();

    std::unordered_map<int, std::string> wdToPath;
    int inotifyFd = initializeInotify(argv[1], wdToPath);
    if (inotifyFd < 0) {
        return 1;
    }

    int result = runEventLoop(inotifyFd, wdToPath);
    close(inotifyFd);
    return result;
}
