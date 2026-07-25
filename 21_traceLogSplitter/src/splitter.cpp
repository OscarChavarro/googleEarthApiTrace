#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/stat.h>
#include <sys/types.h>
#include <cerrno>

#include "ProgressMonitorConsole.h"

#ifdef _WIN32
#include <direct.h>
#define mkdir(path, mode) _mkdir(path)
#endif

#define MAX_LINE 2048

static int ensure_output_dir(const char *output) {
    struct stat st;
    if (stat(output, &st) != 0) {
        if (mkdir(output, 0755) != 0) {
            printf("Error creating %s: %s\n", output, strerror(errno));
            return 0;
        }
    }
    return 1;
}

static FILE* open_output_file(int index) {
    char path[64];

    snprintf(path, sizeof(path), "./output/%05d", index);
    if (!ensure_output_dir(path)) {
    }

    snprintf(path, sizeof(path), "./output/%05d/gl.txt", index);
    FILE* f = fopen(path, "wb");
    if (!f) {
        printf("Error opening output file %s: %s\n", path, strerror(errno));
    }
    return f;
}

static int count_output_frames(FILE* input, size_t* frame_count) {
    char line[MAX_LINE];
    size_t swap_count = 0;

    while (fgets(line, sizeof(line), input) != NULL) {
        if (strstr(line, "glXSwapBuffers") != NULL) {
            swap_count++;
        }
    }
    if (ferror(input)) {
        printf("Read error while counting frames: %s\n", strerror(errno));
        return 0;
    }
    if (fseek(input, 0, SEEK_SET) != 0) {
        printf("Error rewinding input file after counting frames: %s\n", strerror(errno));
        return 0;
    }

    // The splitter creates frame 1 before reading and opens one more frame
    // after every glXSwapBuffers, including the allowed final empty file.
    *frame_count = swap_count + 1;
    return 1;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        printf("Usage: %s <input_file>\n", argv[0]);
        return 1;
    }

    if (!ensure_output_dir("./output")) {
        return 1;
    }

    const char* filename = argv[1];
    FILE* in = fopen(filename, "rb");
    if (!in) {
        printf("Error opening input file: %s\n", strerror(errno));
        return 1;
    }

    size_t frame_count = 0;
    if (!count_output_frames(in, &frame_count)) {
        fclose(in);
        return 1;
    }
    printf("Frames detected: %zu\n", frame_count);
    printf("Splitting frames: ");
    ProgressMonitorConsole progress;
    progress.begin();

    int file_index = 1;
    FILE* out = open_output_file(file_index);
    if (!out) {
        fclose(in);
        return 1;
    }
    progress.update(0, frame_count, file_index);

    char line[MAX_LINE];
    size_t lines_written = 0;

    while (fgets(line, sizeof(line), in) != NULL) {
        // Escribe la línea tal cual
        if (fputs(line, out) == EOF) {
            printf("Write error: %s\n", strerror(errno));
            fclose(out);
            fclose(in);
            return 1;
        }

        lines_written++;

        // Si la línea contiene "glXSwapBuffers", rota archivo
        if (strstr(line, "glXSwapBuffers") != NULL) {
            if (fclose(out) != 0) {
                printf("Error closing output file: %s\n", strerror(errno));
                fclose(in);
                return 1;
            }
            file_index++;
            out = open_output_file(file_index);
            if (!out) {
                fclose(in);
                return 1;
            }
            progress.update(0, frame_count, file_index);
        }
    }

    if (ferror(in)) {
        printf("Read error while splitting frames: %s\n", strerror(errno));
        fclose(out);
        fclose(in);
        return 1;
    }

    // Cerrar último archivo
    if (out && fclose(out) != 0) {
        printf("Error closing output file: %s\n", strerror(errno));
        fclose(in);
        return 1;
    }
    if (fclose(in) != 0) {
        printf("Error closing input file: %s\n", strerror(errno));
        return 1;
    }

    progress.end();
    printf("Done. Files created: %d\n", file_index);
    printf("Lines processed: %zu\n", lines_written);

    return 0;
}
