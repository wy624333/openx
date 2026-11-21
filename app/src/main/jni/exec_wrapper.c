#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#if __aarch64__
#define SYSTEM_LINKER "/system/bin/linker64"
#else
#define SYSTEM_LINKER "/system/bin/linker"
#endif

typedef int (*execve_func_t)(const char *, char *const[], char *const[]);
typedef int (*execvp_func_t)(const char *, char *const[]);
typedef int (*fexecve_func_t)(int, char *const[], char *const[]);

static execve_func_t real_execve;
static execvp_func_t real_execvp;
static fexecve_func_t real_fexecve;
static const char *app_data_dir;
static size_t app_data_dir_len;

__attribute__((constructor)) static void init(void) {
    void *libc = dlopen("libc.so", RTLD_LAZY | RTLD_NOLOAD);
    if (libc) {
        real_execve = (execve_func_t)dlsym(libc, "execve");
        real_execvp = (execvp_func_t)dlsym(libc, "execvp");
        real_fexecve = (fexecve_func_t)dlsym(libc, "fexecve");
        dlclose(libc);
    }
    app_data_dir = getenv("TERMUX_APP__DATA_DIR");
    if (app_data_dir) app_data_dir_len = strlen(app_data_dir);
}

static bool is_under_app_data(const char *path) {
    if (!app_data_dir || !path) return false;
    return strncmp(path, app_data_dir, app_data_dir_len) == 0;
}

static bool file_starts_with(const char *path, const unsigned char *magic, size_t len) {
    FILE *f = fopen(path, "r");
    if (!f) return false;
    unsigned char buf[16];
    size_t n = fread(buf, 1, len, f);
    fclose(f);
    return (n == len && memcmp(buf, magic, len) == 0);
}

static bool is_elf(const char *path) {
    static const unsigned char elf_magic[4] = {0x7F, 'E', 'L', 'F'};
    return file_starts_with(path, elf_magic, 4);
}

static bool is_shebang(const char *path) {
    static const unsigned char shebang_magic[2] = {'#', '!'};
    return file_starts_with(path, shebang_magic, 2);
}

static char* parse_shebang_interp(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return NULL;
    char buf[256];
    if (!fgets(buf, sizeof(buf), f)) { fclose(f); return NULL; }
    fclose(f);
    if (buf[0] != '#' || buf[1] != '!') return NULL;
    char *p = buf + 2;
    while (*p == ' ' || *p == '\t') p++;
    if (*p == '\0' || *p == '\n') return NULL;
    char *start = p;
    while (*p != ' ' && *p != '\t' && *p != '\n' && *p != '\0') p++;
    size_t len = (size_t)(p - start);
    char *interp = malloc(len + 1);
    if (!interp) return NULL;
    memcpy(interp, start, len);
    interp[len] = '\0';
    return interp;
}

static int redirect_through_linker(const char *pathname, char *const argv[], char *const envp[]) {
    if (!real_execve) return -1;
    int argc = 0;
    if (argv) while (argv[argc]) argc++;
    const char **new_argv = malloc((size_t)(argc + 3) * sizeof(char *));
    if (!new_argv) return -1;
    new_argv[0] = SYSTEM_LINKER;
    new_argv[1] = pathname;
    for (int i = 1; i <= argc; i++) new_argv[i + 1] = argv[i];
    int ret = real_execve(SYSTEM_LINKER, (char *const *)new_argv, envp);
    free(new_argv);
    return ret;
}

static int redirect_shebang_through_linker(const char *interp, const char *script,
                                            char *const argv[], char *const envp[]) {
    if (!real_execve) return -1;
    int argc = 0;
    if (argv) while (argv[argc]) argc++;
    const char **new_argv = malloc((size_t)(argc + 4) * sizeof(char *));
    if (!new_argv) return -1;
    new_argv[0] = SYSTEM_LINKER;
    new_argv[1] = interp;
    new_argv[2] = script;
    for (int i = 1; i <= argc; i++) new_argv[i + 2] = argv[i];
    int ret = real_execve(SYSTEM_LINKER, (char *const *)new_argv, envp);
    free(new_argv);
    return ret;
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    if (is_under_app_data(pathname)) {
        if (is_elf(pathname))
            return redirect_through_linker(pathname, argv, envp);
        if (is_shebang(pathname)) {
            char *interp = parse_shebang_interp(pathname);
            if (interp) {
                int rc = redirect_shebang_through_linker(interp, pathname, argv, envp);
                free(interp);
                if (rc != -1) return rc;
            }
        }
    }
    if (real_execve) return real_execve(pathname, argv, envp);
    return -1;
}

int execvp(const char *file, char *const argv[]) {
    if (strchr(file, '/')) {
        extern char **environ;
        return execve(file, argv, environ);
    }
    extern char **environ;
    return execvpe(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (strchr(file, '/')) {
        return execve(file, argv, envp);
    }
    if (!real_execve) return -1;
    const char *path = getenv("PATH");
    if (!path) path = "/system/bin";
    char *path_dup = strdup(path);
    if (!path_dup) return -1;
    int ret = -1;
    char *saveptr, *dir = strtok_r(path_dup, ":", &saveptr);
    while (dir) {
        char buf[4096];
        snprintf(buf, sizeof(buf), "%s/%s", dir, file);
        ret = execve(buf, argv, envp);
        if (ret != -1) break;
        dir = strtok_r(NULL, ":", &saveptr);
    }
    free(path_dup);
    if (ret == -1) errno = ENOENT;
    return ret;
}

int execv(const char *path, char *const argv[]) {
    extern char **environ;
    return execve(path, argv, environ);
}

int fexecve(int fd, char *const argv[], char *const envp[]) {
    if (real_fexecve) return real_fexecve(fd, argv, envp);
    return -1;
}
