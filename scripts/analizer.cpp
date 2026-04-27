#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/stat.h>
#include <sys/types.h>
#include <cerrno>

#ifdef _WIN32
#include <direct.h>
#define mkdir(path, mode) _mkdir(path)
#endif

int main(int argc, char** argv) {
    if (argc < 2) {
        printf("Usage: %s <input_file>\n", argv[0]);
        return 1;
    }

    const char* filename = argv[1];

    struct stat st;
    if (stat("./output", &st) != 0) {
        if (mkdir("./output", 0755) != 0) {
            printf("Error creating ./output: %s\n", strerror(errno));
            return 1;
        } else {
            printf("Created ./output directory\n");
        }
    }

    FILE* f = fopen(filename, "rb");
    if (!f) {
        printf("Error opening file: %s\n", strerror(errno));
        return 1;
    }

    const size_t CHUNK_SIZE = 64 * 1024;
    unsigned char buffer[CHUNK_SIZE];

    size_t total_lines = 0;
    size_t max_line_length = 0;
    size_t current_line_length = 0;

    while (true) {
        size_t bytes_read = fread(buffer, 1, CHUNK_SIZE, f);
        if (bytes_read == 0) {
            if (feof(f)) break;
            if (ferror(f)) {
                printf("Read error\n");
                fclose(f);
                return 1;
            }
        }

        for (size_t i = 0; i < bytes_read; ++i) {
            if (buffer[i] == '\n') {
                total_lines++;
                if (current_line_length > max_line_length) {
                    max_line_length = current_line_length;
                }
                current_line_length = 0;
            } else {
                current_line_length++;
            }
        }
    }

    if (current_line_length > 0) {
        total_lines++;
        if (current_line_length > max_line_length) {
            max_line_length = current_line_length;
        }
    }

    fclose(f);

    printf("Total lines: %zu\n", total_lines);
    printf("Max line length: %zu\n", max_line_length);

    return 0;
}
